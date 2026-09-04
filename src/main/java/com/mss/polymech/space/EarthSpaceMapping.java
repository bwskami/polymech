package com.mss.polymech.space;

/**
 * 地球行星维度 ↔ 真实太空坐标的球面映射。
 * <p>
 * 等价于 space mod 的 CelestialWorld#getSpacePosFromWorldPos / getWorldPosFromSpacePos，
 * 仅做地球专用的简化实现（无自转、中心在 0,0，经度长度 1024）。
 * </p>
 */
public final class EarthSpaceMapping {

    private static final double CENTER_X = 0.0;
    private static final double CENTER_Z = 0.0;
    private static final double LONGITUDE_LENGTH = 100000.0;
    private static final double HEIGHT = 10000.0;
    private static final double MIN_Y = -64.0;

    private EarthSpaceMapping() {
    }

    /** 主世界坐标 → 真实太空坐标。 */
    public static double[] worldToSpace(double x, double y, double z, double seconds) {
        x = -x;
        double dx = x - CENTER_X;
        double dz = z - CENTER_Z;
        double latitude = dx / LONGITUDE_LENGTH * (Math.PI / 2);
        double longitude = dz / LONGITUDE_LENGTH * (Math.PI / 2);
        double heightRatio = (y - MIN_Y) / (HEIGHT - MIN_Y);
        double radius = RealAstroData.EARTH.radiusMeters()
                + heightRatio * RealAstroData.EARTH.carmenLineHeightMeters();
        double cosLat = Math.cos(latitude);
        double localX = Math.cos(latitude) * Math.cos(longitude) * radius;
        double localY = Math.sin(latitude) * radius;
        double localZ = Math.cos(latitude) * Math.sin(longitude) * radius;
        double[] earth = RealAstroData.EARTH.realPositionAt(seconds);
        return new double[]{localX + earth[0], localY + earth[1], localZ + earth[2]};
    }

    /** 真实太空坐标 → 主世界坐标。 */
    public static double[] spaceToWorld(double sx, double sy, double sz, double seconds) {
        double[] earth = RealAstroData.EARTH.realPositionAt(seconds);
        double relX = sx - earth[0];
        double relY = sy - earth[1];
        double relZ = sz - earth[2];
        double r = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
        if (r < 1.0) {
            return new double[]{0.0, 100.0, 0.0};
        }
        double latitude = Math.asin(relY / r);
        double longitude = Math.atan2(relZ, relX);
        double x0 = latitude / (Math.PI / 2) * LONGITUDE_LENGTH;
        double z0 = longitude / (Math.PI / 2) * LONGITUDE_LENGTH;
        double worldX = x0 + CENTER_X;
        double worldZ = z0 + CENTER_Z;
        double worldY = MIN_Y
                + (r - RealAstroData.EARTH.radiusMeters())
                / RealAstroData.EARTH.carmenLineHeightMeters() * HEIGHT;
        return new double[]{worldX, worldY, worldZ};
    }
}
