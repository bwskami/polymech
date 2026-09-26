package com.mss.polymech.space;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实天体数据（数值照搬自 space mod 的 solar_system/object/*.json）。
 * 单位：米、秒、千克。
 *
 * <p><b>{@code massKg} 的来源说明（许可相关，别改成"照抄 space"）</b>：
 * space 的 {@code object/*.json} 里带 {@code mass}，但那是它的数据集本身，
 * 受 All Rights Reserved 保护，不能整体照搬。这里的质量一律取<b>公开天文常数</b>
 * （NASA fact sheet 量级），因此与 space 的取值有约 1% 以内的差异 ——
 * 这是<b>数据来源</b>的差异，不是力学机制的差异；由它算出的圆轨道初速度同样只差 1% 量级，
 * 不影响"行星绕恒星稳定运行"这一结构性行为。</p>
 *
 * <p>{@code massKg} 是 {@code space_data/*\/object/*.json} 的硬需求：没有质量，
 * 牛顿引力 {@code F = G·M·m/r²} 恒为 0，天体只会走直线，整个 cosmos 就没有轨道。</p>
 */
public record RealAstroData(
        String id,
        String name,
        BodyType bodyType,
        double radiusMeters,
        double carmenLineHeightMeters,
        double atmosphereHeightMeters,
        double massKg,
        double posX,
        double posY,
        double posZ) {

    public enum BodyType {
        STAR,
        PLANET
    }

    /**
     * 真实自转轴倾角（赤道面与轨道面交角，度；IAU 发布值）。
     *
     * <p><b>为什么不用 space 的 {@code rotate} 数值</b>（2026-09 实测，见 §31.12）：
     * 它那份数据第 4 个分量恒为 0、且土星/海王星/天王星三种读法都对不上现实倾角，
     * 土星与海王星那两条甚至不是单位四元数 ⇒ 照抄等于把错的季节搬进来。
     * 这里只用**发布的天文常数**自己构造（与质量、自转周期同一条规矩）。</p>
     *
     * <p><b>⚠️ 这张表必须声明在天体条目之前</b>：{@link #ofSatellite} 是在<b>静态字段初始化</b>时
     * 被调用的，它要读母星倾角来把卫星放进母星赤道面；若表声明在文件末尾，条目初始化时
     * 读到的是<b>尚未赋值</b>的字段（倾角全 0 或 NPE ⇒ 整类初始化失败，mod 起不来）。</p>
     *
     * <p>卫星按**潮汐锁定**处理 ⇒ 继承母星倾角（锁定卫星的自转轴 ≈ 母星极轴）。</p>
     */
    private static final Map<String, Double> AXIAL_TILT_DEG = Map.ofEntries(
            Map.entry("sun", 7.25),
            Map.entry("mercury", 0.034),
            Map.entry("venus", 177.36),
            Map.entry("earth", 23.439),
            Map.entry("moon", 6.68),
            Map.entry("mars", 25.19),
            Map.entry("jupiter", 3.13),
            Map.entry("saturn", 26.73),
            Map.entry("uranus", 97.77),
            Map.entry("neptune", 28.32),
            Map.entry("pluto", 122.53));

    public static final RealAstroData SUN = new RealAstroData("sun", "太阳", BodyType.STAR,
            6.96e8, 5.0e5, 0, 1.989e30, 0, 0, 0);

    public static final RealAstroData MERCURY = new RealAstroData("mercury", "水星", BodyType.PLANET,
            2_439_700, 2.7e4, 0, 3.301e23, -5.83e9, 3.09e9, -4.63e10);

    public static final RealAstroData VENUS = new RealAstroData("venus", "金星", BodyType.PLANET,
            6_051_802, 2.5e5, 25_000, 4.867e24, 4.39e9, 1.69e9, -1.08e11);

    public static final RealAstroData EARTH = new RealAstroData("earth", "地球", BodyType.PLANET,
            6.371e6, 1.0e5, 2.5e6, 5.972e24, 1.53e10, 10_876_018, -1.47e11);

    public static final RealAstroData MOON = new RealAstroData("moon", "月球", BodyType.PLANET,
            1_737_500, 2.0e4, 0, 7.342e22, 1.56e10, -23_615_210, -1.47e11);

    public static final RealAstroData MARS = new RealAstroData("mars", "火星", BodyType.PLANET,
            3_389_500, 8.0e4, 8_000, 6.417e23, -1.87e11, -6.37e9, 8.37e10);

    public static final RealAstroData JUPITER = new RealAstroData("jupiter", "木星", BodyType.PLANET,
            69_911_000, 2.7e5, 27_000, 1.898e27, 6.04e11, 1.58e10, 5.41e11);

    public static final RealAstroData SATURN = new RealAstroData("saturn", "土星", BodyType.PLANET,
            58_232_000, 4.0e5, 40_000, 5.683e26, -8.25e11, -5.17e10, -1.09e12);

    public static final RealAstroData URANUS = new RealAstroData("uranus", "天王星", BodyType.PLANET,
            25_362_000, 1.5e5, 15_000, 8.681e25, -6.79e11, 1.59e9, -2.8e12);

    public static final RealAstroData NEPTUNE = new RealAstroData("neptune", "海王星", BodyType.PLANET,
            24_622_000, 2.0e5, 20_000, 1.024e26, -4.4e12, -1.17e11, -7.63e11);

    // ── 矮行星与卫星（与 GUI 星图 SolarSystem.createDefault 的天体一一对应）──
    // 冥王星：真实 J2000 近似日心位置（黄经 253°、黄纬 15°、39.5 AU）。
    public static final RealAstroData PLUTO = new RealAstroData("pluto", "冥王星", BodyType.PLANET,
            1_188_300, 2.0e4, 1.0e5, 1.303e22, -1.668e12, 1.5287e12, -5.4559e12);

    // 卫星：母星真实坐标 + 真实轨道半径/相位（数据驱动，不手算绝对坐标）。
    public static final RealAstroData PHOBOS = ofSatellite("phobos", "火卫一", MARS,
            9.376e6, Math.toRadians(40), 11_267, 500, 0, 1.066e16);
    public static final RealAstroData DEIMOS = ofSatellite("deimos", "火卫二", MARS,
            2.3463e7, Math.toRadians(190), 6_200, 300, 0, 1.476e15);
    public static final RealAstroData IO = ofSatellite("io", "木卫一 Io", JUPITER,
            4.217e8, Math.toRadians(20), 1_821_600, 3.0e4, 0, 8.932e22);
    public static final RealAstroData EUROPA = ofSatellite("europa", "木卫二 Europa", JUPITER,
            6.711e8, Math.toRadians(75), 1_560_800, 2.0e4, 0, 4.800e22);
    public static final RealAstroData GANYMEDE = ofSatellite("ganymede", "木卫三 Ganymede", JUPITER,
            1.0704e9, Math.toRadians(140), 2_634_100, 3.0e4, 0, 1.482e23);
    public static final RealAstroData CALLISTO = ofSatellite("callisto", "木卫四 Callisto", JUPITER,
            1.8827e9, Math.toRadians(210), 2_410_300, 2.0e4, 0, 1.076e23);
    public static final RealAstroData TITAN = ofSatellite("titan", "土卫六 Titan", SATURN,
            1.22187e9, Math.toRadians(95), 2_574_700, 5.0e4, 6.0e5, 1.345e23);
    public static final RealAstroData ENCELADUS = ofSatellite("enceladus", "土卫二 Enceladus", SATURN,
            2.3795e8, Math.toRadians(260), 252_100, 1.0e4, 0, 1.080e20);
    public static final RealAstroData CHARON = ofSatellite("charon", "卡戎", PLUTO,
            1.9591e7, Math.toRadians(70), 606_000, 5.0e3, 0, 1.586e21);

    /**
     * 卫星工厂：位置 = 母星真实坐标 + 轨道半径×(cos 相位, 0, sin 相位)。
     * 轨道面按黄道面处理（Y 取母星 Y）；相位为确定性常量（数据表无 J2000 卫星相位）。
     */
    private static RealAstroData ofSatellite(String id, String name, RealAstroData parent,
                                             double orbitMeters, double phaseRad,
                                             double radiusMeters, double carmen, double atmo,
                                             double massKg) {
        // 轨道面 = **母星赤道面**（§31.13）。极轴 p = (0, cosε, sinε)（λ 暂取 90°），
        // 面内正交基 u = (0, −sinε, cosε)、v = (1, 0, 0) ⇒
        //   相对位置 = r·(sinφ, −sinε·cosφ, cosε·cosφ)，与 p **精确正交**（·p ≡ 0）。
        // 旧写法把轨道建在黄道面(XZ)上，而卫星的 rotate 已继承母星倾角 ⇒ 自转轴与轨道面不一致
        // （"天体连成一条线、那条带子会扭"的根因）。
        // ε 的取值：一般 = 母星赤道面倾角；**月球是例外** —— 它轨道接近黄道面（5.14°），
        // 不是地球赤道面（23.4°），所以取 0（近似贴黄道）。
        double eps = Math.toRadians("moon".equals(id) ? 0.0 : parent.axialTiltDeg());
        double sinE = Math.sin(eps);
        double cosE = Math.cos(eps);
        return new RealAstroData(id, name, BodyType.PLANET, radiusMeters, carmen, atmo, massKg,
                parent.posX() + orbitMeters * Math.sin(phaseRad),
                parent.posY() - orbitMeters * sinE * Math.cos(phaseRad),
                parent.posZ() + orbitMeters * cosE * Math.cos(phaseRad));
    }

    /**
     * 全部天体。顺序与 GUI 星图 SolarSystem.createDefault 完全一致，
     * 但跨系统引用一律按名字/ id 查找（byName/byId），不得依赖位置序号。
     *
     * <p>顺序另有一个硬约束：<b>母星必须排在自己的卫星之前</b> ——
     * 由它推轨道初速度时要先拿到母星的速度（见
     * {@code ModSpaceDataProvider#orbitalVelocity}）。</p>
     */
    public static final List<RealAstroData> BODIES = List.of(
            SUN, MERCURY, VENUS, EARTH, MOON, MARS, PHOBOS, DEIMOS,
            JUPITER, IO, EUROPA, GANYMEDE, CALLISTO,
            SATURN, TITAN, ENCELADUS, URANUS, NEPTUNE, PLUTO, CHARON);

    /** 卫星 → 母星（绕转对象）。日心天体不在此表中。 */
    private static final Map<String, String> PARENT_BY_ID = Map.ofEntries(
            Map.entry("moon", "earth"),
            Map.entry("phobos", "mars"), Map.entry("deimos", "mars"),
            Map.entry("io", "jupiter"), Map.entry("europa", "jupiter"),
            Map.entry("ganymede", "jupiter"), Map.entry("callisto", "jupiter"),
            Map.entry("titan", "saturn"), Map.entry("enceladus", "saturn"),
            Map.entry("charon", "pluto"));

    private static final Map<String, RealAstroData> BY_ID = new LinkedHashMap<>();
    private static final Map<String, RealAstroData> BY_NAME = new LinkedHashMap<>();

    /** 按天体 id（英文）查索引，未找到返回 -1。 */
    public static int indexOf(String id) {
        for (int i = 0; i < BODIES.size(); i++) {
            if (BODIES.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    /** 全部天体 id（用于指令补全）。 */
    public static List<String> bodyIds() {
        return BODIES.stream().map(RealAstroData::id).toList();
    }

    static {
        for (RealAstroData body : BODIES) {
            BY_ID.put(body.id(), body);
            BY_NAME.put(body.name(), body);
        }
    }

    /** 卫星的母星；日心天体返回 null。 */
    public static RealAstroData parentOf(RealAstroData body) {
        String pid = PARENT_BY_ID.get(body.id());
        return pid == null ? null : BY_ID.get(pid);
    }

    /** 按中文名查找（与 GUI 星图 Planet.name() 精确匹配）。未找到返回 null。 */
    public static RealAstroData byName(String name) {
        return BY_NAME.get(name);
    }

    public static RealAstroData byId(String id) {
        return BY_ID.get(id);
    }

    public double[] realPositionAt(double secondsSinceJ2000) {
        return new double[]{posX, posY, posZ};
    }

    /**
     * 自转周期（秒；<b>负值 = 逆行</b>）。
     *
     * <p><b>为什么需要它</b>：地表维度的"上方向"是<b>从行星中心指向该点的径向</b>
     * （{@code CelestialWorld#getRotateFromWorldPos}），所以行星一旦不自转，
     * 天空里太阳的高度角就永远是个<b>常数</b> —— 大白天也会看到太阳挂在地平线下
     * （2026-09 实机就是这样）。space 0.1.3 的做法正是给每个天体真实的
     * {@code rotate_speed}，由 kelvin 每步 {@code rotate.mul(rotateY(rotateSpeed·dt))} 积分
     * （见 {@code mps.kelvin.physical.space_world.SpaceWorld}）。</p>
     *
     * <p><b>数据来源</b>：公开天文常数（与质量同一条规矩：不整表照搬 space 的数据集）。
     * 用 {@code 2π/周期} 换出的 {@code rotate_speed} 与 space 0.1.3 的 {@code object/*.json}
     * 对照：地球 {@code 7.2921e-5} 与它<b>完全相同</b>，火星/木星/土星/天王星/海王星/水星/月球/金星
     * 相差都 &lt;1%（唯一差得多的是太阳：这里取 25.05 天、它取 26.0 天，而太阳自转本就按纬度 24.5–34 天变化）。</p>
     *
     * <p><b>与 space 的一处有意差异</b>：金星与天王星这里写成<b>负</b>（真实逆行，太阳从西边升起）；
     * space 存的是绝对值。要"与参考逐位一致"就把这两个负号去掉。</p>
     */
    private static final Map<String, Double> ROTATION_PERIOD_SECONDS = Map.ofEntries(
            Map.entry("sun", 2.164e6),
            Map.entry("mercury", 5.067e6),
            Map.entry("venus", -2.100e7),
            Map.entry("earth", 86_164.0),
            Map.entry("moon", 2.361e6),
            Map.entry("mars", 88_642.0),
            Map.entry("phobos", 27_554.0),
            Map.entry("deimos", 109_123.0),
            Map.entry("jupiter", 35_730.0),
            Map.entry("io", 152_853.0),
            Map.entry("europa", 306_822.0),
            Map.entry("ganymede", 618_153.0),
            Map.entry("callisto", 1_441_931.0),
            Map.entry("saturn", 38_362.0),
            Map.entry("titan", 1_377_648.0),
            Map.entry("enceladus", 118_387.0),
            Map.entry("uranus", -62_064.0),
            Map.entry("neptune", 57_996.0),
            Map.entry("pluto", 551_854.0),
            Map.entry("charon", 551_854.0));

    /** 自转周期（秒，负 = 逆行）；未登记返回 0（= 不自转）。 */
    public double rotationPeriodSeconds() {
        return ROTATION_PERIOD_SECONDS.getOrDefault(id, 0.0);
    }

    /** 自转角速度（rad/s，负 = 逆行），直接写进 {@code object/*.json} 的 {@code rotate_speed}。 */
    public double rotateSpeedRadPerSec() {
        double period = rotationPeriodSeconds();
        return period == 0.0 ? 0.0 : (2.0 * Math.PI) / period;
    }

    /**
     * 真实自转轴倾角（赤道面与轨道面交角，度；IAU 发布值）。
     *
     * <p><b>为什么不用 space 的 {@code rotate} 数值</b>（2026-09 实测，见 §31.12）：
     * 它那份数据第 4 个分量恒为 0、且土星/海王星/天王星三种读法都对不上现实倾角，
     * 土星与海王星那两条甚至不是单位四元数 ⇒ 照抄等于把错的季节搬进来。
     * 这里只用**发布的天文常数**自己构造（与质量、自转周期同一条规矩）。</p>
     */
    // 表本体（AXIAL_TILT_DEG）已按 §31.13 移到**天体条目之前** —— 静态初始化期必须能读到它。
    /** 自转轴倾角（度）；卫星继承母星（潮汐锁定）。 */
    public double axialTiltDeg() {
        Double own = AXIAL_TILT_DEG.get(id);
        if (own != null) {
            return own;
        }
        String parentId = PARENT_BY_ID.get(id);
        Double parent = parentId == null ? null : AXIAL_TILT_DEG.get(parentId);
        return parent == null ? 0.0 : parent;
    }

    /**
     * 自转轴四元数 <b>(x, y, z, w)</b> —— 直接写进 {@code object/*.json} 的 {@code rotate}。
     *
     * <p><b>约定必须按我们自己的映射来</b>（{@code CelestialWorld.getSpacePosFromWorldPos}）：
     * {@code space = R_body⁻¹ · v + bodyPos}，其中 {@code v} 以 +Y 为极轴构建
     * ⇒ <b>{@code R_body · Y} 必须等于该天体的真实北极方向</b>。</p>
     *
     * <p>构造：把 +Y 转到 {@code n̂ = (sinε·cosλ, cosε, sinε·sinλ)}，用最小旋转
     * （转轴 = Y×n̂、转角 = ∠(Y, n̂)）。λ = 极点黄道经度，暂统一取 90°（与地球同向）——
     * 它只决定**季节的相位**，不决定交角大小；要精修再逐体按 IAU 极点 (α₀, δ₀) 换算。</p>
     */
    public double[] rotateQuaternion() {
        double eps = Math.toRadians(axialTiltDeg());
        double lambda = Math.toRadians(90.0);
        double nx = Math.sin(eps) * Math.cos(lambda);
        double ny = Math.cos(eps);
        double nz = Math.sin(eps) * Math.sin(lambda);
        double ax = nz;
        double az = -nx;
        double axisLen = Math.sqrt(ax * ax + az * az);
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, ny)));
        if (axisLen < 1e-12) {
            // 无倾角：单位四元数，同样按 space 的落盘顺序 (w,x,y,z) 写
            return new double[]{1.0, 0.0, 0.0, 0.0};
        }
        double s = Math.sin(angle / 2.0);
        double qx = ax / axisLen * s;
        double qz = az / axisLen * s;
        double qw = Math.cos(angle / 2.0);
        // ★ 落盘顺序 = **space 0.1.3 的约定**：它的 object/*.json 是按 **(w,x,y,z)** 写的，
        //   而它的读取端（以及我们的读取端）都用
        //   `new Quaterniond(get(0), get(1), get(2), get(3))` 即 **(x,y,z,w)** 去读
        //   ⇒ 参考实现的**有效约定里含这个分量错位**（等价于一次 180° 翻转）。
        //   既然要"照抄约定"，我们就必须用同样的落盘顺序；否则"映射×自转×姿态帧"的复合手性
        //   与参考相反 —— 2026-09-22 实测到的"上午太阳出现在正西"正是这个手性差（高度角对、方位反）。
        //   证据：space 数据第 4 分量恒为 0；按 (w,x,y,z) 读时第 1 分量恰为 cos(倾角/2)
        //   （水星 1.0000→0°、地球 0.979→23.4°、火星 0.9759→25.2°、木星 0.9996→3.2°）。
        return new double[]{qw, qx, 0.0, qz};
    }
}
