package com.mss.polymech.space;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 太空维度 MC 坐标与真实太阳系坐标的缩放换算。
 * <p>
 * 玩家 MC 实体坐标保持在太空维度里，真实太阳系坐标按 ZOOM 缩放，
 * 因此地球轨道大约在 MC 坐标 1500 格左右，既能真实比例渲染，又不会让碰撞检测爆掉。
 * </p>
 */
public final class SpaceWorld {

    /** space mod 的 position_zoom：1 MC 格 = 10000 米。 */
    public static final double ZOOM = 10000.0;

    private SpaceWorld() {
    }

    public static ResourceKey<Level> dimension() {
        return PlanetDimensions.SPACE;
    }

    public static double j2000Seconds() {
        return (System.currentTimeMillis() - 946_728_000_000.0) / 1000.0;
    }

    public static double toMc(double real) {
        return real / ZOOM;
    }

    public static double toReal(double mc) {
        return mc * ZOOM;
    }

    public static double[] earthMcPos(double secondsSinceJ2000) {
        double[] real = RealAstroData.EARTH.realPositionAt(secondsSinceJ2000);
        return new double[]{toMc(real[0]), toMc(real[1]), toMc(real[2])};
    }
}
