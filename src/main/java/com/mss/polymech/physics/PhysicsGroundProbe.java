package com.mss.polymech.physics;

/**
 * 玩家"站在地上吗"的探地查询（照抄 space 0.1.3 的 {@code MixinEntity}）。
 *
 * <p><b>为什么必须单独探地</b>：玩家位置由 Rapier 驱动后，原版的 {@code onGround}
 * 就没人维护了 —— 我们此前<b>一次都没设过</b>。于是"站在船上/地上"时原版认为你在空中：
 * 不能跳、{@code fallDistance} 一直累加（落地就摔死）、冲刺会被打断、贴地动作全乱。
 * 这正是"诡异到不知道怎么说的碰撞"的一大半。</p>
 *
 * <p>space 的做法（原样）：从<b>脚下中心 + 四个角</b>各向下打一条 0.1m 的射线，
 * 任意一条命中就算着地；命中时还要 {@code verticalCollisionBelow = true}、
 * {@code resetFallDistance()}。查询组用玩家自己的 {@code (2, 5)}，
 * 这样能打到地形 {@code (1,-1)} 与物理体 {@code (4,-1)}，但<b>打不到玩家自己的一对盒子</b>
 * （自己的 mem 是 2 和 5，与 filter 5 相与为 0）—— 不需要额外的"忽略自己"逻辑。</p>
 */
public final class PhysicsGroundProbe {

    /** 探地距离（格）：space 用的是 0.1。 */
    public static final double REACH = 0.1;
    /** 查询组：与玩家主碰撞体一致。 */
    public static final int MEMBERSHIP = 2;
    public static final int FILTER = 5;

    /**
     * 采样点（相对脚下中心的水平偏移，{-1,0,1} 是<b>半宽的比例</b>，由 {@link #grounded}
     * 乘 {@code halfWidth}）：<b>中心 + 四个角</b>，与 space 同序 ——
     * space 是 {@code offsetsX = {0,-h,+h,-h,+h}}、{@code offsetsZ = {0,-h,+h,+h,-h}}，
     * 即 (0,0)、(-h,-h)、(+h,+h)、(-h,+h)、(+h,-h)。
     *
     * <p><b>此处曾抄错</b>：原来写成 {@code (0,0),(-1,0),(+1,0),(+1,0),(-1,0)} ——
     * 5 个点里 4 个都落在 X 轴上、四个角全丢，等于只探 3 个共线点。
     * 后果：站在方块/船的<b>边缘</b>时中心落在空中、本该命中的四角又没探，
     * 于是判成"不贴地"→ 边缘跳不起来、{@code fallDistance} 异常累积。</p>
     */
    private static final double[][] OFFSETS = {
            {0.0, 0.0}, {-1.0, -1.0}, {1.0, 1.0}, {-1.0, 1.0}, {1.0, -1.0}
    };

    private PhysicsGroundProbe() {
    }

    /**
     * 脚下是否有碰撞体。
     *
     * @param halfWidth 碰撞箱半宽（四角采样用）
     * @return true = 着地
     */
    public static boolean grounded(long world, double x, double y, double z, double halfWidth) {
        if (world <= 0 || !PhysicsNatives.hasTier1()) {
            return false; // 原生层没有 castRay（ABI &lt; 5）：退回"不设 onGround"，与旧行为一致
        }
        double[] out = new double[5];
        for (double[] offset : OFFSETS) {
            double scale = offset[0] == 0.0 ? 0.0 : halfWidth;
            if (NativePhysics.worldCastRay(world,
                    x + offset[0] * scale, y, z + offset[1] * scale,
                    0.0, -1.0, 0.0, REACH, MEMBERSHIP, FILTER, out)) {
                return true;
            }
        }
        return false;
    }
}
