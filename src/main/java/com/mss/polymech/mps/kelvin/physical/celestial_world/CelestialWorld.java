package com.mss.polymech.mps.kelvin.physical.celestial_world;

import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector2d;
import org.joml.Vector3d;

/**
 * 天体世界（"某颗行星的地表维度"）—— <b>与
 * {@code org.cn_grass_block.kelvin.physical.celestial_world.CelestialWorld} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把一个<b>行星地表维度</b>和一颗 {@link Planet} 在天体世界里的位姿绑起来，
 * 并负责两套坐标之间的换算：<b>地表世界坐标 ⇄ 太空坐标</b>。
 *
 * <h2>为什么需要这一层（照 space 0.1.3）</h2>
 * 地表是一个普通 MC 维度（平的世界：x/z 是经纬、y 是高度），而行星在太空里是一个球。
 * 两者必须<b>双向</b>可换算，否则：地表的位置放不进太空（飞船找不到你在哪），
 * 太空的天体也落不到地表（着陆点算不出来）。换算规则集中在这一个类里，
 * 别处一律调它 —— 散开必错。
 *
 * <h2>三条关键约定（都别改）</h2>
 * <ol>
 *   <li><b>进太空时 x 取负</b>（{@code x = -x}）：地表与太空的手性不同，
 *       不减这个负号，行星会转反方向。</li>
 *   <li><b>经纬度是等距投影</b>：{@code latitude = x0/length·π/2}、
 *       {@code longitude = z0/length·π/2}，再拼成单位球向量乘半径 —— 等于把
 *       地表这块"方毯"贴到球上（能覆盖到极点，但高纬会挤）。</li>
 *   <li><b>高度沿卡门线归一</b>：{@code r = R·(1 + h_ratio·(carmenLine/R))}，
 *       即"地表 y 从 MinY 到 Height"线性映射到"半径 R 到 R+卡门线"。
 *       所以 {@link #Height}/{@link #MinY} 与行星的 {@code carmenLineHeight}
 *       必须一起看，改一个就要重新想另一个。</li>
 * </ol>
 *
 * <p>{@code pos_shadow}（中心 / 旋转 / 经度长度）是数据侧给的"地表贴图参数"：
 * 决定这块毯子贴到球面的哪一块、转多少。它被 {@link #getPosShadowData()} 以<b>副本</b>
 * 形式收发，避免调用方直接改到内部状态。</p>
 */
public class CelestialWorld {

    public final Planet celestialBody;
    public final ResourceLocation WorldID;
    /** 对应的太空维度 id（地表 → 太空的跳转目标）。 */
    public final ResourceLocation SpaceWorldID;
    private posShadowData pos_shadow;

    /** 行星表面重力（供 gravity 那条线读取）。 */
    public double G = 1.0;
    /** 地表世界的"顶"（y 上限，坐标换算用）。 */
    public double Height = 1024.0;
    /** 地表世界的"底"（y 下限）。 */
    public double MinY = 0.0;

    protected CelestialWorld(Planet celestialBody, ResourceLocation WorldID, ResourceLocation SpaceWorldID) {
        this.celestialBody = celestialBody;
        this.WorldID = WorldID;
        this.SpaceWorldID = SpaceWorldID;
        this.pos_shadow = new posShadowData(new Vector2d(), 0.0, 1024.0);
    }

    @Override
    public String toString() {
        return this.WorldID.toString();
    }

    @Override
    public int hashCode() {
        return this.WorldID.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof CelestialWorld celestialWorld && celestialWorld.WorldID.equals(this.WorldID);
    }

    public void setPosShadowData(posShadowData pos_shadow) {
        this.pos_shadow = new posShadowData(pos_shadow);
    }

    /** 返回副本（内部状态不外卖）。 */
    public posShadowData getPosShadowData() {
        return new posShadowData(this.pos_shadow);
    }

    // ==================== 地表 → 太空 ====================

    public Vector3d getSpacePosFromWorldPos(Vec3 worldPos, float partialTick) {
        return this.getSpacePosFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    public Vector3d getSpacePosFromWorldPos(Vector3d worldPos, float partialTick) {
        return this.getSpacePosFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    public Vector3d getSpacePosFromWorldPos(double x, double y, double z, float partialTick) {
        // ★ 2026-09-22 按用户硬需求改：**天空必须与 MC 的东西南北对应**（见 docs §31.15）。
        //   space 的原始约定是"世界 X → 纬度(南北)、世界 Z → 经度(东西)"外加一个 x=-x 镜像；
        //   实测判据（MC罗盘：世界东·物理东 = 世界南·物理南 = 0.0000，两根轴都**垂直**）
        //   证实那套约定相对 MC 罗盘整整转了 90°。改成：
        //     经度 ← 世界 +X（MC 东；经度增大的方向就是"物理东" = 极轴 × 上方向）
        //     纬度 ← −世界 +Z（MC 南；我们的 +纬度 = 北，故取负）
        //   并**去掉 x=-x 那个镜像**。这是**有意偏离** space 的映射；过渡映射 EarthSpaceMapping 同步改。
        double dx = x - this.pos_shadow.center.x();
        double dz = z - this.pos_shadow.center.y();
        double cos = Math.cos(this.pos_shadow.rotate);
        double sin = Math.sin(this.pos_shadow.rotate);
        double x0 = dx * cos + dz * sin;
        double z0 = dx * sin + dz * cos;
        // ★ 2026-09-23 符号订正（判据实测：世界东·物理东 = -1.0000 ⇒ 只差 180°）：
        //   物理东 = 极轴 × 上方向，在 lon=0 处指向 **−Z** ⇒ **经度增大的方向是"西"**，
        //   所以"世界 +X = MC 东"必须取负。纬度侧 `−世界 +Z` 本来是对的（判据那行曾把"南"
        //   算成 `−(东×上)` = 北，那是判据自己的标签错，已同步修正）。
        double longitude = -x0 / this.pos_shadow.longitude_length * (Math.PI / 2);
        double latitude = -z0 / this.pos_shadow.longitude_length * (Math.PI / 2);
        double heightRatio = (y - this.MinY) / (this.Height - this.MinY);
        double r = this.celestialBody.getRadius()
                * (1.0 + heightRatio * (this.celestialBody.getCarmenLineHeight() / this.celestialBody.getRadius()));
        Vector3d space = new Vector3d(
                Math.cos(latitude) * Math.cos(longitude),
                Math.sin(latitude),
                Math.cos(latitude) * Math.sin(longitude)).mul(r);
        // 用 surfaceSpinRotate 而不是直接 getSmoothRotate：客户端在地表维度会把它换成
        // "贴合原版昼夜"的时钟角（见该方法 javadoc）；默认实现与 space 0.1.3 逐字一致。
        this.surfaceSpinRotate(partialTick).invert().transform(space)
                .add(this.celestialBody.getSmoothPos(partialTick));
        return space;
    }

    /**
     * 地表观察者帧用的<b>自转</b>四元数 —— 就是上面那行 {@code space = spin⁻¹·v + bodyPos} 里的 {@code spin}。
     *
     * <p><b>默认 = 物理自转</b>（{@code getSmoothRotate}），与 space 0.1.3 逐字一致。
     * {@code ClientCelestialWorld} 在地表维度重写它，改成<b>由原版 {@code dayTime} 直接推出来的时钟角</b>：
     * 这是用户拍板的方向 —— 别的模组只认原版时钟，我们不可能让它们接受"真实世界时间"，
     * 所以地表天空必须贴合原版昼夜。推导见 {@code docs/mps-clone-plan.md} §31.11。</p>
     *
     * <p><b>为什么必须是自转、而不能"把观察者帧绕天顶转一下"</b>：绕天顶只改<b>方位角</b>，
     * <b>高度角不变</b>，太阳升不起来。只有绕行星自转轴转，才会改变"观察者经度相对太阳"的量，
     * 从而改变太阳高度角。</p>
     */
    protected Quaterniond surfaceSpinRotate(float partialTick) {
        return new Quaterniond(this.celestialBody.getSmoothRotate(partialTick));
    }

    /**
     * 观察者所在天体的<b>地理北极在宇宙系里的方向</b>（倾角与自转都对上，不是写死的 +Y）。
     *
     * <p><b>推导</b>：映射里 {@code v = (cos lat·cos lon, sin lat, cos lat·sin lon)}，纬度是相对
     * <b>天体本地 +Y</b> 量出来的；宇宙系里 {@code up = spin⁻¹·v̂}，于是
     * {@code up·(spin⁻¹·Y) = v̂·Y = sin lat} ⇒ <b>北极 = spin⁻¹·Y</b>。</p>
     *
     * <p><b>为什么不能写死 {@code (0,1,0)}</b>：地表客户端把 {@code surfaceSpinRotate} 换成了纯 Y 的
     * 时钟角，此时 {@code spin⁻¹·Y = Y} 恰好相等，写死看不出错；但一旦切回<b>物理自转</b>
     * （自转是 {@code tilt·rotateY(ωt)}），{@code spin⁻¹·Y ≠ Y} —— 写死会让"东"与纬度整体算歪，
     * 而且症状正是"太阳跑到南边"。所以这里把映射、姿态帧、诊断三处统一到同一个来源。</p>
     */
    public Vector3d getSurfacePole(float partialTick) {
        return this.surfaceSpinRotate(partialTick).invert().transform(new Vector3d(0.0, 1.0, 0.0));
    }

    // ==================== 太空 → 地表 ====================

    public Vector3d getWorldPosFromSpacePos(Vec3 spacePos, float partialTick) {
        return this.getWorldPosFromSpacePos(spacePos.x(), spacePos.y(), spacePos.z(), partialTick);
    }

    public Vector3d getWorldPosFromSpacePos(Vector3d spacePos, float partialTick) {
        return this.getWorldPosFromSpacePos(spacePos.x(), spacePos.y(), spacePos.z(), partialTick);
    }

    public Vector3d getWorldPosFromSpacePos(double x, double y, double z, float partialTick) {
        Vector3d relative = new Vector3d(x, y, z)
                .sub(this.celestialBody.getSmoothPos(partialTick))
                .rotate(new Quaterniond(this.celestialBody.getSmoothRotate(partialTick)));
        double r = relative.length();
        Vector3d normal = new Vector3d(relative).normalize();
        double latitude = Math.asin(normal.y);
        double longitude = Math.atan2(normal.z, normal.x);
        // 与 getSpacePosFromWorldPos 同一约定（§31.15）：世界 X ← −经度、世界 Z ← −纬度。
        double x0 = -longitude / (Math.PI / 2) * this.pos_shadow.longitude_length;
        double z0 = -latitude / (Math.PI / 2) * this.pos_shadow.longitude_length;
        double cos = Math.cos(this.pos_shadow.rotate);
        double sin = Math.sin(this.pos_shadow.rotate);
        return new Vector3d(
                x0 * cos - z0 * sin + this.pos_shadow.center.x(),
                this.MinY + (r - this.celestialBody.getRadius())
                        / this.celestialBody.getCarmenLineHeight() * this.Height,
                x0 * sin + z0 * cos + this.pos_shadow.center.y());
    }

    // ==================== 姿态 ====================

    public Quaterniond getRotateFromWorldPos(Vec3 worldPos, float partialTick) {
        return this.getRotateFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    public Quaterniond getRotateFromWorldPos(Vector3d worldPos, float partialTick) {
        return this.getRotateFromWorldPos(worldPos.x(), worldPos.y(), worldPos.z(), partialTick);
    }

    /**
     * 地表某点对应的"站姿"，<b>把世界三轴整体钉到宇宙三轴</b>（§31.16）：
     *
     * <pre>
     *   世界 +X（MC 东）→ 物理东 = 极轴 × 上方向
     *   世界 +Y（上）   → 上方向 = 该点的径向
     *   世界 +Z（MC 南）→ 物理南 = 东 × 上
     * </pre>
     *
     * <p><b>为什么不能再用"把 +Y 转到径向"的最小旋转</b>（原实现 = space 的写法）：
     * 最小旋转只保证"上"对，<b>完全不管世界 +X/+Z 落到哪里</b> —— 绕"上"轴那一个自由度
     * 是空着、由插值约定任意选定的。2026-09 实机就是这个症状：映射已与 MC 罗盘对上
     * （判据 `罗盘对齐=PASS`），但<b>画面里上午的太阳跑到了南边</b>——天空整体绕天顶滚了 90°。
     * 三对轴 (X,Y,Z) → (东,上,南) 都是右手系（已验：东×上 = 南）⇒ 是合法旋转、无反射，
     * 且方位自由度被钉死 ⇒ 天空与 MC 的东西南北一一对应。</p>
     *
     * <p>返回的是它的<b>逆</b>（space→world），与旧实现的返回方向一致，调用方（渲染）无需改动。</p>
     */
    public Quaterniond getRotateFromWorldPos(double x, double y, double z, float partialTick) {
        Vector3d up = this.getSpacePosFromWorldPos(x, y, z, partialTick)
                .sub(this.celestialBody.getSmoothPos(partialTick)).normalize();
        Vector3d pole = this.getSurfacePole(partialTick); // 不是写死的 +Y：见 getSurfacePole 的推导
        Vector3d east = new Vector3d(pole).cross(up);
        if (east.lengthSquared() < 1e-20) {
            return new Quaterniond(fromToQuaternion(pole, up)).invert(); // 站在极点上：东无定义，退回最小旋转
        }
        east.normalize();
        Vector3d south = new Vector3d(east).cross(up).normalize();
        // 列 = 世界三轴的像：(X,Y,Z) → (east, up, south)。
        // ⚠️⚠️ JOML 的 9 参构造是**列主序**（参数 = 列0行0、列0行1、列0行2、列1行0…），
        //   也就是**三根列向量依次平铺**。第一版按"行"写（east.x, up.x, south.x, east.y, …），
        //   得到的矩阵是目标的**转置**，而旋转矩阵的转置 = 逆 ⇒ 姿态帧整个反了；
        //   实测症状正是用户看到的"上午的太阳偏 90°"（赤道 lon=0 处"物理东"被映到世界 +Y 天顶）。
        //   这个写法由 native/jni-smoketest/FrameProbe.java 用**同一个 joml 版本**离线验过：
        //   4 个观察点 × 9 条判据全 PASS，其中包含"物理东→+X / 物理上→+Y / 物理南→+Z"。
        org.joml.Matrix3f m = new org.joml.Matrix3f(
                (float) east.x, (float) east.y, (float) east.z,
                (float) up.x, (float) up.y, (float) up.z,
                (float) south.x, (float) south.y, (float) south.z);
        return new Quaterniond().setFromNormalized(m).invert();
    }

    /** 构造"把 a 转到 b"的四元数（绕 a×b 旋转夹角）。 */
    private static Quaterniond fromToQuaternion(Vector3d a, Vector3d b) {
        Vector3d v1 = new Vector3d(a).normalize();
        Vector3d v2 = new Vector3d(b).normalize();
        return new Quaterniond().fromAxisAngleRad(v1.cross(v2, new Vector3d()).normalize(),
                Math.acos(v1.dot(v2)));
    }

    /**
     * 地表贴图参数：毯子中心（x,z）、整体旋转、经度方向的半长。
     *
     * <p>{@code longitude_length == 0} 视为未配 → 取默认 1024（避免除零）。</p>
     */
    public static class posShadowData {
        public final Vector2d center;
        public final double rotate;
        public final double longitude_length;

        public posShadowData(Vector2d center, double rotate, double longitude_length) {
            this.center = center;
            this.rotate = rotate;
            this.longitude_length = longitude_length == 0.0 ? 1024.0 : longitude_length;
        }

        public posShadowData(posShadowData posShadowData) {
            this.center = new Vector2d(posShadowData.center);
            this.rotate = posShadowData.rotate;
            this.longitude_length = posShadowData.longitude_length;
        }
    }
}
