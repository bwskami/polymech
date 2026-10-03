import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.util.FrameJitterMeter;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * {@code CelestialBody} 渲染插值的<b>行为特征化</b>判据（重构前的安全带）。
 *
 * <p>用法：{@code java @&lt;argfile&gt;}（需要**全量 classpath**：本类要加载 MC 的
 * {@code CompoundTag}/{@code FriendlyByteBuf} 等，探针跑法见 {@code run-offline-checks.ps1} 第 4 步）。</p>
 *
 * <h2>为什么需要它</h2>
 * 2026-10-02 把"带时间戳的快照环 + 渲染延迟"抽成了通用件 {@code TimedSamples}，
 * 而 {@code CelestialBody} 里还留着同一套机制的<b>私有实现</b>（本项目硬规矩：同一概念不许两份）。
 * 收口之前必须先有一条**能判断"重构没改变行为"**的探针 —— 否则改完只能靠人去游戏里看天空
 * 有没有变卡，那正是用户明确说过不想反复做的事。
 *
 * <p>它<b>不</b>复刻公式：直接 new 出真的 {@code CelestialBody}、按仿真节奏喂
 * {@code moveToDirect}/{@code rotateToDirect}、再逐帧读 {@code getSmoothPos/getSmoothRotate}。
 * 时钟是类内部的 {@code System.nanoTime()}（**没有**可注入时钟），所以这里用<b>忙等</b>按
 * 200fps 推进真实时间 —— 不能用 {@code Thread.sleep}：Windows 上 sleep 粒度约 15ms，
 * 而一帧才 5ms，节奏会糊掉。</p>
 *
 * <h2>钉住四条行为</h2>
 * <ol>
 *   <li><b>平滑</b>：每帧位移抖动（最大/均）≤ 1.5、跳变帧 = 0（用生产代码的 {@link FrameJitterMeter} 判）；</li>
 *   <li><b>不外推</b>：输出永不越过已推样本的最大值（漏拍时只会"夹住"，不会往前冲）；</li>
 *   <li><b>姿态是单位四元数</b>（插值后漂了就说明归一化坏了）；</li>
 *   <li><b>姿态每帧步长有界</b>（取最短弧失败时会绕一大圈 ⇒ 步长爆掉，这条能抓住）。</li>
 * </ol>
 */
public class CelestialSmoothProbe {

    /** 仿真渲染帧率（Hz）。 */
    private static final double FRAME_HZ = 200.0;
    /** 仿真采样节奏（Hz）：与物理/网络同量级。 */
    private static final double SAMPLE_HZ = 20.0;
    /** 采样相位（帧）：故意落在一帧中间 —— 时间戳插值正是为"任意相位"准备的。 */
    private static final int PHASE_FRAMES = 5;
    /** 匀速速度（格/秒）。 */
    private static final double SPEED = 30.0;
    /** 自转速度（rad/s）。 */
    private static final double SPIN = 1.0;
    /** 预热帧数：让快照环攒够（延迟 0.10s = 20 帧，多给一些）。 */
    private static final int WARMUP = 40;
    /** 统计帧数（窗口 120 帧，多跑一段让读数稳定）。 */
    private static final int MEASURE = 260;
    /** 统计窗口帧数。 */
    private static final int WINDOW = 120;

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
        CelestialBody body = new CelestialBody("overworld", "probe", new Vector3d(0.0, 0.0, 0.0),
                new Quaterniond(), 1.0);

        FrameJitterMeter meter = new FrameJitterMeter("[CelestialBody]", WINDOW);
        int framesPerSample = (int) Math.round(FRAME_HZ / SAMPLE_HZ);
        double maxPushedX = 0.0;
        double maxOutputX = -Double.MAX_VALUE;
        double worstUnitError = 0.0;
        double worstStepRad = 0.0;
        Quaterniond prevQ = null;
        int total = WARMUP + MEASURE + 40;

        long t0 = System.nanoTime();
        for (int frame = 0; frame < total; frame++) {
            // 忙等推进到本帧的仿真时刻（不能用 sleep，见类注释）
            long deadline = t0 + (long) (frame * 1.0e9 / FRAME_HZ);
            while (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            double t = frame / FRAME_HZ;
            if ((frame + PHASE_FRAMES) % framesPerSample == 0) {
                Vector3d p = new Vector3d(SPEED * t, 0.0, 0.0);
                body.moveToDirect(p);
                body.rotateToDirect(new Quaterniond().rotateY(SPIN * t));
                maxPushedX = Math.max(maxPushedX, p.x);
            }
            Vector3d out = body.getSmoothPos(0.5f);
            Quaterniond q = body.getSmoothRotate(0.5f);
            if (frame >= WARMUP) {
                meter.sample(out.x, out.y, out.z);
                maxOutputX = Math.max(maxOutputX, out.x);
                worstUnitError = Math.max(worstUnitError,
                        Math.abs(Math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w) - 1.0));
                if (prevQ != null) {
                    double dot = Math.abs(prevQ.x * q.x + prevQ.y * q.y + prevQ.z * q.z + prevQ.w * q.w);
                    // |dot| 直接给"两帧之间转了多少"：dot≈1 表示几乎没转
                    worstStepRad = Math.max(worstStepRad, 2.0 * Math.acos(Math.min(1.0, dot)));
                }
                prevQ = new Quaterniond(q);
            }
        }

        System.out.printf(java.util.Locale.ROOT,
                "仿真 %.1fs：%d 帧 / %d 次采样；位移到 %.2f 格，自转 %.1f rad/s%n",
                total / FRAME_HZ, total, total / framesPerSample, maxPushedX, SPIN);

        FrameJitterMeter.Stats s = meter.stats();
        if (s == null) {
            check(false, "统计窗口没攒够", "filled < " + WINDOW);
            System.out.printf("%n=== CelestialSmoothProbe: %d/%d 通过，失败 %d ===%n", passed, passed + failed, failed);
            System.exit(1);
        }
        System.out.println("        " + meter.verdict());

        check(s.decidable() && s.pass(), "1 平滑：抖动 ≤ 1.5 且 跳变帧 = 0",
                String.format(java.util.Locale.ROOT, "抖动=%.2f 跳变帧=%d/%d",
                        s.jitter(), s.stalls(), s.frames()));
        // 期待每帧位移 = SPEED / FRAME_HZ = 0.15 格；抖动已覆盖均匀性，这里再看量级对不对
        double expectedStep = SPEED / FRAME_HZ;
        check(Math.abs(s.mean() - expectedStep) < expectedStep * 0.25,
                "2 每帧位移量级正确（没有被插值拉慢/加快）",
                String.format(java.util.Locale.ROOT, "实测均 %.4f，期待 %.4f 格", s.mean(), expectedStep));
        check(maxOutputX <= maxPushedX + 1.0e-6,
                "3 不外推：输出从不越过已推样本",
                String.format(java.util.Locale.ROOT, "输出最大 %.4f ≤ 样本最大 %.4f", maxOutputX, maxPushedX));
        check(worstUnitError < 1.0e-6,
                "4 姿态始终是单位四元数",
                String.format(java.util.Locale.ROOT, "最大 |‖q‖−1| = %.2e", worstUnitError));
        // 每帧应有转角 = SPIN/FRAME_HZ = 0.005 rad；取最短弧失败会绕一大圈（≈2π）
        double expectedSpin = SPIN / FRAME_HZ;
        check(worstStepRad < expectedSpin * 10.0,
                "5 姿态每帧步长有界（最短弧没坏）",
                String.format(java.util.Locale.ROOT, "最大步长 %.5f rad（期待 %.5f，上限 %.5f）",
                        worstStepRad, expectedSpin, expectedSpin * 10.0));

        System.out.printf("%n=== CelestialSmoothProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
