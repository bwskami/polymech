package com.mss.polymech.physics;

/**
 * 物理体的质量标定。
 *
 * <p><b>为什么需要这个类</b>：我们的体素碰撞体用的是 Rapier 默认密度 {@code 1.0}，
 * 也就是"<b>一格方块 = 1 kg</b>"。实测（{@code bodyGetMass}，见 {@code NativeSmokeTest}）：</p>
 *
 * <pre>
 *   1 格体素              → 1.0 kg
 *   1000 格体素           → 1000.0 kg
 *   玩家盒 0.6×1.8×0.6    → 0.648 kg（+ 附加 50 = 50.65 kg）
 * </pre>
 *
 * <p>于是"50 kg 的玩家"比"几格方块的船"重几十倍 —— <b>人一碰就把船撞飞</b>，
 * 再叠加推力包（原本是固定 24 N·s，对 1 kg 的体等于瞬加 24 m/s），根本没法测试。</p>
 *
 * <p>做法：给每个物理体一个<b>质量下限</b>。Rapier 的总质量 = 碰撞体推导质量 + 附加质量属性，
 * 所以"缺的那部分"用附加质量补上（附带按等效立方体算出的转动惯量 —— 惯量给 0 会被
 * {@code inv(0) = 0} 解释成无穷大惯量，等于把旋转锁死）。</p>
 *
 * <p>客户端与服务端必须用<b>同一套公式</b>：客户端镜像刚体是 DYNAMIC 的，质量不一致
 * 会让"本地被玩家推动的程度"与服务端不同，镜像就会漂、然后被位置修正拉回来（抖动）。</p>
 */
public final class PhysicsBodyMass {

    /**
     * 物理体质量下限（kg）。
     *
     * <p>取 600：比玩家（≈50.6 kg）重一个量级，人撞上去只给约 0.2–0.5 m/s 的推力（推得动、推不飞）；
     * 而 600 格以上的结构本来就比人重，不再补。</p>
     */
    public static final double MIN_BODY_MASS = 600.0;

    /** 体素碰撞体的密度（Rapier 默认值）→ 质量 kg 在数值上等于方块数。 */
    public static final double VOXEL_DENSITY = 1.0;

    private PhysicsBodyMass() {
    }

    /** 碰撞体本身能提供的质量（kg）。 */
    public static double colliderMass(int blocks) {
        return Math.max(0, blocks) * VOXEL_DENSITY;
    }

    /** 期望的总质量：至少 {@link #MIN_BODY_MASS}。 */
    public static double targetMass(int blocks) {
        return Math.max(MIN_BODY_MASS, colliderMass(blocks));
    }

    /**
     * 给刚体套上质量下限（幂等：可以在每次碰撞体重建后重复调用）。
     *
     * <p>原生层低于 ABI 5 时静默跳过（退化成"一格 1 kg"的旧行为，但不会崩）。</p>
     *
     * <p><b>质心必须传结构中心，不能给 (0,0,0)</b>：物理体刚体的原点取的是区域的<b>最小角</b>
     * （方块局部坐标从 0 开始），若把附加质量挂在原点，合成质心会被拽到角落 ——
     * 表现就是"整条船绕着一个角转"。这里传的是方块包围盒中心。</p>
     *
     * <p><b>为什么"够重了"也要写一次</b>：Rapier 没有"清除附加质量"的接口
     * （附加质量必须 &gt; 0）。一个体先小后被搭到 600 格以上时，若不覆盖，之前那笔附加质量
     * 会一直留着，总质量变成"碰撞体 + 旧附加" = 偏重。所以够重时改设一个 1e-6 的极小值
     * （惯量给 0，也就是不贡献额外惯量），等价于清零。</p>
     *
     * @param blocks   该物理体的方块数
     * @param centerX/Y/Z 结构中心（刚体局部坐标）
     */
    public static void apply(long world, long body, double colliderMass, int blocks,
                            double centerX, double centerY, double centerZ) {
        if (world <= 0 || body <= 0 || !PhysicsNatives.hasTier1()) {
            return;
        }
        double extra = MIN_BODY_MASS - colliderMass;
        double inertia;
        if (extra > 0.0) {
            // 等效立方体边长：体素体的惯量按 m·s²/6 近似（s = 体积立方根）
            double s = Math.cbrt(Math.max(1, blocks));
            inertia = extra * s * s / 6.0;
        } else {
            extra = CLEARED_EXTRA_MASS;
            inertia = 0.0;
        }
        NativePhysics.bodySetAdditionalMassProperties(world, body,
                centerX, centerY, centerZ, extra, inertia, inertia, inertia);
    }

    /** 用来把附加质量"清零"的极小值（kg）：Rapier 不接受 0 质量的附加质量属性。 */
    private static final double CLEARED_EXTRA_MASS = 1.0e-6;
}
