package com.mss.polymech.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.mps.kelvin.physical.KelvinConstants;
import com.mss.polymech.space.RealAstroData;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 把 {@link RealAstroData} 导出为 kelvin 的 {@code space_data/**} 数据包
 * （照 space 0.1.3 的 {@code data/space/space_data/solar_system/**} 布局，
 * 见 {@code docs/mps-clone-plan.md} §17）。
 *
 * <h2>产出三类文件</h2>
 * <pre>
 *   data/poly_mech/space_data/space/type.json                  → 太空世界 poly_mech:space
 *   data/poly_mech/space_data/space/object/&lt;天体 id&gt;.json      → 天体（star / planet）
 *   data/poly_mech/space_data/space/world/&lt;维度 ns&gt;/&lt;维度名&gt;.json → 地表维度参数
 * </pre>
 * 目录名取 {@code space}，于是太空世界 id = {@code poly_mech:space} ——
 * <b>与本项目既有的 {@link PlanetDimensions#SPACE} 完全一致</b>（space 那边叫
 * {@code solar_system}；换名字只是为了让既有维度 id 不必全库改名）。
 *
 * <h2>天体名用 id 而不是中文名（这是硬约束）</h2>
 * 文件名（去掉 .json）会成为 {@code CelestialBody.name}，而
 * {@code SpaceWorld} 判"恒星"用的正是 <b>名字里是否含 sun</b>
 * → 恒星的 {@code fixed} 标志靠它。若用中文名"太阳"，太阳会被当成普通卫星去积分。
 * 中文名只属于 GUI 层，不进 kelvin。
 *
 * <h2>速度是"算"出来的，不是抄的</h2>
 * space 的 {@code object/*.json} 里带真实的 {@code speed} 向量，但那是它的数据集，
 * 不能照搬。这里用<b>牛顿力学的圆轨道初速度</b>从"质量 + 位置"反推：
 * <pre>
 *   v 的<b>大小</b> = sqrt(G·M_中心 / |r − r_中心|)
 *   v 的<b>方向</b> = normalize((r − r_中心) × Y)     // Y = 黄道北，即顺行
 *   卫星再叠加母星自身速度（否则卫星会脱离母星）
 * </pre>
 * 方向式子是<b>对着参考数据反推验证过</b>的：对地球，{@code (−rz, 0, rx)} 归一化后
 * 与 space 的 {@code speed=[30185,1.90,2951]} 方向一致；对火星同样吻合
 * （{-rz, rx} 与 space 的 {@code [-10835,241,-24197]} 同向）。大小相差约 4%
 * （真实轨道有偏心率，圆轨道是近似），但足以形成稳定绕转。
 *
 * <p><b>BODIES 的顺序因此变成硬约束</b>：母星必须排在卫星之前，
 * 否则取母星速度时还没算出来（见 {@link RealAstroData#BODIES}）。</p>
 *
 * <h2>三类"暂无自有数据"的字段（有意留白，不是漏了）</h2>
 * <ul>
 *   <li><b>{@code atmospheric}</b> 整块不发：它需要分层标高（ray/mie/obsorption）
 *       与摩尔质量等大气模型参数，我方没有自采数据；照抄 space 的数值等于照抄它的数据集。
 *       后果：{@code Planet.getAtmosphericHeight()} 保持 -1，
 *       {@code Meteoroid} 的"是否进入大气"恒为假，流星不会烧蚀 —— 待补。</li>
 *   <li><b>{@code rotate}</b> 发单位四元数、<b>{@code rotate_speed}</b> 不发（=0）：
 *       没有自有的自转轴倾角与自转速率数据。后果：天体不自转（纯观感，不影响轨道）。</li>
 *   <li><b>{@code texture}</b> 必须发（读取端会直接 {@code getAsString()}，缺了会 NPE），
 *       但发的是<b>占位路径</b> {@code poly_mech:textures/celestial_body/planet/&lt;id&gt;/surface.png}：
 *       本项目的行星由着色器程序化渲染，目前没有运行时消费者读这个字段。</li>
 * </ul>
 *
 * <h2>引力写"绝对 m/s²"而不是倍数</h2>
 * {@code world/*.json} 的 {@code gravity} 照 space 的语义写<b>绝对重力加速度</b>
 * （地球 9.807、火星 3.72…），而 {@link PlanetDimensions#gravity(int)} 返回的是
 * <b>以地球为 1.0 的倍数</b>。两者换算关系是 {@code abs = factor × 9.807}，
 * 这里就按此换算发出 —— 将来把消费方迁到 {@code CelestialWorld.G} 时，
 * 要么除回 9.807，要么把本项目的重力链整体改成绝对值。
 */
public class ModSpaceDataProvider implements DataProvider {

    /** space_data 下的一级目录，同时也是太空世界 id 的名字部分。 */
    private static final String WORLD_DIR = "space";
    /** 地表"高度归一"的终点 Y；与合成维度类型的 min_y(-64) + height(384) 对齐。 */
    private static final double SURFACE_WORLD_TOP_Y = 320.0;
    /** 贴图投影的经度半长（space 的长球面投影参数；只影响地表→球面的映射）。 */
    private static final double POS_SHADOW_LONGITUDE_LENGTH = 100_000.0;
    /** 标准重力加速度，用于 factor → m/s² 换算。 */
    private static final double STANDARD_GRAVITY = 9.807;

    private final PackOutput.PathProvider pathProvider;

    public ModSpaceDataProvider(PackOutput output) {
        this.pathProvider = output.createPathProvider(PackOutput.Target.DATA_PACK, "space_data");
    }

    @Override
    public String getName() {
        return "Polymech Space Data";
    }

    @Override
    public CompletableFuture<?> run(CachedOutput output) {
        List<CompletableFuture<?>> futures = new ArrayList<>();

        // type.json：读取端会把它 addFirst，必须是第一个存在的世界定义
        futures.add(DataProvider.saveStable(output, typeJson(),
                this.pathProvider.json(loc(WORLD_DIR + "/type"))));

        // 天体：按 BODIES 顺序一遍即可（母星在前，卫星取其速度）
        Map<String, double[]> velocities = new LinkedHashMap<>();
        for (RealAstroData body : RealAstroData.BODIES) {
            double[] velocity = orbitalVelocity(body, velocities);
            velocities.put(body.id(), velocity);
            futures.add(DataProvider.saveStable(output, objectJson(body, velocity),
                    this.pathProvider.json(loc(WORLD_DIR + "/object/" + body.id()))));
        }

        // 地表维度参数：只给"可传送"的天体（气态巨行星与恒星没有地表维度）
        for (RealAstroData body : RealAstroData.BODIES) {
            int index = RealAstroData.indexOf(body.id());
            if (!PlanetDimensions.isTeleportable(index)) {
                continue;
            }
            ResourceLocation dimension = PlanetDimensions.dimension(index).location();
            futures.add(DataProvider.saveStable(output, worldJson(index),
                    this.pathProvider.json(loc(WORLD_DIR + "/world/"
                            + dimension.getNamespace() + "/" + dimension.getPath()))));
        }

        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    // ==================== JSON 构造 ====================

    private static JsonObject typeJson() {
        JsonObject json = new JsonObject();
        // 本项目太空天空由着色器渲染，客户端目前不读这个字段；指向真实存在的立方体贴图，
        // 至少保证引用可解析（space 那边是一张等距柱状全景图）。
        json.addProperty("sky_texture", Polymech.MOD_ID + ":textures/gui/skybox/cubemap/cubemap_space0.png");
        return json;
    }

    private static JsonObject objectJson(RealAstroData body, double[] velocity) {
        JsonObject json = new JsonObject();
        boolean star = body.bodyType() == RealAstroData.BodyType.STAR;
        json.addProperty("type", star ? "star" : "planet");
        json.addProperty("scale", body.radiusMeters());
        json.addProperty("carmen_line_height", body.carmenLineHeightMeters());
        json.add("pos", vec3(body.posX(), body.posY(), body.posZ()));
        // 无自有自转轴数据 → 单位四元数（见类注释）
        JsonArray rotate = new JsonArray();
        rotate.add(0.0);
        rotate.add(0.0);
        rotate.add(0.0);
        rotate.add(1.0);
        json.add("rotate", rotate);

        if (star) {
            // 太阳有效温度 5772 K（公开天文常数）—— 恒星分支的必填字段
            json.addProperty("temperature", 5772.0);
        } else {
            // 必填：读取端直接 getAsString()，缺失会 NPE（见类注释的占位说明）
            json.addProperty("texture",
                    Polymech.MOD_ID + ":textures/celestial_body/planet/" + body.id() + "/surface.png");
        }

        int index = RealAstroData.indexOf(body.id());
        if (PlanetDimensions.isTeleportable(index)) {
            JsonObject dimension = new JsonObject();
            dimension.addProperty("id", PlanetDimensions.dimension(index).location().toString());
            json.add("dimension", dimension);
        }

        json.addProperty("mass", body.massKg());
        json.add("speed", vec3(velocity[0], velocity[1], velocity[2]));
        json.addProperty("rotate_speed", 0.0);
        json.addProperty("compute", true);
        return json;
    }

    private static JsonObject worldJson(int planetIndex) {
        JsonObject json = new JsonObject();
        json.addProperty("gravity", PlanetDimensions.gravity(planetIndex) * STANDARD_GRAVITY);
        json.addProperty("height", SURFACE_WORLD_TOP_Y);
        JsonArray center = new JsonArray();
        center.add(0.0);
        center.add(0.0);
        json.add("pos_shadow_center", center);
        json.addProperty("pos_shadow_rotate", 0.0);
        json.addProperty("pos_shadow_longitude_length", POS_SHADOW_LONGITUDE_LENGTH);
        return json;
    }

    // ==================== 轨道初速度 ====================

    /**
     * 日心天体的初速度。
     *
     * <p><b>2026-09 起改成"真实轨道要素 → 真实椭圆"</b>（原来是构造圆速度，见 {@link #circularVelocity}）：
     * 位置本来就是真实星历，只有速度被换成了"在快照半径上的圆轨道速度"，
     * 于是水星周期快 27%、火星快 15%、且所有轨道都被压进一个平面（用户实测"天体连成一条线"）。
     * 现在用公开的 JPL 近似轨道要素（J2000 的 a/e/i/Ω，天文常数）反推出**穿过当前位置**的
     * 真实椭圆初速度 —— 位置一个数不动，轨道却变成真实的椭圆+真实倾角+真实周期。</p>
     *
     * <p>要素表里没有的天体（当前只有卫星群与恒星）走原路径：卫星的构造速度已能把周期
     * 做到 0.01~0.05% 误差，真实倾角要引入母星极轴，是另一件事，**不在本轮范围内**。</p>
     *
     * @param velocities 已算好的天体速度表；母星必须已在表中
     */
    private static double[] orbitalVelocity(RealAstroData body, Map<String, double[]> velocities) {
        RealAstroData parent = RealAstroData.parentOf(body);
        if (parent == null) {
            if (body.bodyType() == RealAstroData.BodyType.STAR) {
                return new double[]{0.0, 0.0, 0.0};
            }
            Elements elements = REAL_ELEMENTS.get(body.id());
            if (elements != null) {
                return velocityFromElements(body, elements);
            }
            // 兜底：不在要素表里就照旧（行为与今天完全一致）
            return circularVelocity(body.posX(), body.posY(), body.posZ(), RealAstroData.SUN.massKg());
        }

        // 卫星：母星速度 + 绕母星的圆轨道速度（本轮不改）
        double[] parentVelocity = velocities.get(parent.id());
        double[] relative = circularVelocity(
                body.posX() - parent.posX(),
                body.posY() - parent.posY(),
                body.posZ() - parent.posZ(),
                parent.massKg());
        return new double[]{
                parentVelocity[0] + relative[0],
                parentVelocity[1] + relative[1],
                parentVelocity[2] + relative[2]};
    }

    /** 一个天文单位（米）。 */
    private static final double AU_METERS = 1.495978707e11;

    /** 公开的 JPL 近似轨道要素（J2000）：半长轴(AU)、偏心率、倾角(°)、升交点经度(°)。 */
    private record Elements(double aAu, double e, double incDeg, double nodeDeg) {
    }

    private static final Map<String, Elements> REAL_ELEMENTS = Map.ofEntries(
            Map.entry("mercury", new Elements(0.38709927, 0.20563593, 7.00497902, 48.33076593)),
            Map.entry("venus", new Elements(0.72333566, 0.00677672, 3.39467605, 76.67984255)),
            Map.entry("earth", new Elements(1.00000261, 0.01671123, -0.00001531, 0.0)),
            Map.entry("mars", new Elements(1.52371034, 0.09339410, 1.84969142, 49.55953891)),
            Map.entry("jupiter", new Elements(5.20288700, 0.04838624, 1.30439695, 100.47390909)),
            Map.entry("saturn", new Elements(9.53667594, 0.05386179, 2.48599187, 113.66242448)),
            Map.entry("uranus", new Elements(19.18916464, 0.04725744, 0.77263783, 74.01692503)),
            Map.entry("neptune", new Elements(30.06992276, 0.00859048, 1.77004347, 131.78422574)),
            Map.entry("pluto", new Elements(39.48211675, 0.24882730, 17.14001206, 110.30393684)));

    /**
     * 由真实轨道要素推出<b>穿过当前真实位置</b>的椭圆初速度。
     *
     * <pre>
     *   法线 n̂ = (sin i·sin Ω,  cos i,  −sin i·cos Ω)      ← ⚠️ z 分量是负的（见下）
     *   面内顺行方向 v̂ = n̂ × r̂
     *   速率 |v| = √( μ(2/r − 1/a) )                        ← vis-viva，保证是椭圆（e 由要素定）
     * </pre>
     *
     * <p><b>法线为什么要带那个负号</b>：它不是推出来的，是<b>对表对出来的</b>——
     * 拿 space 数据包里公开星历的真实状态向量做参照（仅作行为验证），
     * 地球（i≈0）检验面内方向、水星（i=7.005°）检验面外分量：
     * 带 + 号算出 vy/|v| = 0.0801，带 − 号算出 0.1003，真实值 0.1027 ⇒ 取负号。
     * 两个独立样本同时对齐，才敢写死。</p>
     *
     * <p>偏心率不直接参与构造：它由 (r, v) 与 a 通过 vis-viva 与角动量<b>自然涌现</b>，
     * 所以生成时会顺便把"涌现的 e"与要素表里的 e 对一下（差得多说明位置与要素不同历元）。</p>
     */
    private static double[] velocityFromElements(RealAstroData body, Elements el) {
        double rx = body.posX();
        double ry = body.posY();
        double rz = body.posZ();
        double rm = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double i = Math.toRadians(el.incDeg());
        double om = Math.toRadians(el.nodeDeg());
        double nx = Math.sin(i) * Math.sin(om);
        double ny = Math.cos(i);
        double nz = -Math.sin(i) * Math.cos(om);

        double ux = rx / rm;
        double uy = ry / rm;
        double uz = rz / rm;
        // ★ 把法线对 r̂ 做正交化：让轨道面**精确包含当前位置**。
        // 为什么必须这一步：我们的位置（公开星历快照）与要素表给出的 (i, Ω) 不可能严格共面
        // （实测水星位置离面 0.06°）。若不处理，圆锥曲线构造会被那点离面量污染 ——
        // e 从 0.206 掉到 0.186、周期也跟着偏。正交化后 (a, e) 是**精确**的，
        // 只有倾角会带着那 0.06° 的残差（可接受，且它本来就不是我们数据的精度瓶颈）。
        double nd = nx * ux + ny * uy + nz * uz;
        nx -= nd * ux;
        ny -= nd * uy;
        nz -= nd * uz;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(nl > 0.0)) {
            return circularVelocity(rx, ry, rz, RealAstroData.SUN.massKg());
        }
        nx /= nl;
        ny /= nl;
        nz /= nl;

        // 切向单位向量（面内顺行）：θ̂ = n̂ × r̂
        double tx = ny * uz - nz * uy;
        double ty = nz * ux - nx * uz;
        double tz = nx * uy - ny * ux;
        double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (!(tl > 0.0)) {
            // 位置与轨道面法线平行（退化）：回退圆速度，绝不产出 NaN
            return circularVelocity(rx, ry, rz, RealAstroData.SUN.massKg());
        }
        tx /= tl;
        ty /= tl;
        tz /= tl;

        // ★ 偏心率必须"喂进去"，不能靠 v⊥r 自然涌现：
        //   v⊥r 意味着当前位置就是拱点 ⇒ 涌现出的 e 只能是 |r/a − 1|，
        //   而真实位置一般不在拱点（实测：天王星 0.004 vs 真实 0.047、
        //   冥王星 0.000 vs 真实 0.249）。所以按圆锥曲线把速度拆成
        //   **径向 + 切向**两部分，e 就精确等于公开要素的 e：
        //     p = a(1−e²)，h = √(μp)，cos ν = (p/r − 1)/e
        //     v_r = (μ/h)·e·sin ν ，v_t = h/r
        //   sin ν 取正号只是一种选择（决定近拱点在当前点之前还是之后），
        //   轨道**形状/周期/倾角与它无关**。
        double mu = KelvinConstants.GRAVITATIONAL_CONSTANT * RealAstroData.SUN.massKg();
        double a = el.aAu() * AU_METERS;
        double e = el.e();
        double p = a * (1.0 - e * e);
        if (!(p > 0.0)) {
            return circularVelocity(rx, ry, rz, RealAstroData.SUN.massKg());
        }
        double hAng = Math.sqrt(mu * p);
        double cosNu = (p / rm - 1.0) / e;
        cosNu = Math.max(-1.0, Math.min(1.0, cosNu));
        double sinNu = Math.sqrt(Math.max(0.0, 1.0 - cosNu * cosNu));
        double vRadial = mu / hAng * e * sinNu;
        double vTangent = hAng / rm;
        double fx = ux * vRadial + tx * vTangent;
        double fy = uy * vRadial + ty * vTangent;
        double fz = uz * vRadial + tz * vTangent;

        // 涌现出的 e/i 自检（应当精确复现要素；差得多说明位置本身与要素不同历元）
        double hx = ry * fz - rz * fy;
        double hy = rz * fx - rx * fz;
        double hz = rx * fy - ry * fx;
        double ex = (fy * hz - fz * hy) / mu - ux;
        double ey = (fz * hx - fx * hz) / mu - uy;
        double ez = (fx * hy - fy * hx) / mu - uz;
        double eEmergent = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (Math.abs(eEmergent - el.e()) > 0.02) {
            Polymech.LOGGER.warn("[Kelvin] [datagen] {} 的涌现偏心率 {} 与公开要素 {} 相差较大"
                            + "（位置与要素可能不同历元，轨道形状仍按 vis-viva+要素面确定）",
                    body.id(), String.format(java.util.Locale.ROOT, "%.4f", eEmergent),
                    String.format(java.util.Locale.ROOT, "%.4f", el.e()));
        }
        return new double[]{fx, fy, fz};
    }

    /** {@code |v| = sqrt(G·M/r)}，方向 {@code normalize(r × Y)}（Y = 黄道北，顺行）。 */
    private static double[] circularVelocity(double rx, double ry, double rz, double centralMassKg) {
        double distance = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (distance == 0.0) {
            return new double[]{0.0, 0.0, 0.0};
        }
        double speed = Math.sqrt(KelvinConstants.GRAVITATIONAL_CONSTANT * centralMassKg / distance);
        // r × Y = (−rz, 0, rx)
        double dirX = -rz;
        double dirZ = rx;
        double dirLength = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLength == 0.0) {
            // 沿 Y 轴的退化轨道（极点正上方）：没有唯一顺行方向
            return new double[]{0.0, 0.0, 0.0};
        }
        return new double[]{dirX / dirLength * speed, 0.0, dirZ / dirLength * speed};
    }

    // ==================== 工具 ====================

    private static ResourceLocation loc(String path) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, path);
    }

    private static JsonArray vec3(double x, double y, double z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }
}
