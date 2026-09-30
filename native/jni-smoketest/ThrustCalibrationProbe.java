import com.mss.polymech.mps.rapier.helper.RapierWorld;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import com.mss.polymech.physics.PhysicsNatives;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 推进器<b>离线标定</b>探针 —— 回答"能飞、能停到底需要多大推力、多久"。
 *
 * <h2>为什么先离线跑</h2>
 * ①（1 格 = 10⁴ 米）落地后，"能飞/能停"变成一个**纯数值问题**：需要在<b>方块帧</b>里
 * 把刚体推到 0.35 ~ 216 格/秒。这些数完全可以用真实 MPS 克隆层在离线算出来，
 * 不必等实机（"能离线验的不要留到实机验"）。
 *
 * <h2>参考的推进器参数（decompiled-space/0.1.3）</h2>
 * {@code ChemicalThrusterBlockEntity:17,40} 与 {@code HallThrusterBlockEntity:22,66}：
 * 两者都是 {@code magnitude = 1000.0}，每 MC tick 追加一个"持续 0.05 s"的力
 * （世界 {@code tickTime = 0.01} ⇒ 稳态恰好一份，见 {@code Config.java:49-51}、{@code PhysicsStepThread:21-23}）。
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>引擎契约</b>：加速度必须等于 {@code F/m}（方块/秒²）—— 这是"MPS 的单位是 kg + 方块帧力"的离线证明；
 *       如果这里对不上，说明我们对 {@code bodyCreate(mass)} / {@code bodyAddForce} 的单位理解错了，
 *       那么天体引力桥那三处换算也要跟着重算。</li>
 *   <li><b>可玩性数字</b>：把每档推力达到四个里程碑的时间/距离打出来，供选型：
 *       0.35 格/秒（= 真实 3.5 km/s，地月转移量级）、3.5、128（≈5 分钟到月球）、216.5（**追上地球** = 能停）。</li>
 * </ol>
 *
 * 跑法（需要 slf4j-api 与 dll 路径，见 docs/mps-clone-plan.md 第 13 节）。
 */
public final class ThrustCalibrationProbe {

    private static final double DT = 0.01;            // 游戏内 tickTime（Config.java:49-51）
    private static final int STEPS_PER_MC_TICK = 5;   // 0.05 s / 0.01 s
    private static final double FORCE_DURATION = 0.05;

    /** 里程碑（格/秒，方块帧）。 */
    private static final double[] MILE = {0.35, 3.5, 128.0, 216.5};
    private static final String[] MILE_NAME = {
            "0.35(真实3.5km/s)", "3.5", "128(5分钟到月球)", "216.5(追上地球=能停)"};

    private static int failures = 0;

    public static void main(String[] args) {
        if (!PhysicsNatives.ensureLoaded()) {
            System.err.println("原生库加载失败：" + PhysicsNatives.status());
            System.exit(2);
        }
        System.out.println("================ 推进器离线标定（真实 MPS 克隆层）================");
        System.out.println("世界 tickTime = " + DT + " s；推进器每 " + STEPS_PER_MC_TICK + " 子步追加一次持续 "
                + FORCE_DURATION + " s 的力（= 每 MC tick，照 0.1.3 的写法）");
        System.out.println();

        double[] masses = {80.0, 500.0, 5_000.0, 50_000.0};      // 人+服 / 小艇 / 船 / 大船（kg）
        String[] massName = {"80(人+服)", "500(小艇)", "5000(船)", "50000(大船)"};
        double[] thrusts = {1_000.0, 10_000.0, 100_000.0};       // 方块帧力；1000 = 参考值

        // ---------- 判据 1：引擎契约 a == F/m ----------
        System.out.println("--- 判据 1：加速度 == F/m（方块/秒²）---");
        for (double m : new double[]{500.0, 5_000.0}) {
            for (double f : new double[]{1_000.0, 10_000.0}) {
                double measured = measureAccel(m, f);
                double expect = f / m;
                boolean ok = Math.abs(measured - expect) / expect < 0.02;
                if (!ok) {
                    failures++;
                }
                System.out.printf("   [%s] m=%.0f kg  F=%.0f ⇒ a 实测=%.6f 期望=%.6f 格/秒²%n",
                        ok ? "PASS" : "FAIL", m, f, measured, expect);
            }
        }
        System.out.println();

        // ---------- 判据 2：里程碑时间/距离表 ----------
        System.out.println("--- 判据 2：达到各里程碑所需时间 / 距离（空格 = 长期才到，仅看时间量级）---");
        System.out.printf("%-14s %-10s %10s %12s %14s%n", "载荷", "推力", "里程碑", "时间", "距离(格)");
        for (int i = 0; i < masses.length; i++) {
            for (double f : thrusts) {
                double[] t = new double[MILE.length];
                double[] x = new double[MILE.length];
                simulate(masses[i], f, MILE, t, x);
                for (int k = 0; k < MILE.length; k++) {
                    String ts = t[k] < 0 ? "未达到" : fmtTime(t[k]);
                    String xs = t[k] < 0 ? "-" : String.format(java.util.Locale.ROOT, "%.4e", x[k]);
                    System.out.printf("%-14s %-10.0f %10s %12s %14s%n",
                            i == 0 && k == 0 ? massName[i] : (k == 0 ? massName[i] : ""), k == 0 ? f : 0.0,
                            MILE_NAME[k], ts, xs);
                }
                System.out.println();
            }
        }

        // ---------- 结论（据实算，不写死）----------
        System.out.println("--- 结论 ---");
        double tStop1000_5000 = timeTo(5_000.0, 1_000.0, 216.5);
        double tStop1000_80 = timeTo(80.0, 1_000.0, 216.5);
        double tMoon5000 = timeTo(5_000.0, 1_000.0, 128.0);
        System.out.printf("   参考推力(1000) 推 5 t 到「追上地球」(216.5 格/秒) 需 %.1f 分钟；推 80 kg 只需 %.1f 秒%n",
                tStop1000_5000 / 60.0, tStop1000_80);
        System.out.printf("   参考推力(1000) 推 5 t 到 128 格/秒（≈5 分钟走完地月的速度）需 %.1f 分钟%n",
                tMoon5000 / 60.0);
        System.out.println("   ⇒ 选型依据：**推力按「帧」给，载荷质量决定一切** —— 同一推力下 80 kg 与 5 t 差 62.5 倍。");
        System.out.println("     若目标是「套装级机动（能追能停）」，需要 1000 量级推力作用在**百 kg 级**载荷上；");
        System.out.println("     若目标是「船级星际」，1000 要去推 t 级载荷 ⇒ 加速阶段以小时计，需另加档位或更大力。");

        System.out.println();
        if (failures == 0) {
            System.out.println("==== 判据 PASS（引擎契约成立；里程碑数字见上表）====");
        } else {
            System.out.println("==== 有 " + failures + " 项 FAIL ====");
            System.exit(1);
        }
    }

    /** 只测加速度：g=0、无碰撞体、恒定力推 10 s，取 v/t。 */
    private static double measureAccel(double mass, double force) {
        RapierWorld w = new RapierWorld(0.0, 0.0, 0.0);
        try {
            w.setTickTime(DT);
            RigidBody b = new RigidBody(RigidBody.Type.DYNAMIC, new Vector3d(), new Quaterniond(), mass);
            w.addRigidBody(b);
            w.up();
            int steps = (int) (10.0 / DT);
            for (int i = 1; i <= steps; i++) {
                if (i % STEPS_PER_MC_TICK == 0) {
                    b.applyForce(new RigidBody.Force(new Vector3d(force, 0.0, 0.0), FORCE_DURATION));
                }
                w.up();
                w.step();
            }
            Vector3d v = b.getLinvel();
            return v == null ? Double.NaN : v.x / (steps * DT);
        } finally {
            w.free();
        }
    }

    /** 推演到各里程碑，记录时间与距离（最多 2 小时游戏时间，够看出量级）。 */
    private static void simulate(double mass, double force, double[] targets,
                                 double[] outT, double[] outX) {
        java.util.Arrays.fill(outT, -1.0);
        RapierWorld w = new RapierWorld(0.0, 0.0, 0.0);
        try {
            w.setTickTime(DT);
            RigidBody b = new RigidBody(RigidBody.Type.DYNAMIC, new Vector3d(), new Quaterniond(), mass);
            w.addRigidBody(b);
            w.up();
            int maxSteps = (int) (7200.0 / DT);   // 2 小时
            int next = 0;
            for (int i = 1; i <= maxSteps && next < targets.length; i++) {
                if (i % STEPS_PER_MC_TICK == 0) {
                    b.applyForce(new RigidBody.Force(new Vector3d(force, 0.0, 0.0), FORCE_DURATION));
                }
                w.up();
                w.step();
                Vector3d v = b.getLinvel();
                Vector3d p = b.getPos();
                double speed = v == null ? 0.0 : v.x;
                while (next < targets.length && speed >= targets[next]) {
                    outT[next] = i * DT;
                    outX[next] = p == null ? 0.0 : p.x;
                    next++;
                }
            }
        } finally {
            w.free();
        }
    }

    private static double timeTo(double mass, double force, double target) {
        double[] t = new double[1];
        double[] x = new double[1];
        simulate(mass, force, new double[]{target}, t, x);
        return t[0] < 0 ? Double.NaN : t[0];
    }

    private static String fmtTime(double s) {
        if (s < 90) {
            return String.format(java.util.Locale.ROOT, "%.1f 秒", s);
        }
        if (s < 5400) {
            return String.format(java.util.Locale.ROOT, "%.1f 分", s / 60.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2f 时", s / 3600.0);
    }
}
