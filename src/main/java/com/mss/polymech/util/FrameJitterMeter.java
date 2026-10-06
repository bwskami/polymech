package com.mss.polymech.util;

import java.util.Arrays;

/**
 * <b>每帧速度抖动计</b>：把一个"应该匀速/连续运动"的东西每帧喂进来，
 * 统计它的<b>逐帧速度</b>（位移 ÷ 帧间隔）是否均匀。
 *
 * <h2>为什么需要它（这是"通用方法"的第二半）</h2>
 * {@link TimedSamples} 修的是"采样相位错位造成的跳变"，但**改完怎么知道真的好了**？
 * 肉眼看"好像不抽了"不是判据 —— 本项目有一条硬规矩：
 * 实机判据必须**程序判定 PASS/FAIL**，而且用绝对量、不用相机相对量。
 *
 * <p>顺滑运动在数值上的特征是：<b>逐帧速度几乎相等</b>（抖动 = 最大/均 ≈ 1.0）。
 * 只要每秒混进几次跳变，这个比值就会明显 &gt; 1；而"动一下停一下"会同时让
 * <b>停帧</b>（速度 &lt; 5% 均值）的计数不为 0。这两条一起看，就能把
 * "抽抽"翻译成两个数 —— 这个口径与天空那条线（{@code SpaceRenderer} 的"太空顺滑"）同源，
 * 免得同一种毛病在两处用两套说法。</p>
 *
 * <h2>v2 相对 v1 的两处结构性修正（2026-10-04，各有真实事故背书）</h2>
 * <ol>
 *   <li><b>按帧间隔归一化（位移 → 速度）</b>。v1 直接统计"每帧位移"，隐含假设帧间隔恒定。
 *       帧间隔不恒时它会<b>两头误报</b>：游戏内一次 GC 卡帧（帧间隔 300ms）会让"那帧位移"虚大
 *       ⇒ 抖动虚高；{@code CelestialSmoothProbe} 的实时忙等迟到帧同理（偶发 2.25，健康值 1.00），
 *       当时只好把该探针的容差单独放宽到 2.5 并记了待办 —— 现在归一化做完，容差收回 1.5。
 *       反方向更重要：2026-10-03 牵引枪读数 {@code 最小=0.0000 跳变帧=101/120} 里，
 *       560fps 下"每 5.6 帧才跨一个物理步"的零位移帧，v1/v2 都能抓住，但只有 v2 能同时
 *       把那些帧的<b>时长</b>正确折算进速度（长帧零位移 = 真停，短帧零位移 = 步进保持）。</li>
 *   <li><b>窗口按时间开（秒），不按帧数开</b>。v1 的 120 帧窗口在 60fps 下是 2 秒、
 *       在 560fps 下只有 0.21 秒 —— 同一个"判据"在不同机器上量的根本不是同一段时间，
 *       短窗口还会被一次偶然的平静期骗过（假 PASS）。现在窗口 = 固定秒数
 *       （牵引枪取 1.2s，与当初"120 帧 ≈ 1.2 秒"的设计意图逐字一致）。</li>
 * </ol>
 *
 * <h2>用法</h2>
 * <pre>
 *   meter.sample(x, y, z);          // 每帧喂一次（世界坐标；内部用 nanoTime 记帧时刻）
 *   meter.sample(t, x, y, z);       // 探针用：喂仿真时间轴，保证确定性
 *   if (meter.ready()) { ... meter.verdict() ... }   // 攒够窗口后取结论
 *   meter.reset();                  // 一次抓取结束时清零，下一次重新计
 * </pre>
 *
 * <p><b>统计覆盖"投喂起点以来"的整段</b>（{@link #reset()} 才清）：抓 10 秒就量 10 秒，
 * 读数里打印实际观察时长。所以<b>预热段要由调用方跳过</b> —— 环还没攒够两拍时输出被
 * "夹在最旧一拍"（恒定值 ⇒ 停帧），那是起步脚手架不是被测行为。老版 v1 是 120 帧环形
 * 缓冲，会把预热顺手冲掉 —— 但那个语义没写在任何地方，v2 改成按时间累计后它立刻
 * 把探针 A/C 冤枉成 FAIL（2026-10-04 实测，教训：量具语义变了，喂法要跟着过一遍）。</p>
 */
public final class FrameJitterMeter {

    /** 停帧的门槛：逐帧速度小于均值的这个比例就算"这一帧几乎没动"。 */
    private static final double STALL_FRACTION = 0.05;

    /**
     * 停帧计数的<b>运动门槛</b>（格/秒）：窗口均速低于它就认为"主体基本没动"，停帧不计数。
     *
     * <p>为什么需要：停帧的语义是"<b>该动没动</b>"，而"没动"与"动得丝滑"是两件事
     * ——人站着抓着物体时（实测枪口均速 0.28~0.31 格/秒），绝大多数帧零位移是<b>天经地义</b>，
     * 把它们算成停帧会把判据淹了（2026-10-04 实测：枪口停帧 91% 全是这种水分）。
     * 门槛取 0.5：站立漂移实测 ≤0.31，行走/拖拽实测 3.3+，中间留了余量。
     * <b>注意只闸停帧，不闸抖动</b> ——静止窗口里的速度尖峰（实测枪口站立时 5~15 格/秒的
     * 位置修正突发）仍会被抖动正常抓出来。</p>
     */
    private static final double STALL_MIN_MEAN = 0.5;

    /** 抖动（最大/均）的 PASS 门槛：帧间隔本身有几个百分点的起伏，1.5 留足余量。 */
    public static final double JITTER_PASS = 1.5;

    /** 默认窗口（秒）——牵引枪那条读数用的就是它。 */
    public static final double DEFAULT_WINDOW_SECONDS = 1.2;

    /** 攒够多少帧才允许下结论（窗口够长但帧太少 ⇒ 统计是噪声）。 */
    private static final int MIN_FRAMES = 32;

    /** 数组初始容量 / 硬上限（上限防的是"喂错时钟把窗口撑爆"这类自身 bug）。 */
    private static final int INITIAL_CAPACITY = 128;
    private static final int MAX_FRAMES = 65536;

    private final String label;
    private final double windowSeconds;
    private double[] times;
    private double[] dx;
    private double[] dy;
    private double[] dz;

    private int filled;
    private boolean hasPrev;
    private double prevX;
    private double prevY;
    private double prevZ;
    private double prevTime;

    /**
     * @param label         读数前缀（例如 {@code "[牵引枪·丝滑判据]"}）
     * @param windowSeconds 统计窗口（<b>秒</b>，不是帧数）：与帧率无关，任何机器上量的是同一段时间
     */
    public FrameJitterMeter(String label, double windowSeconds) {
        this.label = label;
        this.windowSeconds = windowSeconds > 0.0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
        this.times = new double[INITIAL_CAPACITY];
        this.dx = new double[INITIAL_CAPACITY];
        this.dy = new double[INITIAL_CAPACITY];
        this.dz = new double[INITIAL_CAPACITY];
    }

    /** 每帧喂一个世界点（帧时刻取 {@link System#nanoTime()}）。 */
    public void sample(double x, double y, double z) {
        sample(System.nanoTime() * 1.0e-9, x, y, z);
    }

    /**
     * 每帧喂一个世界点，帧时刻由调用方给（<b>给离线探针用</b>：仿真时间轴才能保证确定性，
     * 与 {@link TimedSamples} 的可注入时钟同一个理由）。
     */
    public void sample(double timeSeconds, double x, double y, double z) {
        if (hasPrev) {
            double dt = timeSeconds - prevTime;
            if (dt > 0.0) {
                append(dt, x - prevX, y - prevY, z - prevZ, timeSeconds);
            }
            // dt <= 0（同一时刻喂两次）：丢弃这一段间隔，但位置/时刻照常前进，
            // 否则下一段会把两次位移合并算进一个偏大的 dt 里。
        }
        prevX = x;
        prevY = y;
        prevZ = z;
        prevTime = timeSeconds;
        hasPrev = true;
    }

    private void append(double dt, double ddx, double ddy, double ddz, double timeSeconds) {
        if (filled == times.length) {
            if (filled >= MAX_FRAMES) {
                // 到上限：丢最旧的一帧保持窗口语义（窗口本就是"最近一段时间"）
                System.arraycopy(times, 1, times, 0, filled - 1);
                System.arraycopy(dx, 1, dx, 0, filled - 1);
                System.arraycopy(dy, 1, dy, 0, filled - 1);
                System.arraycopy(dz, 1, dz, 0, filled - 1);
                filled--;
            } else {
                int cap = Math.min(MAX_FRAMES, filled * 2);
                times = Arrays.copyOf(times, cap);
                dx = Arrays.copyOf(dx, cap);
                dy = Arrays.copyOf(dy, cap);
                dz = Arrays.copyOf(dz, cap);
            }
        }
        times[filled] = timeSeconds;
        dx[filled] = ddx / dt;      // ← 归一化：存的是**速度**（格/秒），不是位移
        dy[filled] = ddy / dt;
        dz[filled] = ddz / dt;
        filled++;
    }

    /** 窗口攒够了吗（时长够 <b>且</b> 帧数够；没攒够时任何结论都是噪声）。 */
    public boolean ready() {
        return filled >= MIN_FRAMES && filled >= 2 && (times[filled - 1] - times[0]) >= windowSeconds;
    }

    public String label() {
        return label;
    }

    /** 窗口时长（秒）——"至少观察这么久才下结论"；读数里打印的是实际观察时长。 */
    public double windowSeconds() {
        return windowSeconds;
    }

    /** 一次抓取结束/重新开始时清零（否则会把上一段的运动混进来）。 */
    public void reset() {
        filled = 0;
        hasPrev = false;
    }

    /**
     * 一次窗口的数值结论（<b>速度口径</b>：mean/max/min 的单位都是 格/秒）。
     *
     * @param frames     参与统计的帧间隔数
     * @param mean       逐帧速度均值（格/秒）
     * @param max        逐帧速度最大值（格/秒）
     * @param min        逐帧速度最小值（格/秒）
     * @param jitter     {@code max / mean}；{@code decidable == false} 时为 {@code NaN}
     * @param stalls     停帧数（速度 &lt; 5% 均值 —— "上游在动而这一帧没动"；
     *                   仅当窗口均速 ≥ 0.5 格/秒才计数，见 {@link #STALL_MIN_MEAN}）
     * @param decidable  速度均值是否大到"能判"（见 {@link #verdict()} 的说明）
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
        double max = -Double.MAX_VALUE;
        double min = Double.MAX_VALUE;
        for (int i = 0; i < filled; i++) {
            double v = speed(i);
            sum += v;
            if (v > max) {
                max = v;
            }
            if (v < min) {
                min = v;
            }
        }
        double mean = sum / filled;
        // 速度均值≈0 ⇒ 物体根本没动（例如抓着一个躺着不动的体）：这时**不能**判 PASS，
        // 因为"没动"与"动得丝滑"是两件事，判 PASS 就是一条永远通过的假判据。
        boolean decidable = mean > 1.0e-6;
        int stalls = 0;
        // 停帧只在"明显在动"的窗口里计数（见 STALL_MIN_MEAN）：静止窗口的零位移帧
        // 是天经地义，不是"抽"；那种窗口里的异常由抖动（速度尖峰）来抓。
        if (decidable && mean >= STALL_MIN_MEAN) {
            for (int i = 0; i < filled; i++) {
                if (speed(i) < mean * STALL_FRACTION) {
                    stalls++;
                }
            }
        }
        double jitter = decidable ? max / mean : Double.NaN;
        boolean pass = decidable && stalls == 0 && jitter <= JITTER_PASS;
        return new Stats(filled, mean, max, min, jitter, stalls, decidable, pass);
    }

    /** 第 i 段的速率（格/秒）——dx/dy/dz 在 {@link #append} 里已除过帧间隔。 */
    private double speed(int i) {
        double vx = dx[i];
        double vy = dy[i];
        double vz = dz[i];
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
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
                    "%s 静默样本：窗口内速度均值 %.3e 格/秒（物体本来就没动）⇒ 不可判；"
                            + "请抓着物体移动/转动后再看", label, s.mean());
        }
        // 读数打印**实际观察时长**（窗口是"至少观察这么久"，抓得越久统计越稳，别谎报成窗口值）
        double span = times[filled - 1] - times[0];
        return String.format(java.util.Locale.ROOT,
                "%s %.1f 秒 %d 帧：逐帧速度 均=%.3f 最大=%.3f 最小=%.3f 格/秒；抖动(最大/均)=%.2f（阈值 %.2f）；"
                        + "停帧=%d/%d ⇒ %s",
                label, span, s.frames() + 1, s.mean(), s.max(), s.min(), s.jitter(), JITTER_PASS,
                s.stalls(), s.frames(), s.pass() ? "PASS" : "FAIL");
    }
}
