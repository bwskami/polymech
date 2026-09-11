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

    // ===== 游戏宇宙（保向压缩）=====
    // 真实太阳系坐标 / ZOOM 后仍远超 MC 世界的物理范围：
    //   世界高度 -64..1984（共 2048 格），而火星 Y = -63.7 万格、木星 Y = +158 万格；
    //   世界边界 ±29,999,984 格，而木星 X-Z 半径 8109 万格、海王星 4.47 亿格。
    // 渲染矩阵不受世界限制（只关心相对方向），但玩家实体到不了这些位置。
    // 因此引入统一映射 gamePos()：内太阳系（≤火星轨道）保持真实，四颗巨行星
    // 沿各自"太阳方向"线性压缩进世界边界，非地球天体的 Y 压到黄道面（0）。
    // 渲染 / 阴影遮挡 / 传送 / HUD 必须全部使用本映射，保证刚体一致。

    /** 保持真实坐标的 X-Z 半径上限（= 火星轨道半径，格）。 */
    public static final double KEEP_RADIUS_MC;
    /** 压缩后允许的最大 X-Z 半径（格），小于世界边界并留出生成余量。 */
    public static final double MAX_RADIUS_MC = 28_500_000.0;
    /** 巨行星轨道的压缩系数。 */
    private static final double OUTER_K;

    static {
        RealAstroData m = RealAstroData.MARS;
        KEEP_RADIUS_MC = Math.sqrt(m.posX() * m.posX() + m.posZ() * m.posZ()) / ZOOM;
        // 压缩系数由最远的"日心"天体推导（当前为冥王星），数据驱动；
        // 卫星跟随母星（见 gamePos），不参与标定。
        double rMax = 0;
        for (RealAstroData b : RealAstroData.BODIES) {
            if (RealAstroData.parentOf(b) != null) continue;
            double r = Math.sqrt(b.posX() * b.posX() + b.posZ() * b.posZ()) / ZOOM;
            if (r > rMax) rMax = r;
        }
        OUTER_K = (MAX_RADIUS_MC - KEEP_RADIUS_MC) / (rMax - KEEP_RADIUS_MC);
    }

    /**
     * 天体的"游戏宇宙"坐标（米，天文坐标系）。
     * <p>
     * 地球完全保持真实（玩家基准点）；其余天体 X-Z 沿原方向压缩（≤火星轨道保持真实，
     * 巨行星线性压缩至 {@link #MAX_RADIUS_MC}），Y 压到黄道面（0，处于世界高度范围内）。
     * 所有消费方（渲染、阴影、传送、HUD、导航标记）统一使用本方法。
     * </p>
     */
    public static double[] gamePos(RealAstroData b) {
        if (b == RealAstroData.EARTH) {
            return new double[]{b.posX(), b.posY(), b.posZ()};
        }
        RealAstroData parent = RealAstroData.parentOf(b);
        if (parent != null) {
            // 卫星 = 母星游戏坐标 + 未压缩的真实 X-Z 偏移（轨道几何保持真实比例，
            // 不参与径向压缩，否则卫星轨道会被压进母星内部）。
            // Y 取母星高度（轨道面按黄道面处理，保证传送观测点可到达）。
            double[] pg = gamePos(parent);
            return new double[]{pg[0] + (b.posX() - parent.posX()), pg[1],
                    pg[2] + (b.posZ() - parent.posZ())};
        }
        double x = b.posX() / ZOOM;
        double z = b.posZ() / ZOOM;
        double r = Math.sqrt(x * x + z * z);
        double s = 0.0;
        if (r > 1e-9) {
            double r2 = r <= KEEP_RADIUS_MC ? r : KEEP_RADIUS_MC + (r - KEEP_RADIUS_MC) * OUTER_K;
            s = r2 / r;
        }
        return new double[]{x * s * ZOOM, 0.0, z * s * ZOOM};
    }

    /** {@link #gamePos} 的 MC 格版本。 */
    public static double[] gamePosMc(RealAstroData b) {
        double[] p = gamePos(b);
        return new double[]{toMc(p[0]), toMc(p[1]), toMc(p[2])};
    }

    public static double[] earthMcPos(double secondsSinceJ2000) {
        double[] real = RealAstroData.EARTH.realPositionAt(secondsSinceJ2000);
        return new double[]{toMc(real[0]), toMc(real[1]), toMc(real[2])};
    }
}
