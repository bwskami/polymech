import com.mss.polymech.physics.NativePhysics;

/**
 * 原生<b>固定约束</b>探针（牵引枪的"钉住"/LOCK，ABI 10）。
 *
 * <p>用法：{@code java -cp <classes> ConstraintProbe <原生库绝对路径>}
 * 退出码 0 表示全部通过。</p>
 *
 * <h2>为什么要专门验它</h2>
 * 参考的 LOCK 是 {@code pipeline.addConstraint(null, subLevel, FixedConstraintConfiguration(...))}
 * —— 关节一端写 {@code null} = 锚在世界。Rapier 0.34 <b>没有</b>这个隐式地面
 * （约束构建器直接索引刚体集合，传 invalid 句柄会 panic），所以原生侧改成
 * "显式建一个 FIXED 锚体"。**这是对引擎内部语义的依赖，必须由程序判定，不能靠读代码相信**：
 * 如果锚体与关节搭得不对，表现是"钉不住"（物体照旧掉），而那是**静默**的。
 *
 * <h2>判据设计</h2>
 * <ul>
 *   <li><b>A 组是对照组</b>：同一个世界、同一个体、同样 100 步，<b>不加</b>约束时必须自由落体。
 *       没有它，B 组的"没动"可能只是"物理世界根本没在跑"（死判据）。</li>
 *   <li>用 {@code worldGetRigidBodySetSize()}（Rapier 原始刚体集大小，<b>含</b>锚体）
 *       而不是 {@code worldBodyCount()}（只数 Java 可见的体）来验证锚体的存在与回收
 *       —— 锚体泄漏是"不报错、不可见"的那类错，只能靠这个计数抓。</li>
 *   <li>数值判据全部是<b>绝对量</b>（自由落体位移 4.9 格 / 钉住位移 &lt; 1e-6），与相机无关。</li>
 * </ul>
 */
public class ConstraintProbe {

    /** 重力 -9.8 m/s²；原生 dt = 1/100 s ⇒ 100 步 = 1 秒 ⇒ 自由落体位移 4.9 m。 */
    private static final int STEPS_1S = 100;
    private static final int STEPS_HALF_S = 50;
    private static final double G = 9.8;

    private static int passed;
    private static int failed;

    private static void check(boolean ok, String label, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  [PASS] %-46s %s%n", label, detail);
        } else {
            failed++;
            System.out.printf("  [FAIL] %-46s %s%n", label, detail);
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: ConstraintProbe <原生库路径>");
            System.exit(2);
        }
        System.load(args[0]);

        int abi = NativePhysics.abiVersion();
        check(abi >= 10, "ABI 版本 >= 10（固定约束）", "abi=" + abi);
        if (abi < 10) {
            System.out.println("原生库太旧，无法验证固定约束");
            System.exit(1);
        }

        groupControl();
        groupLock();
        groupUnlock();
        groupDuplicate();
        groupDestroyCleanup();
        groupDegenerate();

        System.out.printf("%n=== ConstraintProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** A 组：对照组 —— 不加约束时必须会掉。没有它，"钉住了"可能只是"物理没在跑"。 */
    private static void groupControl() {
        System.out.println("A 组（对照组：判据必须能 FAIL）");
        long world = NativePhysics.worldCreate(0.0, -G, 0.0);
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 10.0);
        check(world > 0 && body > 0, "A1 世界与刚体建出来了", "world=" + world + " body=" + body);

        step(world, STEPS_1S);
        double[] p = read(world, body);
        // 自由落体 1 秒的理论位移：0.5 * 9.8 * 1² = 4.9
        check(Math.abs((10.0 - p[1]) - 4.9) < 0.3,
                "A2 无约束 ⇒ 自由落体 1 秒 ≈ 4.9 格",
                String.format("实测 Δy=%.4f（理论 %.2f）", 10.0 - p[1], 0.5 * G));
        NativePhysics.worldDestroy(world);
    }

    /** B 组：加约束 ⇒ 一动不动；锚体确实建出来了（刚体集 +1）。 */
    private static void groupLock() {
        System.out.println("B 组（钉住）");
        long world = NativePhysics.worldCreate(0.0, -G, 0.0);
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 10.0);
        int sizeBefore = NativePhysics.worldGetRigidBodySetSize(world);
        int countBefore = NativePhysics.worldBodyCount(world);

        long constraint = NativePhysics.worldAddFixedConstraint(world, body);
        check(constraint > 0, "B1 加约束返回正 id", "constraint=" + constraint);

        int sizeAfter = NativePhysics.worldGetRigidBodySetSize(world);
        check(sizeAfter == sizeBefore + 1,
                "B2 刚体集 +1（锚体真的建出来了）",
                sizeBefore + " -> " + sizeAfter);
        check(NativePhysics.worldBodyCount(world) == countBefore,
                "B3 Java 可见体数不变（锚体不该被计数）",
                countBefore + " -> " + NativePhysics.worldBodyCount(world));

        step(world, STEPS_1S);
        double[] p = read(world, body);
        double moved = Math.abs(p[1] - 10.0) + Math.abs(p[0]) + Math.abs(p[2]);
        check(moved < 1.0e-6,
                "B4 钉住后 100 步一动不动（对比 A2 的 4.9）",
                String.format("总位移 %.3e 格", moved));

        NativePhysics.worldDestroy(world);
    }

    /** C 组：解锁 ⇒ 立刻恢复自由落体；锚体确实被删掉了。 */
    private static void groupUnlock() {
        System.out.println("C 组（解锁）");
        long world = NativePhysics.worldCreate(0.0, -G, 0.0);
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 10.0);
        int sizeBefore = NativePhysics.worldGetRigidBodySetSize(world);
        long constraint = NativePhysics.worldAddFixedConstraint(world, body);

        step(world, STEPS_HALF_S);
        double[] p1 = read(world, body);
        check(Math.abs(p1[1] - 10.0) < 1.0e-6, "C1 前 0.5 秒被钉住",
                String.format("y=%.6f", p1[1]));

        boolean removed = NativePhysics.worldRemoveConstraint(world, constraint);
        check(removed, "C2 解锁返回 true", "removed=" + removed);
        check(NativePhysics.worldGetRigidBodySetSize(world) == sizeBefore,
                "C3 刚体集回到原值（锚体确实删掉了）",
                sizeBefore + " -> " + NativePhysics.worldGetRigidBodySetSize(world));

        step(world, STEPS_HALF_S);
        double[] p2 = read(world, body);
        // 从静止开始自由落体 0.5 秒：0.5 * 9.8 * 0.25 = 1.225
        double drop = p1[1] - p2[1];
        check(Math.abs(drop - 1.225) < 0.15,
                "C4 解锁后 0.5 秒下落 ≈ 1.225 格",
                String.format("实测 Δy=%.4f（理论 1.225）", drop));
        NativePhysics.worldDestroy(world);
    }

    /** D 组：重复加被拒绝，且**不建第二个锚体**（锚体泄漏是静默的）。 */
    private static void groupDuplicate() {
        System.out.println("D 组（重复加）");
        long world = NativePhysics.worldCreate(0.0, -G, 0.0);
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 10.0);
        NativePhysics.worldAddFixedConstraint(world, body);
        int sizeAfterFirst = NativePhysics.worldGetRigidBodySetSize(world);

        long second = NativePhysics.worldAddFixedConstraint(world, body);
        check(second == -2L, "D1 重复加返回 -2（拒绝）", "返回 " + second);
        check(NativePhysics.worldGetRigidBodySetSize(world) == sizeAfterFirst,
                "D2 刚体集没变（没有建出第二个锚体）",
                sizeAfterFirst + " -> " + NativePhysics.worldGetRigidBodySetSize(world));
        NativePhysics.worldDestroy(world);
    }

    /** E 组：删掉被钉住的体 ⇒ 体与锚体一起回收（否则锚体永远留在刚体集里）。 */
    private static void groupDestroyCleanup() {
        System.out.println("E 组（删体时的锚体回收）");
        long world = NativePhysics.worldCreate(0.0, -G, 0.0);
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 10.0);
        int sizeBare = NativePhysics.worldGetRigidBodySetSize(world);
        NativePhysics.worldAddFixedConstraint(world, body);
        int sizeLocked = NativePhysics.worldGetRigidBodySetSize(world);
        check(sizeLocked == sizeBare + 1, "E1 钉住后刚体集 +1",
                sizeBare + " -> " + sizeLocked);

        NativePhysics.bodyDestroy(world, body);
        int sizeAfter = NativePhysics.worldGetRigidBodySetSize(world);
        // 体 -1、锚体 -1 ⇒ 回到裸世界的计数
        check(sizeAfter == sizeBare - 1,
                "E2 删体后刚体集 -2（体 + 锚体都回收了）",
                sizeLocked + " -> " + sizeAfter + "（裸世界 " + sizeBare + "）");
        check(NativePhysics.worldBodyCount(world) == 0,
                "E3 Java 可见体数为 0", "count=" + NativePhysics.worldBodyCount(world));
        NativePhysics.worldDestroy(world);
    }

    /** F 组：退化输入只返回失败，不许崩。 */
    private static void groupDegenerate() {
        System.out.println("F 组（退化输入）");
        long world = NativePhysics.worldCreate(0.0, -G, 0.0);
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                0.0, 10.0, 0.0, 0.0, 0.0, 0.0, 1.0, 10.0);

        check(NativePhysics.worldAddFixedConstraint(0L, body) < 0,
                "F1 世界句柄 0 ⇒ 负值", "ok");
        check(NativePhysics.worldAddFixedConstraint(world, 999_999L) < 0,
                "F2 不存在的体 ⇒ 负值", "ok");
        check(!NativePhysics.worldRemoveConstraint(world, 999_999L),
                "F3 不存在的约束 ⇒ false", "ok");
        check(!NativePhysics.worldRemoveConstraint(0L, 1L),
                "F4 世界句柄 0 ⇒ false", "ok");
        NativePhysics.worldDestroy(world);
    }

    // ==================== 小工具 ====================

    private static void step(long world, int steps) {
        for (int i = 0; i < steps; i++) {
            NativePhysics.worldStep(world);
        }
    }

    private static double[] read(long world, long body) {
        double[] out = new double[3];
        NativePhysics.bodyReadTranslation(world, body, out);
        return out;
    }
}
