import com.mss.polymech.util.FrameJitterMeter;
import com.mss.polymech.util.TimedSamples;

/**
 * <b>按拍同步 → 按帧渲染</b>的插值判据（牵引枪"线抽抽、框也抽抽"的离线证明）。
 *
 * <p>用法：{@code java -cp &lt;classes&gt; InterpSmoothnessProbe}（**必须先 javac 编译再用 -cp 跑**，
 * 原因见 {@code ConstraintProbe} 的注释：源码模式用的是另一套类加载器）。</p>
 *
 * <h2>它要证明什么</h2>
 * 老写法 {@code lerp(prev, cur, partialTick)} 隐含要求"prev ← cur 这次交换恰好发生在 tick 边界"。
 * 本探针把这句话变成可判定的东西：同一条匀速运动、同一个采样节奏，
 * <b>只改"交换落在哪个相位"</b>，看每帧位移是否还均匀。
 *
 * <h2>四组（A/B 是对照组，缺了它们 C 就是死判据）</h2>
 * <ul>
 *   <li><b>A</b> 固定相位（交换正好在 tick 边界）+ 旧 lerp ⇒ 顺滑。证明<b>量具本身能给出 PASS</b>，
 *       否则 C 组的 PASS 可能只是"这个统计怎么也通过"；</li>
 *   <li><b>B</b> ★ <b>任意相位</b> + 旧 lerp ⇒ 必抽（每 tick 跳一整个 Δ）。这是"判据必须能 FAIL"的对照组；</li>
 *   <li><b>C</b> 同样的任意相位 + {@link TimedSamples} ⇒ 顺滑；</li>
 *   <li><b>D</b> 丢一拍 + {@link TimedSamples} ⇒ <b>夹住不外推</b>（外推会在丢包时把物体甩出屏幕）。</li>
 * </ul>
 *
 * <h2>为什么可以直接调真实现</h2>
 * 两组判据都调<b>生产代码本体</b>（{@code TimedSamples} / {@code FrameJitterMeter}），
 * 不是在这里复刻一份公式 —— 本项目栽过"探针复刻的契约与真实现不一致"。
 * 时钟由 {@code TimedSamples} 的可注入构造器给定，所以相位是<b>确定性</b>的
 * （靠 {@code Thread.sleep} 造相位不行：Windows 粒度 ~15ms，而一个 tick 才 50ms）。
 */
public class InterpSmoothnessProbe {

    /** 仿真的帧率（Hz）：与实机 100~200fps 同量级。 */
    private static final double FRAME_HZ = 200.0;
    /** 仿真的采样节奏（Hz）：20 = 服务端每 tick 一发（改完后的 BROADCAST_INTERVAL_TICKS = 1）。 */
    private static final double SAMPLE_HZ = 20.0;
    /** 匀速运动速度（格/秒）：取 8 —— 拖着东西走路就是这个量级。 */
    private static final double SPEED = 8.0;
    /** 窗口帧数：与游戏内那条读数一致（120 帧）。 */
    private static final int WINDOW = 120;

    /** 仿真时间轴：探针直接写它，作为 TimedSamples 的时钟。 */
    private static double simTime;

    private static int passed;
    private static int failed;

    private static void check(boolean ok, String label, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  [PASS] %-44s %s%n", label, detail);
        } else {
            failed++;
            System.out.printf("  [FAIL] %-44s %s%n", label, detail);
        }
    }

    public static void main(String[] args) {
        // 采样周期（秒）。延迟按生产代码的同一条规则取 2 个周期
        // （PhysgunClientState 里就是这么从 BROADCAST_INTERVAL_TICKS 推出来的）。
        double samplePeriod = 1.0 / SAMPLE_HZ;
        double delay = 2.0 * samplePeriod;

        System.out.println("帧率 " + (int) FRAME_HZ + "Hz，采样 " + (int) SAMPLE_HZ
                + "Hz（周期 " + String.format(java.util.Locale.ROOT, "%.3f", samplePeriod)
                + "s），渲染延迟 " + String.format(java.util.Locale.ROOT, "%.3f", delay)
                + "s = 2 个采样周期");

        // A：固定相位（交换正好落在 tick 边界）
        runOldLerp("A 固定相位 + 旧 lerp（对照：应当顺滑）", 0);
        // B：任意相位（半个采样周期）—— 交换落在帧与帧之间
        runOldLerp("B 任意相位 + 旧 lerp（★ 对照：必须抽）", 5);
        // C：同样的任意相位，改走 TimedSamples
        runTimed("C 任意相位 + TimedSamples（应当顺滑）", 5, false);
        // D：丢一拍
        runDropout();

        System.out.printf("%n=== InterpSmoothnessProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    /** A/B 组：复刻旧写法（`prev ← cur` 在推样本时交换，渲染用 partialTick 在两者间 lerp）。 */
    private static void runOldLerp(String title, int phaseFrames) {
        System.out.println(title);
        FrameJitterMeter meter = new FrameJitterMeter("[探针]", WINDOW);
        int framesPerSample = (int) Math.round(FRAME_HZ / SAMPLE_HZ);
        double[] prev = posAt(0.0);
        double[] cur = posAt(0.0);
        int totalFrames = 400;
        for (int frame = 0; frame < totalFrames; frame++) {
            simTime = frame / FRAME_HZ;
            // 采样：每 framesPerSample 帧推一次，相位由 phaseFrames 决定
            if ((frame + phaseFrames) % framesPerSample == 0) {
                prev = cur;
                cur = posAt(simTime);
            }
            // 旧写法的渲染口径：partialTick = **当前 tick** 已过的比例。
            // ⚠️ 必须用整数帧运算，**不能**写 `(simTime % period) / period`：
            //   浮点取模在 tick 边界上会给出 0.0499999 而不是 0（frame/200 与 0.05 都不可精确表示），
            //   于是 partialTick≈1 下一帧又跳回 0.1，凭空造出一个倒退跳变 ——
            //   本探针第一版就是这么把自己判 FAIL 的（判据写错，不是代码错）。
            double partial = (frame % framesPerSample) / (double) framesPerSample;
            double x = prev[0] + (cur[0] - prev[0]) * partial;
            double y = prev[1] + (cur[1] - prev[1]) * partial;
            double z = prev[2] + (cur[2] - prev[2]) * partial;
            meter.sample(x, y, z);
        }
        FrameJitterMeter.Stats s = meter.stats();
        if (s == null) {
            check(false, "统计窗口没攒够", "filled < " + WINDOW);
            return;
        }
        System.out.println("        " + meter.verdict());
        if (phaseFrames == 0) {
            check(s.pass(), "A 固定相位 ⇒ 顺滑（量具能给出 PASS）",
                    String.format(java.util.Locale.ROOT, "抖动=%.2f 跳变帧=%d", s.jitter(), s.stalls()));
        } else {
            check(!s.pass(), "B 任意相位 + 旧写法 ⇒ 必抽（判据不是死的）",
                    String.format(java.util.Locale.ROOT, "抖动=%.2f 跳变帧=%d ⇒ %s",
                            s.jitter(), s.stalls(), s.pass() ? "竟然 PASS" : "如期 FAIL"));
        }
    }

    /** C 组：同样的任意相位，但走真实现 TimedSamples。 */
    private static void runTimed(String title, int phaseFrames, boolean dropOne) {
        System.out.println(title);
        FrameJitterMeter meter = new FrameJitterMeter("[探针]", WINDOW);
        double[] clock = {0.0};
        TimedSamples samples = new TimedSamples(3, 64, 2.0 * samplePeriod(), () -> clock[0]);
        double[] out = new double[3];
        int framesPerSample = (int) Math.round(FRAME_HZ / SAMPLE_HZ);
        int totalFrames = 400;
        boolean dropped = false;
        for (int frame = 0; frame < totalFrames; frame++) {
            clock[0] = frame / FRAME_HZ;
            simTime = clock[0];
            if ((frame + phaseFrames) % framesPerSample == 0) {
                if (dropOne && !dropped && frame > 150) {
                    dropped = true;             // 只丢这一拍
                } else {
                    double[] p = posAt(simTime);
                    samples.push(p[0], p[1], p[2]);
                }
            }
            if (samples.evaluate(out)) {
                meter.sample(out[0], out[1], out[2]);
            }
        }
        FrameJitterMeter.Stats s = meter.stats();
        if (s == null) {
            check(false, "统计窗口没攒够", "filled < " + WINDOW);
            return;
        }
        System.out.println("        " + meter.verdict());
        check(s.pass(), "C 任意相位 + TimedSamples ⇒ 顺滑",
                String.format(java.util.Locale.ROOT, "抖动=%.2f 跳变帧=%d", s.jitter(), s.stalls()));
    }

    /**
     * D 组：丢一拍 ⇒ 必须<b>夹住</b>，不得外推。
     *
     * <p>判据是"输出永远落在已推样本的取值范围内"：外推的典型表现就是越过最新样本继续往前跑
     * （丢包时物体一路飞出屏幕）。这里只推<b>单调递增</b>的样本，所以"范围"就是
     * [第一拍, 最新一拍]。</p>
     */
    private static void runDropout() {
        System.out.println("D 丢一拍 + TimedSamples（必须夹住，不得外推）");
        double[] clock = {0.0};
        TimedSamples samples = new TimedSamples(1, 64, 2.0 * samplePeriod(), () -> clock[0]);
        double[] out = new double[1];
        double maxPushed = 0.0;
        boolean dropped = false;
        boolean outside = false;
        double worstOvershoot = 0.0;
        for (int frame = 0; frame < 400; frame++) {
            clock[0] = frame / FRAME_HZ;
            double trueValue = SPEED * clock[0];
            if (frame % (int) Math.round(FRAME_HZ / SAMPLE_HZ) == 0) {
                // 从第 150 帧起连续丢 4 拍（模拟丢包/卡顿）
                if (frame >= 150 && frame < 190) {
                    dropped = true;
                } else {
                    samples.push(trueValue);
                    maxPushed = trueValue;
                }
            }
            if (samples.evaluate(out)) {
                if (out[0] > maxPushed + 1.0e-9) {
                    outside = true;
                    worstOvershoot = Math.max(worstOvershoot, out[0] - maxPushed);
                }
            }
        }
        check(dropped, "D0 确实造出了丢包窗口", dropped ? "第 150~190 帧不推样本" : "没丢成");
        check(!outside, "D1 丢包期间输出没有越过最新样本（夹住，不外推）",
                String.format(java.util.Locale.ROOT, "最大越界 %.6e 格（应为 0）", worstOvershoot));
        // 时间戳口径下，丢包只该造成"速率短暂变化"，不该造成位置跳变 ——
        // 这里再确认它没有把输出冻死（冻结说明延迟配置错了）
        double[] last = new double[1];
        clock[0] = 400 / FRAME_HZ;
        boolean got = samples.evaluate(last);
        check(got && last[0] > 0.0, "D2 丢包结束后仍在正常插值（没有被冻死）",
                got ? String.format(java.util.Locale.ROOT, "末值 %.4f", last[0]) : "evaluate 返回 false");
    }

    private static double samplePeriod() {
        return 1.0 / SAMPLE_HZ;
    }

    /** 仿真运动：x 轴匀速直线（与实机"拖着物体走"同形）。 */
    private static double[] posAt(double t) {
        return new double[]{SPEED * t, 0.0, 0.0};
    }
}
