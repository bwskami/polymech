package com.mss.polymech.util;

/**
 * <b>每帧位移抖动计</b>：把一个"应该匀速/连续运动"的东西每帧喂进来，统计它的每帧位移是否均匀。
 *
 * <h2>为什么需要它（这是"通用方法"的第二半）</h2>
 * {@link TimedSamples} 修的是"采样相位错位造成的跳变"，但**改完怎么知道真的好了**？
 * 肉眼看"好像不抽了"不是判据 —— 本项目有一条硬规矩：
 * 实机判据必须**程序判定 PASS/FAIL**，而且用绝对量、不用相机相对量。
 *
 * <p>顺滑运动在数值上的特征是：<b>每帧位移几乎相等</b>（抖动 = 最大/均 ≈ 1.0）。
 * 只要每秒混进几次跳变，这个比值就会明显 &gt; 1；而"动一下停一下"会同时让
 * <b>跳变帧</b>（位移 &lt; 5% 均值）的计数不为 0。这两条一起看，就能把
 * "抽抽"翻译成两个数 —— 这个口径与天空那条线（{@code SpaceRenderer} 的"太空顺滑"）完全一致，
 * 免得同一种毛病在两处用两套说法。</p>
 *
 * <h2>用法</h2>
 * <pre>
 *   meter.sample(x, y, z);          // 每帧喂一次（世界坐标）
 *   if (meter.ready()) { ... meter.verdict() ... }   // 攒够窗口后取结论
 *   meter.reset();                  // 一次抓取结束时清零，下一次重新计
 * </pre>
 */
public final class FrameJitterMeter {

    /** 跳变帧的门槛：位移小于均值的这个比例就算"这一帧几乎没动"。 */
    private static final double STALL_FRACTION = 0.05;

    /** 抖动（最大/均）的 PASS 门槛：帧间隔本身有几个百分点的起伏，1.5 留足余量。 */
    public static final double JITTER_PASS = 1.5;

    private final String label;
    private final int windowFrames;
    private final double[] dx;
    private final double[] dy;
    private final double[] dz;

    private int filled;
    private int next;
    private boolean hasPrev;
    private double prevX;
    private double prevY;
    private double prevZ;

    /**
     * @param label        读数前缀（例如 {@code "[牵引枪·丝滑判据]"}）
     * @param windowFrames 统计窗口（帧数）；按 100fps 算，120 帧 ≈ 1.2 秒
     */
    public FrameJitterMeter(String label, int windowFrames) {
        this.label = label;
        this.windowFrames = Math.max(8, windowFrames);
        this.dx = new double[this.windowFrames];
        this.dy = new double[this.windowFrames];
        this.dz = new double[this.windowFrames];
    }

    /** 每帧喂一个世界点。 */
    public void sample(double x, double y, double z) {
        if (hasPrev) {
            dx[next] = x - prevX;
            dy[next] = y - prevY;
            dz[next] = z - prevZ;
            next = (next + 1) % windowFrames;
            if (filled < windowFrames) {
                filled++;
            }
        }
        prevX = x;
        prevY = y;
        prevZ = z;
        hasPrev = true;
    }

    /** 窗口攒够了吗（没攒够时任何结论都是噪声）。 */
    public boolean ready() {
        return filled >= windowFrames;
    }

    public String label() {
        return label;
    }

    /** 一次抓取结束/重新开始时清零（否则会把上一段的位移混进来）。 */
    public void reset() {
        filled = 0;
        next = 0;
        hasPrev = false;
    }

    /**
     * 一次窗口的数值结论。
     *
     * @param frames     参与统计的帧数
     * @param mean       每帧位移均值（格）
     * @param max        每帧位移最大值
     * @param min        每帧位移最小值
     * @param jitter     {@code max / mean}；{@code decidable == false} 时为 {@code NaN}
     * @param stalls     跳变帧数（位移 &lt; 5% 均值）
     * @param decidable  位移均值是否大到"能判"（见 {@link #verdict()} 的说明）
     * @param pass       程序判定结论；{@code decidable == false} 时恒为 false
     */
    public record Stats(int frames, double mean, double max, double min, double jitter,
                        int stalls, boolean decidable, boolean pass) {
    }

    /**
     * 数值结论（<b>给离线探针用</b>：探针要断言数字，不该去解析"给人看的字符串"）。
     *
     * @return 未攒够窗口时返回 null（**不要**拿半个窗口下结论）
     */
    public Stats stats() {
        if (!ready()) {
            return null;
        }
        double sum = 0.0;
        double max = 0.0;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < filled; i++) {
            double d = len(i);
            sum += d;
            if (d > max) {
                max = d;
            }
            if (d < min) {
                min = d;
            }
        }
        double mean = sum / filled;
        // 均值≈0 ⇒ 物体根本没动（例如抓着一个躺着不动的体）：这时**不能**判 PASS，
        // 因为"没动"与"动得丝滑"是两件事，判 PASS 就是一条永远通过的假判据。
        boolean decidable = mean > 1.0e-6;
        int stalls = 0;
        if (decidable) {
            for (int i = 0; i < filled; i++) {
                if (len(i) < mean * STALL_FRACTION) {
                    stalls++;
                }
            }
        }
        double jitter = decidable ? max / mean : Double.NaN;
        boolean pass = decidable && stalls == 0 && jitter <= JITTER_PASS;
        return new Stats(filled, mean, max, min, jitter, stalls, decidable, pass);
    }

    private double len(int i) {
        return Math.sqrt(dx[i] * dx[i] + dy[i] * dy[i] + dz[i] * dz[i]);
    }

    /**
     * 结论：一行可读读数 + 程序判定的 PASS/FAIL。
     *
     * @return 未攒够窗口时返回 null；攒够但"物体本来就没动"时返回一行<b>不可判</b>说明
     */
    public String verdict() {
        Stats s = stats();
        if (s == null) {
            return null;
        }
        if (!s.decidable()) {
            return String.format(java.util.Locale.ROOT,
                    "%s 静默样本：窗口内位移均值 %.3e 格（物体本来就没动）⇒ 不可判；"
                            + "请抓着物体移动/转动后再看", label, s.mean());
        }
        return String.format(java.util.Locale.ROOT,
                "%s %d 帧：每帧位移 均=%.4f 最大=%.4f 最小=%.4f 格；抖动(最大/均)=%.2f（阈值 %.2f）；"
                        + "跳变帧=%d/%d ⇒ %s",
                label, s.frames(), s.mean(), s.max(), s.min(), s.jitter(), JITTER_PASS,
                s.stalls(), s.frames(), s.pass() ? "PASS" : "FAIL");
    }
}
