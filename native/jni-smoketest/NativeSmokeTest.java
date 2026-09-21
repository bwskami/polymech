import com.mss.polymech.physics.NativePhysics;

/**
 * 原生库 JNI 冒烟测试（不依赖 Minecraft，供 CI 与本地手动验证使用）。
 *
 * <p>用法：{@code java -cp <classes> NativeSmokeTest <原生库绝对路径>}
 * 退出码 0 表示通过。</p>
 *
 * <p>覆盖范围：ABI 校验、老的体素落体、以及 ABI 5 的 Tier 1 全部新增函数
 * （地面 / 力矩 / 刚体属性 / 材质 / 运动学 / 复合盒 / 自检）。
 * JNI 是按函数名解析符号的，所以<b>每一个 native 方法被成功调用一次</b>
 * 就等于证明了"Java 声明 ↔ Rust 导出符号"两侧完全对齐。</p>
 */
public class NativeSmokeTest {

    private static int failures;

    private static void check(boolean condition, String message) {
        if (!condition) {
            System.err.println("FAIL: " + message);
            failures++;
            return;
        }
        System.out.println("ok: " + message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: NativeSmokeTest <原生库路径>");
            System.exit(2);
        }
        System.load(args[0]);

        int abi = NativePhysics.abiVersion();
        check(abi >= 5, "ABI 版本 = " + abi + "（Tier 1 需要 >= 5）");

        double restY = NativePhysics.selftest(300);
        check(Math.abs(restY - 0.5) < 0.05, String.format("原生落体静止高度 %.4f ≈ 0.5", restY));

        smokeVoxelPlatform();
        smokeFloor();
        smokeBoxes();
        smokeBodyControls();
        smokeMaterials();
        smokeMass();
        smokeRaycast();
        smokePlayerRig();
        smokeAbi6();
        smokeDetached();
        smokeCosmos();
        smokeSelfTest();

        if (failures > 0) {
            System.err.println("SMOKE TEST FAILED: " + failures + " 项不通过");
            System.exit(1);
        }
        System.out.println("SMOKE TEST PASSED");
    }

    /** 老能力回归：体素平台 + 立方体落体。 */
    private static void smokeVoxelPlatform() {
        long world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
        check(world > 0, "worldCreate -> " + world);
        try {
            NativePhysics.worldSetTimestep(world, 1.0 / 60.0);

            long[] cells = new long[6 * 2 * 6];
            int i = 0;
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 6; z++) {
                        cells[i++] = NativePhysics.packCell(x, y, z);
                    }
                }
            }
            long platform = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                    0, 0, 0, 0, 0, 0, 1, 0);
            long voxelCollider = NativePhysics.colliderAttachVoxels(world, platform,
                    1.0, 1.0, 1.0, cells, 0.8, 0.0);
            check(voxelCollider > 0, "体素碰撞体创建 -> " + voxelCollider);

            long cube = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    3.0, 7.0, 3.0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachCuboid(world, cube, 0.5, 0.5, 0.5, 0.6, 0.0);

            double[] pos = new double[3];
            for (int s = 0; s < 600; s++) {
                NativePhysics.worldStep(world);
            }
            NativePhysics.bodyReadTranslation(world, cube, pos);
            check(Math.abs(pos[1] - 2.5) < 0.08,
                    String.format("体素平台落点 y=%.4f ≈ 2.5（平台顶 2.0 + 半高 0.5）", pos[1]));

            int bodies = NativePhysics.worldBodyCount(world);
            check(bodies == 2, "刚体数 = " + bodies);
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /** A1：维度地面（半空间）—— 掉出世界的东西能被接住。 */
    private static void smokeFloor() {
        long world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
        try {
            NativePhysics.worldSetTimestep(world, 1.0 / 60.0);
            check(NativePhysics.worldSetFloor(world, -64.0, true), "worldSetFloor(enabled=true)");

            long cube = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    100.0, -55.0, 100.0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachCuboid(world, cube, 0.5, 0.5, 0.5, 0.6, 0.0);
            for (int s = 0; s < 600; s++) {
                NativePhysics.worldStep(world);
            }
            double[] pos = new double[3];
            NativePhysics.bodyReadTranslation(world, cube, pos);
            check(Math.abs(pos[1] - (-63.5)) < 0.1,
                    String.format("地面落点 y=%.4f ≈ -63.5（地面 -64 + 半高 0.5）", pos[1]));

            check(NativePhysics.worldSetFloor(world, 0.0, false), "worldSetFloor(enabled=false)");
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /** B1：复合盒碰撞体 —— 半格高的板必须真的只有半格高。 */
    private static void smokeBoxes() {
        long world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
        try {
            NativePhysics.worldSetTimestep(world, 1.0 / 60.0);
            // 板：局部 [0,4] x [0,0.5] x [0,4]
            double[] boxes = {0, 0, 0, 4, 0.5, 4};
            long platform = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                    0, 0, 0, 0, 0, 0, 1, 0);
            long boxCollider = NativePhysics.colliderAttachBoxes(world, platform, boxes,
                    0.7, 0.0, 1, -1);
            check(boxCollider > 0, "复合盒碰撞体创建 -> " + boxCollider);

            long cube = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    2.0, 5.0, 2.0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachCuboid(world, cube, 0.5, 0.5, 0.5, 0.6, 0.0);
            for (int s = 0; s < 600; s++) {
                NativePhysics.worldStep(world);
            }
            double[] pos = new double[3];
            NativePhysics.bodyReadTranslation(world, cube, pos);
            check(Math.abs(pos[1] - 1.0) < 0.08,
                    String.format("复合盒板落点 y=%.4f ≈ 1.0（板顶 0.5 + 半高 0.5）", pos[1]));
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /** A2/A3/A5：力矩、刚体属性、运动学目标位姿。 */
    private static void smokeBodyControls() {
        long world = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        try {
            NativePhysics.worldSetTimestep(world, 1.0 / 60.0);

            long spin = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    0, 0, 0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachCuboid(world, spin, 0.5, 0.5, 0.5, 0.6, 0.0);
            check(NativePhysics.bodyApplyTorqueImpulse(world, spin, 0, 8, 0), "bodyApplyTorqueImpulse");
            NativePhysics.worldStep(world);
            double[] angvel = new double[3];
            NativePhysics.bodyReadAngvel(world, spin, angvel);
            check(Math.abs(angvel[1]) > 0.1,
                    String.format("角冲量后 angvel.y=%.4f（期望 > 0.1）", angvel[1]));

            check(NativePhysics.bodyAddTorque(world, spin, 0, 1, 0), "bodyAddTorque");
            check(NativePhysics.bodyResetTorque(world, spin), "bodyResetTorque");
            check(NativePhysics.bodyAddForceAtPoint(world, spin, 1, 0, 0, 0, 1, 0),
                    "bodyAddForceAtPoint");
            check(NativePhysics.bodySetDamping(world, spin, 1.0, 1.0), "bodySetDamping");
            check(NativePhysics.bodySetGravityScale(world, spin, 0.0), "bodySetGravityScale");
            check(NativePhysics.bodyEnableCcd(world, spin, true), "bodyEnableCcd");
            check(NativePhysics.bodySetEnabledRotations(world, spin, true, false, true),
                    "bodySetEnabledRotations");
            check(NativePhysics.bodySetAdditionalMassProperties(world, spin,
                    0, 0, 0, 7.0, 2, 2, 2), "bodySetAdditionalMassProperties");

            long kinematic = NativePhysics.bodyCreate(world, NativePhysics.BODY_KINEMATIC_POSITION,
                    0, 20, 0, 0, 0, 0, 1, 0);
            check(NativePhysics.bodySetNextKinematicTranslation(world, kinematic, 3, 20, 0),
                    "bodySetNextKinematicTranslation");
            check(NativePhysics.bodySetNextKinematicRotation(world, kinematic, 0, 0.7071067811865476, 0, 0.7071067811865476),
                    "bodySetNextKinematicRotation");
            NativePhysics.worldStep(world);
            double[] pos = new double[3];
            NativePhysics.bodyReadTranslation(world, kinematic, pos);
            check(Math.abs(pos[0] - 3.0) < 0.01,
                    String.format("运动学体位移 x=%.4f ≈ 3.0", pos[0]));
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /** A4：材质与组合规则（按碰撞体 id 生效）。 */
    private static void smokeMaterials() {
        long world = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        try {
            long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                    0, 0, 0, 0, 0, 0, 1, 0);
            long collider = NativePhysics.colliderAttachCuboid(world, body, 0.5, 0.5, 0.5, 0.6, 0.0);
            check(NativePhysics.colliderSetMaterial(world, collider, 20.0, 0.5, 0.02,
                    NativePhysics.RULE_MIN, NativePhysics.RULE_MAX), "colliderSetMaterial");
            check(!NativePhysics.colliderSetMaterial(world, 999999L, 1.0, 0.0, 0.0, 0, 0),
                    "colliderSetMaterial 对不存在的碰撞体返回 false");
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /**
     * 质量标定。
     *
     * <p>体素碰撞体用的是 Rapier 默认密度 1.0，所以<b>一格方块 = 1 kg</b> —— 比 50 kg 的玩家
     * 还轻 50 倍，这是"玩家一碰物理体就把它撞飞"的根因。这里把这条事实钉成断言，
     * 顺便验证 {@code PhysicsBodyMass} 用的"碰撞体质量 + 附加质量 = 总质量"这条合成规则。</p>
     */
    private static void smokeMass() {
        long world = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        try {
            long one = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    0, 0, 0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachVoxels(world, one, 1, 1, 1,
                    new long[]{NativePhysics.packCell(0, 0, 0)}, 0.6, 0.0);
            NativePhysics.worldStep(world);
            check(Math.abs(NativePhysics.bodyGetMass(world, one) - 1.0) < 1.0e-6,
                    String.format("1 格体素质量 = %.3f kg（Rapier 默认密度 1.0）",
                            NativePhysics.bodyGetMass(world, one)));

            long floor = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    10, 0, 0, 0, 0, 0, 1, 0);
            long[] cells = new long[125];
            int i = 0;
            for (int x = 0; x < 5; x++) {
                for (int y = 0; y < 5; y++) {
                    for (int z = 0; z < 5; z++) {
                        cells[i++] = NativePhysics.packCell(x, y, z);
                    }
                }
            }
            long floorCollider = NativePhysics.colliderAttachVoxels(world, floor, 1, 1, 1, cells, 0.6, 0.0);
            // 密度（kg/m³）：体素碰撞体的总质量 = 体素数 × 密度。
            // 125 格 × 2500（石头）= 312500 kg —— 这才是"人推不动"的量级。
            check(NativePhysics.colliderSetDensity(world, floorCollider, 2500.0),
                    "colliderSetDensity(2500)");
            check(Math.abs(NativePhysics.colliderGetDensity(world, floorCollider) - 2500.0) < 1.0e-9,
                    "colliderGetDensity 读回一致");
            NativePhysics.worldStep(world);
            double stoneMass = NativePhysics.bodyGetMass(world, floor);
            check(Math.abs(stoneMass - 312500.0) < 1.0,
                    String.format("125 格石头质量 %.0f kg ≈ 312500（密度生效）", stoneMass));

            NativePhysics.bodySetAdditionalMassProperties(world, floor,
                    0, 0, 0, 600.0 - 125.0, 50.0, 50.0, 50.0);
            NativePhysics.worldStep(world);
            double m = NativePhysics.bodyGetMass(world, floor);
            check(Math.abs(m - 312975.0) < 1.0,
                    String.format("密度 + 附加质量 = %.0f kg（碰撞体 312500 + 附加 475）", m));

            check(NativePhysics.bodyGetMass(world, 999999L) < 0.0,
                    "不存在的刚体 mass 返回负值");
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /**
     * 射线查询（B3）：space 的玩家探地（{@code MixinEntity} 五点向下打 0.1m）就靠它。
     *
     * <p>这里专门验证最容易写错的一条：<b>查询组要能排除自己的碰撞箱</b>。
     * 玩家用 {@code (2,5)} 查询：地形 {@code (1,-1)} 打得中，自己的 {@code (2,5)} 打不中
     * （{@code 2 & 5 == 0}）—— 否则射线会先打到自己，探地永远返回"着地"。</p>
     */
    private static void smokeRaycast() {
        long world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
        try {
            // 地形平台：格子 (0..3, 0..1, 0..3) → 顶面 y = 2.0，组 (1,-1)
            long[] cells = new long[4 * 2 * 4];
            int i = 0;
            for (int x = 0; x < 4; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 4; z++) {
                        cells[i++] = NativePhysics.packCell(x, y, z);
                    }
                }
            }
            long platform = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                    0, 0, 0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachVoxelsGrouped(world, platform, 1, 1, 1, cells,
                    0.7, 0.0, 1, -1);

            // 玩家碰撞箱：中心 y = 3.5，半高 0.9 → 盒子 [2.6, 4.4]；组与查询同为 (2,5)
            long player = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                    1.0, 3.5, 1.0, 0, 0, 0, 1, 50.0);
            NativePhysics.colliderAttachCuboidGrouped(world, player, 0.3, 0.9, 0.3,
                    20.0, 0.0, 2, 5);
            NativePhysics.worldStep(world);

            double[] out = new double[5];
            // 从盒子内部（y=3.4）向下 1.0m：自己要被排除、地形(顶面2.0)又够不着 → 不该命中
            boolean near = NativePhysics.worldCastRay(world, 1.0, 3.4, 1.0,
                    0.0, -1.0, 0.0, 1.0, 2, 5, out);
            check(!near, "射线不把自己当障碍（1.0m 内无命中）");

            // 同一条射线拉长到 2.0m：应该打在平台上，toi ≈ 1.4
            boolean far = NativePhysics.worldCastRay(world, 1.0, 3.4, 1.0,
                    0.0, -1.0, 0.0, 2.0, 2, 5, out);
            check(far && Math.abs(out[0] - 1.4) < 0.05,
                    String.format("拉长后命中地形 toi=%.3f ≈ 1.4（平台顶 2.0）", out[0]));

            // 法线应朝上
            check(far && out[2] > 0.9, String.format("命中法线 ny=%.3f 朝上", out[2]));
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /**
     * cosmos（ABI 9）行为回归：一个引力源 + 一颗圆轨道卫星，绕一整圈后仍在轨道上。
     *
     * <p>钉三件事：① builder → insert → 读回 这条链通；② 积分器真的产生轨道运动；
     * ③ <b>半隐式欧拉的能量有界性</b> —— 换成显式欧拉，轨道会一圈圈外扩，绕几圈就飞掉。</p>
     *
     * <p>取地球量级：M=5.972e24 kg、R=6.8e6 m，圆轨道速度 v=√(GM/R)≈7656 m/s，
     * 周期 T=2πR/v≈5580 s；dt=60 × 93 步 = 5580 s，正好一圈。</p>
     */
    private static void smokeCosmos() {
        final double g = 6.6743e-11;
        final double mass = 5.972e24;
        final double r = 6.8e6;
        final double v = Math.sqrt(g * mass / r);
        final double period = 2.0 * Math.PI * r / v;

        long world = NativePhysics.cosmosWorldCreate(60.0, 4, 1, 1, 1, 1.0e6);
        check(world > 0, "cosmosWorldCreate -> " + world);
        try {
            long fixed = NativePhysics.cosmosFixedBodyBuilder(0.0, 0.0, 0.0);
            long source = NativePhysics.cosmosWorldInsertBodyAsGravitySource(world, fixed, mass);
            check(source > 0, "引力源已插入（handle=" + source + "）");

            long satBuilder = NativePhysics.cosmosSatelliteBuilder(1000.0, r, 0.0, 0.0, 0.0, 0.0, v, 1.0);
            long sat = NativePhysics.cosmosWorldInsertBodyAsGravitySource(world, satBuilder, 1000.0);
            check(sat > 0, "卫星已插入（handle=" + sat + "）");

            int steps = (int) Math.round(period / 60.0);
            int lastCount = 0;
            for (int i = 0; i < steps; i++) {
                lastCount = NativePhysics.cosmosWorldStep(world, 60.0);
            }
            check(lastCount == 2, "cosmosWorldStep 返回天体数 = " + lastCount);

            double[] p = new double[3];
            double[] vel = new double[3];
            // 约定照 kelvin 的调用方：**非 0 = 成功，0 = 失败**
            check(NativePhysics.cosmosBodyTranslationOut(world, sat, p) != 0, "cosmosBodyTranslationOut 非 0（成功）");
            check(NativePhysics.cosmosBodyLinvelOut(world, sat, vel) != 0, "cosmosBodyLinvelOut 非 0（成功）");
            check(NativePhysics.cosmosBodyTranslationOut(world, sat + 999, p) == 0,
                    "不存在的句柄返回 0（失败）—— 与 kelvin 的 `== 0 ? null` 一致");

            double radius = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            double speed = Math.sqrt(vel[0] * vel[0] + vel[1] * vel[1] + vel[2] * vel[2]);
            double fromStart = Math.sqrt((p[0] - r) * (p[0] - r) + p[1] * p[1] + p[2] * p[2]);
            check(Math.abs(radius - r) < 0.2 * r,
                    String.format("绕一圈后轨道半径仍在线（r=%.4g ≈ %.4g）", radius, r));
            check(fromStart < 0.25 * r,
                    String.format("绕一圈后回到出发点附近（偏差 %.4g / R=%.4g）", fromStart, r));
            check(Math.abs(speed - v) < 0.1 * v,
                    String.format("速度量级保持（|v|=%.5g ≈ %.5g）", speed, v));
        } finally {
            NativePhysics.cosmosWorldDestroy(world);
        }
    }

    /**
     * ABI 6/7 的行为回归：实时碰撞组、位姿写入、重力与集合尺寸读取。
     *
     * <p>重点是<b>实时改碰撞组真的影响求解器</b>这件事 —— 玩家主/兄弟刚体靠
     * {@code (2,5)/(5,5)} 互不作用、玩家靠 {@code (2,5)} 撞地形与物理体，全靠它。
     * 只验"函数返回 true"没有意义，必须看<b>下落结果</b>。</p>
     */
    private static void smokeAbi6() {
        // ① 实时分组：同一套几何，只改 filter，一个被地面接住、一个穿过去
        double[] restY = new double[2];
        int[] filters = {-1, 2}; // -1：(2,-1) 与地面 (1,-1) 互相作用；2：filter 不含地面 membership(1) → 不作用
        for (int k = 0; k < 2; k++) {
            long world = NativePhysics.worldCreate(0.0, -9.8, 0.0);
            try {
                NativePhysics.worldSetTimestep(world, 0.01);
                long ground = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                        0.0, -0.5, 0.0, 0, 0, 0, 1, 0.0);
                NativePhysics.colliderAttachCuboidGrouped(world, ground, 5.0, 0.5, 5.0, 0.5, 0.0, 1, -1);
                long cube = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                        0.0, 3.0, 0.0, 0, 0, 0, 1, 1.0);
                NativePhysics.colliderAttachCuboidGrouped(world, cube, 0.5, 0.5, 0.5, 0.5, 0.0, 2, filters[k]);
                for (int i = 0; i < 300; i++) {
                    NativePhysics.worldStep(world);
                }
                double[] p = new double[3];
                NativePhysics.bodyReadTranslation(world, cube, p);
                restY[k] = p[1];
                if (k == 0) {
                    check(Math.abs(p[1] - 0.5) < 0.3,
                            String.format("实时分组：作用时被地面接住 y=%.3f ≈ 0.5", p[1]));
                } else {
                    check(p[1] < -5.0,
                            String.format("实时分组：不作用时穿过地面 y=%.3f < -5", p[1]));
                }
            } finally {
                NativePhysics.worldDestroy(world);
            }
        }

        // ② bodySetPose：位姿一次性写入（位置 + 90° 绕 Y 的旋转）
        long w2 = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        try {
            long body = NativePhysics.bodyCreate(w2, NativePhysics.BODY_DYNAMIC,
                    0.0, 0.0, 0.0, 0, 0, 0, 1, 1.0);
            double s = Math.sqrt(0.5);
            check(NativePhysics.bodySetPose(w2, body, 7.0, 8.0, 9.0, 0.0, s, 0.0, s, true),
                    "bodySetPose");
            double[] p = new double[3];
            double[] q = new double[4];
            NativePhysics.bodyReadTranslation(w2, body, p);
            NativePhysics.bodyReadRotation(w2, body, q);
            check(Math.abs(p[0] - 7.0) < 1e-6 && Math.abs(p[1] - 8.0) < 1e-6 && Math.abs(p[2] - 9.0) < 1e-6,
                    String.format("bodySetPose 位置 (%.3f,%.3f,%.3f)", p[0], p[1], p[2]));
            check(Math.abs(Math.abs(q[1]) - s) < 1e-6 && Math.abs(Math.abs(q[3]) - s) < 1e-6,
                    String.format("bodySetPose 姿态 (%.3f,%.3f,%.3f,%.3f)", q[0], q[1], q[2], q[3]));

            // ③ worldGetGravity / 集合尺寸
            double[] g = new double[3];
            check(NativePhysics.worldGetGravity(w2, g) && Math.abs(g[1]) < 1e-9,
                    String.format("worldGetGravity (%.3f,%.3f,%.3f)", g[0], g[1], g[2]));
            check(NativePhysics.worldGetRigidBodySetSize(w2) == 1, "worldGetRigidBodySetSize = 1");
            NativePhysics.colliderAttachCuboid(w2, body, 0.5, 0.5, 0.5, 0.5, 0.0);
            check(NativePhysics.worldGetColliderSetSize(w2) == 1, "worldGetColliderSetSize = 1");
        } finally {
            NativePhysics.worldDestroy(w2);
        }
    }

    /**
     * 分离对象（ABI 8）：把一整条物理体从世界 A 搬进世界 B —— 跨维度搬运的核心
     * （{@code ServerPhysicalWorld.dimensionLeapPhysicalBody}）。
     *
     * <p>钉住的正是"不能用 Java 侧读位姿再重建冒充"的那些细节：位姿、线速度、角速度、
     * 碰撞体数量都必须原样过去。</p>
     */
    private static void smokeDetached() {
        long a = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        long b = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        try {
            NativePhysics.worldSetTimestep(a, 0.01);
            NativePhysics.worldSetTimestep(b, 0.01);
            long body = NativePhysics.bodyCreate(a, NativePhysics.BODY_DYNAMIC,
                    1.0, 2.0, 3.0, 0, 0, 0, 1, 25.0);
            long collider = NativePhysics.colliderAttachCuboid(a, body, 0.5, 0.5, 0.5, 0.5, 0.0);
            NativePhysics.bodySetMotion(a, body, 4.0, 0.0, 0.0, 0.0, 0.0, 1.5, true);
            check(body > 0 && collider > 0, "分离测试：A 世界建体 + 碰撞体");

            long colliderMem = NativePhysics.worldCopyCollider(a, collider);
            check(colliderMem > 0, "worldCopyCollider -> " + colliderMem);
            check(NativePhysics.worldRemoveCollider(a, collider, true), "从 A 摘掉碰撞体");

            long bodyMem = NativePhysics.worldCopyRigidBody(a, body);
            check(bodyMem > 0, "worldCopyRigidBody -> " + bodyMem);
            check(NativePhysics.worldRemoveRigidBody(a, body, true), "从 A 摘掉刚体");

            long moved = NativePhysics.worldInsertRigidBody(b, bodyMem);
            check(moved > 0, "worldInsertRigidBody -> " + moved);
            long movedCollider = NativePhysics.worldInsertColliderWithParent(b, colliderMem, moved);
            check(movedCollider > 0, "worldInsertColliderWithParent -> " + movedCollider);

            double[] p = new double[3];
            double[] v = new double[3];
            double[] w = new double[3];
            NativePhysics.bodyReadTranslation(b, moved, p);
            NativePhysics.bodyReadVelocity(b, moved, v);
            NativePhysics.bodyReadAngvel(b, moved, w);
            check(Math.abs(p[0] - 1.0) < 1e-6 && Math.abs(p[1] - 2.0) < 1e-6 && Math.abs(p[2] - 3.0) < 1e-6,
                    String.format("换世界后位姿保持 (%.3f,%.3f,%.3f)", p[0], p[1], p[2]));
            check(Math.abs(v[0] - 4.0) < 1e-6, String.format("换世界后线速度保持 vx=%.3f", v[0]));
            check(Math.abs(w[2] - 1.5) < 1e-6, String.format("换世界后角速度保持 wz=%.3f", w[2]));
            check(NativePhysics.worldBodyCount(a) == 0, "A 世界已清空（bodyCount=0）");
            check(NativePhysics.worldBodyCount(b) == 1, "B 世界已接收（bodyCount=1）");
            check(NativePhysics.worldGetColliderSetSize(b) == 1, "B 世界碰撞体数 = 1");
        } finally {
            NativePhysics.worldDestroy(a);
            NativePhysics.worldDestroy(b);
        }
    }

    /**
     * 玩家"双刚体 + 速度继承"（照 space 0.1.3 的 {@code PhysicalEntity}，见
     * {@code PlayerPhysicsBody}）。这里复刻那条继承链（含 space 的
     * {@code sibling.setLinvel(own)} 每子步清零），钉住两条关键性质：
     *
     * <ol>
     *   <li><b>站在静止船上的玩家不推船</b> —— 这正是"站在船上把船推着走"那个 bug 的反面；</li>
     *   <li><b>站着不动时速度不被污染</b> —— 兄弟刚体每子步都被搬回主刚体位置，
     *       搬动会产生穿透冲量；若不按 space 那样每子步清零，噪声会经
     *       {@code main = sibling + own} 加到玩家身上，表现就是"移动发飘/莫名加速"。</li>
     * </ol>
     *
     * <p>与之配套的取舍（实测、已知）：清零也让"载速"被限制在单个子步的摩擦预算
     * μ·g·dt 内（5 m/s 平台上玩家约 1.0–1.7 m/s，会被落下）。这是 space 的原样行为。</p>
     */
    private static void smokePlayerRig() {
        // ── ① 零重力：站在静止船上，输入 0，跑 2 秒 → 船不该动 ──
        long world = NativePhysics.worldCreate(0.0, 0.0, 0.0);
        try {
            NativePhysics.worldSetTimestep(world, 0.01);
            long[] cells = new long[6 * 2 * 6];
            int i = 0;
            for (int x = 0; x < 6; x++) {
                for (int y = 0; y < 2; y++) {
                    for (int z = 0; z < 6; z++) {
                        cells[i++] = NativePhysics.packCell(x, y, z);
                    }
                }
            }
            long ship = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC, 0, 0, 0, 0, 0, 0, 1, 0);
            NativePhysics.colliderAttachVoxels(world, ship, 1, 1, 1, cells, 0.6, 0.0);
            NativePhysics.bodySetAdditionalMassProperties(world, ship, 3, 1, 3, 528.0, 300, 300, 300);
            PlayerRig rig = new PlayerRig(world, 3.0, 2.9, 3.0);
            for (int s = 0; s < 200; s++) {
                rig.step(0.0, 0.0, 0.0);
            }
            double[] shipV = new double[3];
            NativePhysics.bodyReadVelocity(world, ship, shipV);
            check(Math.abs(shipV[0]) < 0.05,
                    String.format("玩家站在静止船上不推船（船速 %.4f m/s）", shipV[0]));

            // ② 站着不动 2 秒：主刚体速度与位移都不该被接触噪声污染
            double[] mainV = new double[3];
            NativePhysics.bodyReadVelocity(world, rig.main, mainV);
            double[] mainP = new double[3];
            NativePhysics.bodyReadTranslation(world, rig.main, mainP);
            check(Math.abs(mainV[0]) < 0.05 && Math.abs(mainV[1]) < 0.05 && Math.abs(mainV[2]) < 0.05,
                    String.format("站着不动不被污染（速度 %.4f/%.4f/%.4f）",
                            mainV[0], mainV[1], mainV[2]));
            check(Math.abs(mainP[1] - 2.9) < 0.15,
                    String.format("站着不动不下沉（y=%.3f ≈ 2.9）", mainP[1]));

            // ③ 在甲板上"走"2 秒：**不许被抬起来**。
            //    这条链会把 own 里的任何正向垂直偏置放大成恒定上升
            //    （实测：own.y = +1.6 m/s 时 y 从 2.90 一路涨到 8.84 —— 就是"走一下被蹬飞"）。
            //    这里 own = 纯水平 2 m/s，玩家应当水平走、垂直不离甲板。
            for (int s = 0; s < 200; s++) {
                rig.step(2.0, 0.0, 0.0);
            }
            double[] walkP = new double[3];
            double[] walkV = new double[3];
            NativePhysics.bodyReadTranslation(world, rig.main, walkP);
            NativePhysics.bodyReadVelocity(world, rig.main, walkV);
            check(Math.abs(walkP[1] - 2.9) < 0.3,
                    String.format("甲板上走不被抬起（y=%.3f ≈ 2.9）", walkP[1]));
            check(walkV[1] < 0.5,
                    String.format("甲板上走不产生上升速度（vy=%.3f）", walkV[1]));
            check(walkV[0] > 1.0,
                    String.format("甲板上走水平速度正常（vx=%.3f）", walkV[0]));
        } finally {
            NativePhysics.worldDestroy(world);
        }
    }

    /** space 的 PhysicalEntity：主 + 兄弟两个 25 kg 盒子，组 (2,5)/(5,5)，锁旋转 + 速度继承链。 */
    private static final class PlayerRig {
        private final long world;
        final long main;
        private final long sibling;
        private final double[] tmp = new double[3];

        PlayerRig(long world, double x, double y, double z) {
            this.world = world;
            main = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC, x, y, z, 0, 0, 0, 1, 25.0);
            sibling = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC, x, y, z, 0, 0, 0, 1, 25.0);
            NativePhysics.colliderAttachCuboidGrouped(world, main, 0.3, 0.9, 0.3, 20.0, 0.0, 2, 5);
            NativePhysics.colliderAttachCuboidGrouped(world, sibling, 0.3, 0.9, 0.3, 20.0, 0.0, 5, 5);
            NativePhysics.bodyLockRotations(world, main, true);
            NativePhysics.bodyLockRotations(world, sibling, true);
        }

        /** 一步 + space 的 afterStep 继承链（含兄弟速度清零）。 */
        void step(double ox, double oy, double oz) {
            NativePhysics.worldStep(world);
            if (!NativePhysics.bodyReadVelocity(world, sibling, tmp)) {
                return;
            }
            NativePhysics.bodySetMotion(world, main, tmp[0] + ox, tmp[1] + oy, tmp[2] + oz, 0, 0, 0, true);
            NativePhysics.bodySetMotion(world, sibling, ox, oy, oz, 0, 0, 0, true);
            if (NativePhysics.bodyReadTranslation(world, main, tmp)) {
                NativePhysics.bodySetTranslation(world, sibling, tmp[0], tmp[1], tmp[2]);
            }
        }
    }

    /** 原生 Tier 1 自检（与 /polymech physics tier1test 同一条路径）。 */
    private static void smokeSelfTest() {
        int bits = NativePhysics.tier1Selftest();
        check((bits & 0xFF) == 0xFF,
                String.format("Tier 1 自检位掩码 = 0x%02X（期望 0xFF）", bits));
    }
}
