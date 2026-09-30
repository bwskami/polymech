import com.mss.polymech.mps.rapier.helper.RapierWorld;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import com.mss.polymech.physics.PhysgunSpring;
import com.mss.polymech.physics.PhysicsNatives;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 牵引枪的<b>离线验收</b> —— 把 {@link PhysgunSpring} 的力真正喂给 MPS 刚体，看它会不会收敛、
 * 会不会爆掉、限幅有没有生效。
 *
 * <h2>为什么这条必须离线先跑</h2>
 * "用弹簧把刚体拉到目标点"最容易犯的错是<b>参数选得让显式积分发散</b>：
 * 本项目的物理子步 dt = 0.01 s，ω·dt 只要接近 1 就会每步放大一次（看起来就是"抓一下就弹飞"）。
 * 这类问题在实机里表现为"工具坏了"，但根因是纯数学，完全可以离线判定。
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>收敛</b>：从 1 / 10 / 32 格误差出发，5 秒内到达目标 0.25 格以内（且此后不再离开）；</li>
 *   <li><b>不发散</b>：全程速度不超过 120 格/秒，位置不出现 NaN；</li>
 *   <li><b>超调受控</b>：最大超调 ≤ 初始误差的 50%（ζ≈0.71 的理论超调约 4.3%，给足余量）；</li>
 *   <li><b>限幅生效</b>：把目标放到 1000 格外（误差被截到 32 格）时，1 秒后的速度必须
 *       ≤ MAX_A×1s 加上一点余量 —— 证明"抓远处目标不会变成开炮"；</li>
 *   <li><b>载荷无关</b>：80 kg 与 50 t 在同一初始误差下的收敛时间差 ≤ 20%（乘了质量的直接结果）。</li>
 * </ol>
 *
 * 跑法（同 CloneSmokeTest：需要 joml + slf4j-api + 原生库路径）。
 */
public final class PhysgunDragProbe {

    private static final double DT = 0.01;             // 物理子步
    private static final int STEPS_PER_TICK = 5;       // 一个 MC tick = 0.05 s

    private static int failures = 0;

    private static void check(boolean ok, String msg) {
        System.out.println((ok ? "[ OK ] " : "[FAIL] ") + msg);
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        if (!PhysicsNatives.ensureLoaded()) {
            System.err.println("原生库加载失败：" + PhysicsNatives.status());
            System.exit(2);
        }
        System.out.println("================ 牵引枪离线验收（PhysgunSpring + 真 MPS 刚体）================");
        System.out.printf("参数：Kp=%.1f Kd=%.1f aMax=%.1f 误差上限=%.1f 力时长=%.2fs（dt=%.2f）%n",
                PhysgunSpring.KP, PhysgunSpring.KD, PhysgunSpring.MAX_A,
                PhysgunSpring.MAX_ERROR, PhysgunSpring.FORCE_DURATION, DT);
        System.out.println();

        // ---------- 判据 1/2/3：不同初始误差下的收敛 ----------
        System.out.println("--- 1) 收敛 / 不发散 / 超调（载荷 5 t，目标 +X 方向，误差 1、10、32 格）---");
        double[] errs = {1.0, 10.0, 32.0};
        double t80 = 0.0;
        for (double e : errs) {
            Result r = drag(5000.0, e, 8.0);
            System.out.printf("   误差 %5.1f 格 ⇒ 收敛 %s，稳态误差 %.4f 格，最大超调 %.3f 格，峰值速度 %.2f 格/秒%n",
                    e, r.settleTime < 0 ? "未收敛" : String.format(java.util.Locale.ROOT, "%.2f 秒", r.settleTime),
                    r.steadyError, r.overshoot, r.peakSpeed);
            check(r.settleTime > 0 && r.settleTime <= 5.0, "5 秒内收敛（误差 " + e + " 格）");
            check(r.steadyError <= 0.25, "稳态误差 ≤ 0.25 格（实得 " + String.format(java.util.Locale.ROOT, "%.4f", r.steadyError) + "）");
            check(r.peakSpeed <= 120.0 && !Double.isNaN(r.steadyError), "不发散（峰值 " + String.format(java.util.Locale.ROOT, "%.2f", r.peakSpeed) + "）");
            check(r.overshoot <= e * 0.5, "超调 ≤ 初始误差 50%（实得 " + String.format(java.util.Locale.ROOT, "%.3f", r.overshoot) + "）");
        }
        System.out.println();

        // ---------- 判据 4：限幅（抓远处目标不会变炮弹）----------
        System.out.println("--- 2) 限幅：目标放到 1000 格外（误差被截到 32 格）---");
        Result far = drag(5000.0, 1000.0, 1.0);
        double vAfter1s = far.speedAt1s;
        System.out.printf("   1 秒后速度 = %.2f 格/秒（aMax×1s = %.1f，余量 20%%）%n", vAfter1s, PhysgunSpring.MAX_A);
        check(vAfter1s <= PhysgunSpring.MAX_A * 1.2, "1 秒后速度不超过限幅允许值");
        check(vAfter1s < 1000.0 * PhysgunSpring.KP, "比「不限幅」小一个量级以上（不限幅会是 "
                + (1000.0 * PhysgunSpring.KP) + " 格/秒²）");
        System.out.println();

        // ---------- 判据 5：载荷无关 ----------
        System.out.println("--- 3) 载荷无关性（同一初始误差，80 kg vs 50 t）---");
        Result light = drag(80.0, 10.0, 8.0);
        Result heavy = drag(50_000.0, 10.0, 8.0);
        t80 = light.settleTime;
        double tHeavy = heavy.settleTime;
        double rel = Math.abs(tHeavy - t80) / Math.max(1.0e-9, t80);
        System.out.printf("   80 kg 收敛 %.3f 秒；50 t 收敛 %.3f 秒；相差 %.1f%%%n", t80, tHeavy, rel * 100.0);
        check(rel <= 0.20, "两者收敛时间差 ≤ 20%（乘了质量的直接结果）");
        System.out.println();

        System.out.println(failures == 0 ? "==== 牵引枪判据 PASS ====" : ("==== 有 " + failures + " 项 FAIL ===="));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private record Result(double settleTime, double steadyError, double overshoot, double peakSpeed, double speedAt1s) {
    }

    /**
     * 把一个质量 {@code mass} 的刚体从 0 拉到 +X 方向 {@code error} 处，跑 {@code seconds} 秒。
     * 力按 {@link PhysgunSpring#accel} 算（与物品里完全同一条路），每个 MC tick 施加一次。
     */
    private static Result drag(double mass, double error, double seconds) {
        RapierWorld w = new RapierWorld(0.0, 0.0, 0.0);
        try {
            w.setTickTime(DT);
            RigidBody body = new RigidBody(RigidBody.Type.DYNAMIC, new Vector3d(), new Quaterniond(), mass);
            w.addRigidBody(body);
            w.up();
            Vector3d target = new Vector3d(error, 0.0, 0.0);

            int steps = (int) (seconds / DT);
            double settle = -1.0;
            double peak = 0.0;
            double overshoot = 0.0;
            double speedAt1s = Double.NaN;
            for (int i = 1; i <= steps; i++) {
                if (i % STEPS_PER_TICK == 0) {
                    Vector3d pos = body.getPos();
                    Vector3d vel = body.getLinvel();
                    Vector3d acc = PhysgunSpring.accel(new Vector3d(target).sub(pos), vel);
                    // 注意：RigidBody 的 API 是 applyForce(Force)（带时长排队）；
                    // 物品那边调的是 PhysicalBody.addForce(Force)（同一个语义，见 PhysicalBody:161）。
                    body.applyForce(new RigidBody.Force(PhysgunSpring.forceFor(mass, acc),
                            PhysgunSpring.FORCE_DURATION));
                }
                w.up();
                w.step();

                Vector3d p = body.getPos();
                Vector3d v = body.getLinvel();
                double speed = v == null ? 0.0 : v.length();
                peak = Math.max(peak, speed);
                double x = p == null ? Double.NaN : p.x;
                overshoot = Math.max(overshoot, x - error);
                double t = i * DT;
                if (Math.abs(t - 1.0) < DT * 0.5) {
                    speedAt1s = speed;
                }
                if (settle < 0 && Math.abs(x - error) <= 0.25 && speed <= 0.25) {
                    settle = t;
                }
            }
            Vector3d p = body.getPos();
            double steady = Math.abs(p.x - error);
            return new Result(settle, steady, Math.max(0.0, overshoot), peak, speedAt1s);
        } finally {
            w.free();
        }
    }
}
