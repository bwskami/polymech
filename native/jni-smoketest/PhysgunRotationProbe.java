import com.mss.polymech.physics.PhysgunRotation;
import com.mss.polymech.physics.PhysgunSpring;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 牵引枪<b>角伺服</b>的离线判据（纯数学 + 积分仿真，零 Minecraft 依赖）。
 *
 * <h2>为什么必须离线判</h2>
 * 角伺服错了在实机上只表现为"转得怪"（转反、绕远路、抖、拧成陀螺），
 * 而这些在代码里都读不出来 —— 它们是<b>四元数约定</b>与<b>增益量纲</b>的产物。
 * 参考在 {@code PhysicsStaffServerHandler.physicsTick} 里也是每 tick 设一次马达，
 * 所以这里按同样的节奏积分仿真，验的是"这套增益到底收不收敛、超调多少、跟载荷有没有关系"。
 *
 * <h2>判据分组</h2>
 * <ul>
 *   <li>A：{@code errorAxisAngle} 的最短弧与双重覆盖（{@code q} 与 {@code -q} 必须同解）；</li>
 *   <li>B：{@code angularAccel} 的 PD 律与限幅（含"零误差零角速度 ⇒ 零"这种平凡但必要的检查）；</li>
 *   <li>C：★ 真收敛仿真 —— 2% 整定时间、超调、末态角速度；</li>
 *   <li>D：★ 惯量补偿 —— 10 倍惯量的体必须与参考体<b>同样快</b>（这是刻意偏离参考的地方）；</li>
 *   <li>E：{@code estimateInertia} 必须与建体公式同形（否则补偿的基准就错了）。</li>
 * </ul>
 *
 * <h2>★ 本探针抓到的两个真问题（记在这里，避免以后重复踩）</h2>
 * <ol>
 *   <li><b>固定力矩 PD 不行</b>：第一版按"力矩空间"实现（增益直接是 N·m/rad，不乘惯量），
 *       实测 10 倍惯量的体阻尼比从 0.71 掉到 0.22，3 秒后还差 37.75°。
 *       于是改成<b>惯量补偿</b>（D 组就是钉这个的）。</li>
 *   <li><b>判据期望写错过一次</b>：把"目标取 {@code -q90}"当成"与当前同朝向"，
 *       期望残角 0；但 {@code -q90} 与 {@code q90} 是<b>同一个旋转</b>，
 *       它离单位元本来就是 90°。真正该断言的是
 *       {@code error(单位元, -q90) == error(单位元, q90)} 以及 {@code error(q90, -q90) == 0}。</li>
 * </ol>
 */
public final class PhysgunRotationProbe {

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  [PASS] %-52s %s%n", name, detail); }
        else    { failed++; System.out.printf("  [FAIL] %-52s %s%n", name, detail); }
    }

    private static final double EPS = 1.0e-9;

    public static void main(String[] args) {
        System.out.println("=== PhysgunRotationProbe: 牵引枪角伺服（最短弧 / PD 律 / 收敛 / 惯量补偿）===");
        System.out.println();

        System.out.println("--- A 组：最短弧轴角（四元数约定）---");
        {
            double[] same = PhysgunRotation.errorAxisAngle(new Quaterniond(), new Quaterniond());
            check("同朝向 ⇒ angle=0", Math.abs(same[3]) < EPS, String.format("angle=%.3e", same[3]));

            Quaterniond q90 = new Quaterniond().rotateY(Math.PI / 2);
            double[] e90 = PhysgunRotation.errorAxisAngle(new Quaterniond(), q90);
            check("绕Y 90° ⇒ 轴=(0,1,0)、角=π/2",
                    Math.abs(e90[1] - 1.0) < 1e-9 && Math.abs(e90[3] - Math.PI / 2) < 1e-9,
                    String.format("轴=(%.3f,%.3f,%.3f) 角=%.6f", e90[0], e90[1], e90[2], e90[3]));

            // ★ 双重覆盖（修正后的判据）：-q 与 q 是同一个旋转
            //   1) 从单位元看，-q90 与 q90 必须给出同一个角（都是 π/2）
            Quaterniond neg90 = new Quaterniond(-q90.x, -q90.y, -q90.z, -q90.w);
            double[] eNeg = PhysgunRotation.errorAxisAngle(new Quaterniond(), neg90);
            check("★ 双重覆盖：error(1, -q90) == error(1, q90)",
                    Math.abs(eNeg[3] - e90[3]) < 1e-9,
                    String.format("%.9f vs %.9f", eNeg[3], e90[3]));
            //   2) 从 q90 看，-q90 是同一朝向 ⇒ 残角必须 0（不是 2π）
            double[] eSelf = PhysgunRotation.errorAxisAngle(new Quaterniond(q90), neg90);
            check("★ 双重覆盖：error(q90, -q90) = 0（不是 2π）",
                    eSelf[3] < 1e-6,
                    String.format("angle=%.3e（若为 %.4f 则走了长弧）", eSelf[3], 2 * Math.PI - eSelf[3]));

            Quaterniond q180 = new Quaterniond().rotateY(Math.PI);
            double[] e180 = PhysgunRotation.errorAxisAngle(new Quaterniond(), q180);
            check("绕Y 180° ⇒ 角=π（最短弧极端）", Math.abs(e180[3] - Math.PI) < 1e-6,
                    String.format("角=%.6f", e180[3]));

            boolean noThrow;
            double[] bad;
            try {
                bad = PhysgunRotation.errorAxisAngle(new Quaterniond(Double.NaN, 0, 0, 0), new Quaterniond());
                noThrow = true;
            } catch (RuntimeException ex) {
                bad = new double[]{1, 1, 1, 1};
                noThrow = false;
            }
            check("NaN 输入 ⇒ 零误差且不抛异常", noThrow && bad[3] == 0.0,
                    noThrow ? "安全退化" : "抛异常");

            Quaterniond un = new Quaterniond().rotateY(Math.PI / 2).mul(3.0);
            double[] eUn = PhysgunRotation.errorAxisAngle(new Quaterniond(), un);
            check("未归一化输入 ⇒ 与归一化同解", Math.abs(eUn[3] - Math.PI / 2) < 1e-9,
                    String.format("角=%.9f", eUn[3]));
        }

        System.out.println();
        System.out.println("--- B 组：角加速度 PD 律与限幅 ---");
        {
            Vector3d zero = PhysgunRotation.angularAccel(new double[]{0, 0, 0, 0}, new Vector3d());
            check("零误差 + 零角速度 ⇒ 零", zero.length() < EPS, String.format("|α|=%.3e", zero.length()));

            Vector3d p = PhysgunRotation.angularAccel(new double[]{0, 1, 0, 0.1}, new Vector3d());
            check("纯比例：α = KP·θ", Math.abs(p.y - PhysgunRotation.KP * 0.1) < 1e-9,
                    String.format("α_y=%.6f（期望 %.6f）", p.y, PhysgunRotation.KP * 0.1));

            Vector3d d = PhysgunRotation.angularAccel(new double[]{0, 0, 0, 0}, new Vector3d(0, 1, 0));
            check("纯阻尼：α = −KD·ω", Math.abs(d.y + PhysgunRotation.KD) < 1e-9,
                    String.format("α_y=%.6f（期望 %.6f）", d.y, -PhysgunRotation.KD));

            Vector3d big = PhysgunRotation.angularAccel(new double[]{0, 1, 0, Math.PI}, new Vector3d());
            check("★ 大误差被限幅到 MAX_ANG_ACCEL",
                    Math.abs(big.length() - PhysgunRotation.MAX_ANG_ACCEL) < 1e-9,
                    String.format("|α|=%.4f（上限 %.1f，未限幅应为 %.4f）",
                            big.length(), PhysgunRotation.MAX_ANG_ACCEL, PhysgunRotation.KP * Math.PI));

            check("方向与误差轴同向", p.y > 0, String.format("α_y=%.6f", p.y));

            // 力矩 = 惯量 × 角加速度
            Vector3d tau = PhysgunRotation.torqueFor(7000.0, new Vector3d(1, 0, 0));
            check("力矩 = 惯量 × 角加速度", Math.abs(tau.x - 7000.0) < 1e-9,
                    String.format("τ_x=%.1f", tau.x));
        }

        System.out.println();
        System.out.println("--- C 组：★ 真收敛仿真（每 MC tick 一个力矩冲量，与运行时同节奏）---");
        {
            double omega = Math.sqrt(PhysgunRotation.KP);
            double zeta = PhysgunRotation.KD / (2.0 * omega);
            check("ω=√KP ≈ 2.83 rad/s", Math.abs(omega - 2.828427) < 1e-5, String.format("ω=%.6f", omega));
            check("ζ≈0.71（略欠阻尼）", Math.abs(zeta - 0.7071) < 1e-3, String.format("ζ=%.4f", zeta));

            double start = Math.PI / 2;
            double[] r = simulate(7000.0, start, 4.0);
            // 标准 2% 整定判据：2 秒内残角 ≤ 起始角的 2%
            check("2 秒内整定到 2% 以内（标准整定判据）",
                    r[0] <= 0.02 * start,
                    String.format("残角=%.4f°（阈值 %.4f°）", Math.toDegrees(r[0]), Math.toDegrees(0.02 * start)));
            check("4 秒内收敛到 <0.1°", r[1] < Math.toRadians(0.1),
                    String.format("残角=%.5f°", Math.toDegrees(r[1])));
            check("末态角速度已衰减（|ω|<0.01 rad/s）", r[3] < 0.01, String.format("|ω|=%.6f", r[3]));
            check("超调 ≤ 起始角的 5%", r[2] <= 0.05 * start,
                    String.format("最大超调=%.4f°（阈值 %.4f°）", Math.toDegrees(r[2]), Math.toDegrees(0.05 * start)));

            double[] rn = simulate(7000.0, -Math.PI / 2, 4.0);
            check("−90° 起步同样收敛（方向对称）", rn[1] < Math.toRadians(0.1),
                    String.format("残角=%.5f°", Math.toDegrees(rn[1])));

            double[] rbig = simulate(7000.0, Math.PI * 0.99, 4.0);
            check("★ 接近 180° 起步不发散（4 秒内收敛）",
                    Double.isFinite(rbig[1]) && rbig[1] < Math.toRadians(0.5),
                    String.format("残角=%.5f°", Math.toDegrees(rbig[1])));
        }

        System.out.println();
        System.out.println("--- D 组：★ 惯量补偿（刻意偏离参考：参考的固定刚度马达会让大船转得慢）---");
        {
            double start = Math.PI / 2;
            double[] base = simulate(7000.0, start, 4.0);
            double[] heavy = simulate(70000.0, start, 4.0);
            double[] light = simulate(700.0, start, 4.0);

            check("10 倍惯量：与参考体同样快（补偿生效）",
                    Math.abs(heavy[1] - base[1]) < 1e-6,
                    String.format("重体残角=%.6f° vs 参考=%.6f°",
                            Math.toDegrees(heavy[1]), Math.toDegrees(base[1])));
            check("0.1 倍惯量：同样快（不许轻体抖）",
                    Math.abs(light[1] - base[1]) < 1e-6,
                    String.format("轻体残角=%.6f° vs 参考=%.6f°",
                            Math.toDegrees(light[1]), Math.toDegrees(base[1])));
            check("★ 载荷无关：三者 2% 整定时间一致",
                    heavy[0] <= 0.02 * start && light[0] <= 0.02 * start,
                    String.format("2s 残角：轻=%.4f° 参考=%.4f° 重=%.4f°",
                            Math.toDegrees(light[0]), Math.toDegrees(base[0]), Math.toDegrees(heavy[0])));
        }

        System.out.println();
        System.out.println("--- E 组：惯量估计必须与建体公式同形 ---");
        {
            // 与 PhysicsBodyMass.apply 里那句 inertia = extra * s * s / 6.0 同形
            for (int blocks : new int[]{1, 8, 64, 600, 4096}) {
                double s = Math.cbrt(blocks);
                double want = 600.0 * s * s / 6.0;
                double got = PhysgunRotation.estimateInertia(600.0, blocks);
                check(String.format("blocks=%-5d 惯量= m·∛b²/6", blocks),
                        Math.abs(got - want) < 1e-9,
                        String.format("I=%.3f", got));
            }
            check("blocks=0 不产生 0/NaN（退化保护）",
                    PhysgunRotation.estimateInertia(600.0, 0) > 0.0,
                    String.format("I=%.3f", PhysgunRotation.estimateInertia(600.0, 0)));
            check("质量与惯量成正比", Math.abs(
                            PhysgunRotation.estimateInertia(1200.0, 600)
                                    - 2.0 * PhysgunRotation.estimateInertia(600.0, 600)) < 1e-9,
                    "线性");
        }

        System.out.println();
        System.out.printf("=== PhysgunRotationProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * 积分仿真：绕 Y 轴从 {@code startAngle} 转到 0。
     *
     * <p>与运行时同节奏：每 {@link PhysgunSpring#FORCE_DURATION}（0.05 s）算一次角加速度、
     * 乘惯量得力/力矩、再作为冲量施加（{@code Δω = τ·dt / I}），中间用 0.01 s 子步推进姿态。</p>
     *
     * @return {@code {2 秒残角, 末残角, 最大超调, 末态|ω|}}（弧度 / rad·s⁻¹）
     */
    private static double[] simulate(double inertia, double startAngle, double seconds) {
        final double dt = 0.01;
        final int substepsPerTick = 5;
        final int twoSecondStep = (int) Math.round(2.0 / dt);
        int total = (int) Math.round(seconds / dt);
        Quaterniond q = new Quaterniond().rotateY(startAngle);
        Vector3d w = new Vector3d();
        double maxOvershoot = 0.0;
        double lastAbs = Math.abs(startAngle);
        double residualAt2s = Double.NaN;

        for (int i = 0; i < total; i++) {
            if (i % substepsPerTick == 0) {
                double[] axisAngle = PhysgunRotation.errorAxisAngle(new Quaterniond(q), new Quaterniond());
                Vector3d alpha = PhysgunRotation.angularAccel(axisAngle, w);
                Vector3d tau = PhysgunRotation.torqueFor(inertia, alpha);
                Vector3d j = PhysgunRotation.torqueImpulse(tau);
                w.add(j.div(inertia));
            }
            Quaterniond dq = new Quaterniond(w.x * dt * 0.5, w.y * dt * 0.5, w.z * dt * 0.5, 0.0).mul(q);
            q.add(dq).normalize();

            double abs = PhysgunRotation.errorAxisAngle(q, new Quaterniond())[3];
            if (abs > lastAbs) {
                maxOvershoot = Math.max(maxOvershoot, abs);
            }
            lastAbs = abs;
            if (i == twoSecondStep - 1) {
                residualAt2s = abs;
            }
        }
        double[] end = PhysgunRotation.errorAxisAngle(q, new Quaterniond());
        return new double[]{residualAt2s, end[3], maxOvershoot, w.length()};
    }
}
