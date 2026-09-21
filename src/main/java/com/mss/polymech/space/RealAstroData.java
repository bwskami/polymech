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
        return new RealAstroData(id, name, BodyType.PLANET, radiusMeters, carmen, atmo, massKg,
                parent.posX() + orbitMeters * Math.cos(phaseRad),
                parent.posY(),
                parent.posZ() + orbitMeters * Math.sin(phaseRad));
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
}
