package com.mss.polymech.space;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实天体数据（数值照搬自 space mod 的 solar_system/object/*.json）。
 * 单位：米、秒。
 */
public record RealAstroData(
        String id,
        String name,
        BodyType bodyType,
        double radiusMeters,
        double carmenLineHeightMeters,
        double atmosphereHeightMeters,
        double posX,
        double posY,
        double posZ) {

    public enum BodyType {
        STAR,
        PLANET
    }

    public static final RealAstroData SUN = new RealAstroData("sun", "太阳", BodyType.STAR,
            6.96e8, 5.0e5, 0, 0, 0, 0);

    public static final RealAstroData MERCURY = new RealAstroData("mercury", "水星", BodyType.PLANET,
            2_439_700, 2.7e4, 0, -5.83e9, 3.09e9, -4.63e10);

    public static final RealAstroData VENUS = new RealAstroData("venus", "金星", BodyType.PLANET,
            6_051_802, 2.5e5, 25_000, 4.39e9, 1.69e9, -1.08e11);

    public static final RealAstroData EARTH = new RealAstroData("earth", "地球", BodyType.PLANET,
            6.371e6, 1.0e5, 2.5e6, 1.53e10, 10_876_018, -1.47e11);

    public static final RealAstroData MOON = new RealAstroData("moon", "月球", BodyType.PLANET,
            1_737_500, 2.0e4, 0, 1.56e10, -23_615_210, -1.47e11);

    public static final RealAstroData MARS = new RealAstroData("mars", "火星", BodyType.PLANET,
            3_389_500, 8.0e4, 8_000, -1.87e11, -6.37e9, 8.37e10);

    public static final RealAstroData JUPITER = new RealAstroData("jupiter", "木星", BodyType.PLANET,
            69_911_000, 2.7e5, 27_000, 6.04e11, 1.58e10, 5.41e11);

    public static final RealAstroData SATURN = new RealAstroData("saturn", "土星", BodyType.PLANET,
            58_232_000, 4.0e5, 40_000, -8.25e11, -5.17e10, -1.09e12);

    public static final RealAstroData URANUS = new RealAstroData("uranus", "天王星", BodyType.PLANET,
            25_362_000, 1.5e5, 15_000, -6.79e11, 1.59e9, -2.8e12);

    public static final RealAstroData NEPTUNE = new RealAstroData("neptune", "海王星", BodyType.PLANET,
            24_622_000, 2.0e5, 20_000, -4.4e12, -1.17e11, -7.63e11);

    public static final List<RealAstroData> BODIES = List.of(
            SUN, MERCURY, VENUS, EARTH, MOON, MARS, JUPITER, SATURN, URANUS, NEPTUNE);

    private static final Map<String, RealAstroData> BY_ID = new LinkedHashMap<>();

    static {
        for (RealAstroData body : BODIES) {
            BY_ID.put(body.id(), body);
        }
    }

    public static RealAstroData byId(String id) {
        return BY_ID.get(id);
    }

    public double[] realPositionAt(double secondsSinceJ2000) {
        return new double[]{posX, posY, posZ};
    }
}
