package com.mss.polymech.space;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 太空维度 MC 坐标与真实太阳系坐标的换算（大坐标系路线）。
 * <p>
 * 参考 space mod 新版的做法：太空维度里拆除原版世界边界、
 * 深空位置不进入区块系统之后，行星按<b>真实缩放坐标</b>（{@link #ZOOM}，
 * 1 MC 格 = 10000 米）直接放在同一个 space 维度里，不再做径向压缩 ——
 * 海王星最远约 4.4×10<sup>8</sup> 格，远小于双精度坐标的实用极限
 * （2<sup>53</sup> ≈ 9×10<sup>15</sup>）。
 * </p>
 * <p>
 * 唯一保留的妥协：非地球天体的 Y 压平到黄道面（0）。世界高度只有 ±2×10<sup>3</sup> 格，
 * 而真实 Y 跨度达 ±1.6×10<sup>6</sup> 格；Y 只影响观测位姿，着陆走影子维度传送，
 * 不影响轨道几何。渲染 / 遮挡 / 传送 / HUD 必须统一使用本映射，保证刚体一致。
 * </p>
 */
public final class SpaceWorld {

    /** space mod 的 position_zoom：1 MC 格 = 10000 米。 */
    public static final double ZOOM = 10000.0;

    /**
     * 深空守卫：|x|/|z| 超过此值的位置不再进入区块系统
     * （原版 BlockPos.asLong 只有 26 位：±33,554,431）。
     * 守卫范围以内仍是真实（flat 空生成器）区块，供地球/火星近旁活动。
     */
    public static final int DEEP_SPACE_LIMIT = 33_554_431;

    /** 太空维度专用边界已移除：{@code space.MixinWorldBorder} 把边界整套放开。 */

    private SpaceWorld() {
    }

    public static ResourceKey<Level> dimension() {
        return PlanetDimensions.SPACE;
    }

    /** 该 Level 是否为太空维度（ResourceKey 是注册表内化的，引用比较即可）。 */
    public static boolean isSpace(Level level) {
        return level != null && level.dimension() == PlanetDimensions.SPACE;
    }

    /** 是否为"深空"：方块访问直接按真空处理，不进入区块系统。 */
    public static boolean isDeepSpace(double x, double z) {
        return Math.abs(x) > DEEP_SPACE_LIMIT || Math.abs(z) > DEEP_SPACE_LIMIT;
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

    /**
     * 天体的"游戏宇宙"坐标（米，天文坐标系）——大坐标系版本。
     * <p>
     * 地球完全保持真实（玩家基准点）；其余天体 X-Z 不再压缩（木星/土星/天王星/海王星
     * 位于 MC ±3×10<sup>7</sup>~4.4×10<sup>8</sup> 格，由太空维度的
     * 边界/深空守卫 mixin 保证可达），Y 压平到黄道面（0）。
     * 所有消费方（渲染、阴影、传送、HUD、导航标记）统一使用本方法。
     * </p>
     */
    public static double[] gamePos(RealAstroData b) {
        if (b == RealAstroData.EARTH) {
            return new double[]{b.posX(), b.posY(), b.posZ()};
        }
        RealAstroData parent = RealAstroData.parentOf(b);
        if (parent != null) {
            // 卫星 = 母星游戏坐标 + 真实 X-Z 偏移（轨道几何保持真实比例），Y 取母星高度（0）。
            double[] pg = gamePos(parent);
            return new double[]{pg[0] + (b.posX() - parent.posX()), pg[1],
                    pg[2] + (b.posZ() - parent.posZ())};
        }
        return new double[]{b.posX(), 0.0, b.posZ()};
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
