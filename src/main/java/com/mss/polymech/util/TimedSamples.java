package com.mss.polymech.util;

import java.util.function.DoubleSupplier;

/**
 * <b>带时间戳的快照环</b>：把"服务端/上游按某个节奏推过来的量"按时间戳存起来，
 * 渲染时在<b>包住"现在 − 延迟"的相邻两拍</b>之间插值。
 *
 * <h2>它解决的是哪一类问题（这是通用件，不是牵引枪专用）</h2>
 * 老写法 `lerp(prev, cur, partialTick)` 有一个<b>没写出来、但必须成立</b>的前提：
 * `prev ← cur; cur ← 新值` 这次交换恰好发生在 <b>tick 边界</b>（partialTick = 0）。
 * 只有那样，两段折线才首尾相接。而现实里：
 * <pre>
 *   交换落在一个任意相位 φ₀：
 *     交换前：P(t−1) + φ₀·Δ
 *     交换后：P(t)   + φ₀·Δ = 交换前 + Δ      ← 每个采样周期凭空跳一整个 Δ（幅度与 φ₀ 无关）
 * </pre>
 * 表现就是"抽抽"：动一下 → 停一下 → 又跳一下。而且<b>Δ 越大越明显</b>，
 * 所以采样越稀（周期越长）越难看。
 *
 * <p>本项目已经为天空修过一次同类 bug（2026-09-25，见 {@code CelestialBody} 的注释：
 * 天体每 tick 跳 108 km ≈ 6.9 像素）；当时把机制直接写在 {@code CelestialBody} 里。
 * 本类就是把那份机制<b>抽出来</b>，让所有"按拍同步、按帧渲染"的量共用同一份实现
 * —— 本项目有一条硬规矩：同一概念不许有两份实现。</p>
 *
 * <h2>为什么延迟必须是"每实例可配"</h2>
 * 延迟要 ≥ <b>2 个采样周期</b>，"渲染时刻必然被两拍包住"才成立：
 * <ul>
 *   <li>天空：样本是<b>每帧</b>推进去的（100+ Hz）⇒ 默认 0.10 s 已经是好几拍，余量充足；</li>
 *   <li>牵引枪：光束端点包是<b>每 tick</b>（20 Hz，周期 0.05 s）⇒ 延迟必须 ≥ 0.10 s。
 *       如果照抄天空那个 0.10 s 而采样是每 2 tick（0.1 s），延迟刚好等于 1 个周期
 *       ⇒ 稍有网络抖动就越过最新样本、被夹住不动 ⇒ <b>又开始抽</b>。
 *       所以这里把延迟设成参数，调用方按自己的节奏给。</li>
 * </ul>
 *
 * <h2>诊断读数</h2>
 * {@link #sampleIntervalSeconds()} / {@link #historySeconds()} / {@link #size()} 让上层能把
 * "到底几拍、每拍多久"打成一行读数 —— 本项目的实机判据要求程序判定，而不是肉眼说"好像不抽了"。
 */
public final class TimedSamples {

    /**
     * 渲染延迟默认值（秒）——照抄 {@code CelestialBody.INTERP_DELAY_SECONDS}（它那边是 2 拍）。
     *
     * <p>⚠️ 只在"样本比 tick 密"时可直接用；采样周期 ≥ 0.05 s 的调用方必须自己给
     * {@code ≥ 2 × 周期} 的值。</p>
     */
    public static final double DEFAULT_DELAY_SECONDS = 0.10;

    /**
     * 同一拍去重窗口（秒）：上游一次逻辑更新里可能分几次调用（例如位置与姿态各一次），
     * 落在这个窗口内视为<b>同一拍</b>（就地更新，不新增），否则会把"一拍"拆成两拍、
     * 插值权重被稀释（表现是插值变慢半拍）。
     */
    private static final double SAME_SAMPLE_EPS_SECONDS = 0.004;

    /** 维度（每个样本几个 double）。 */
    private final int dim;
    /** 环容量。 */
    private final int cap;
    /** 渲染延迟（秒）。 */
    private final double delaySeconds;
    /** 时钟（秒）；生产路径是 {@link #nowSeconds()}，探针注入仿真时间轴。 */
    private final DoubleSupplier clock;

    /** 时间戳（秒，{@link #nowSeconds()} 基准）。 */
    private final double[] times;
    /** 样本值。 */
    private final double[][] values;
    /** 已写入条数（到 cap 后恒为 cap）。 */
    private int count;
    /** 下一条写入的下标（环形）。 */
    private int next;

    public TimedSamples(int dim) {
        this(dim, 64, DEFAULT_DELAY_SECONDS, TimedSamples::nowSeconds);
    }

    /**
     * @param dim          每个样本的分量数（位置 3、四元数 4……）
     * @param cap          环容量（条数）
     * @param delaySeconds 渲染延迟（秒）；必须 {@code ≥ 2 × 采样周期}，见类注释
     */
    public TimedSamples(int dim, int cap, double delaySeconds) {
        this(dim, cap, delaySeconds, TimedSamples::nowSeconds);
    }

    /**
     * 时钟可注入的版本 —— <b>给离线判据用</b>。
     *
     * <p>为什么必须有它：这一项的判据里有一条是"<b>交换落在任意相位</b>时旧写法会跳 Δ"，
     * 而"任意相位"必须是<b>确定性的</b>才叫判据（跑两次结论不同就不是判据）。
     * 靠 {@code Thread.sleep} 造相位做不到：Windows 上 sleep 的粒度约 15ms，
     * 而一个 tick 才 50ms，误差能把相位甩掉三分之一。所以把时钟做成依赖，
     * 探针用一条**仿真时间轴**精确控制"第几帧推样本、第几帧读"。生产路径仍走
     * {@link #nowSeconds()}（单调钟）。</p>
     */
    public TimedSamples(int dim, int cap, double delaySeconds, DoubleSupplier clock) {
        if (dim < 1) {
            throw new IllegalArgumentException("dim 必须 >= 1，实际 " + dim);
        }
        if (cap < 2) {
            throw new IllegalArgumentException("cap 必须 >= 2，实际 " + cap);
        }
        this.dim = dim;
        this.cap = cap;
        this.delaySeconds = delaySeconds;
        this.clock = clock;
        this.times = new double[cap];
        this.values = new double[cap][dim];
    }

    /** 单调时钟（秒）。生产路径的默认时钟；探针走可注入的那个构造器。 */
    public static double nowSeconds() {
        return System.nanoTime() * 1.0e-9;
    }

    /**
     * 记一拍。与上一拍的间隔小于 {@link #SAME_SAMPLE_EPS_SECONDS} 时<b>就地更新</b>那一拍
     * （见该常量的注释），否则追加新的一拍。
     *
     * @param value 长度必须等于 {@code dim}
     */
    public void push(double... value) {
        if (value.length != dim) {
            throw new IllegalArgumentException("样本维度不符：期望 " + dim + "，实际 " + value.length);
        }
        double now = clock.getAsDouble();
        if (count > 0) {
            int last = (next - 1 + cap) % cap;
            if (now - times[last] < SAME_SAMPLE_EPS_SECONDS) {
                System.arraycopy(value, 0, values[last], 0, dim);
                return;
            }
        }
        int idx = next;
        times[idx] = now;
        System.arraycopy(value, 0, values[idx], 0, dim);
        next = (idx + 1) % cap;
        if (count < cap) {
            count++;
        }
    }

    /** 当前延迟（秒）。 */
    public double delaySeconds() {
        return delaySeconds;
    }

    /** 已存条数。 */
    public int size() {
        return count;
    }

    /**
     * 按时间戳插值求值（线性）。
     *
     * @return false = 样本不足（调用方自行回退到旧的"两拍 lerp"）
     */
    public boolean evaluate(double[] out) {
        return evaluate(out, false);
    }

    /**
     * 按时间戳插值求值（四元数用 slerp：分量线性插值在四元数上是错的）。
     *
     * @return false = 样本不足
     */
    public boolean evaluateQuat(double[] out) {
        return evaluate(out, true);
    }

    private boolean evaluate(double[] out, boolean quat) {
        if (count < 2 || out.length < dim) {
            return false;
        }
        int oldest = (next - count + cap) % cap;
        int newest = (next - 1 + cap) % cap;
        double target = clock.getAsDouble() - delaySeconds;

        // 比最旧还旧 / 比最新还新 ⇒ 夹住（"保持那一拍"，**不外推**：外推会让物体在丢包时飞出去）
        int a;
        int b;
        double w;
        if (target <= times[oldest]) {
            a = oldest;
            b = oldest;
            w = 0.0;
        } else if (target >= times[newest]) {
            a = newest;
            b = newest;
            w = 0.0;
        } else {
            a = oldest;
            b = newest;
            w = Double.NaN;
            for (int k = 0; k < count - 1; k++) {
                int ia = (oldest + k) % cap;
                int ib = (oldest + k + 1) % cap;
                if (target >= times[ia] && target <= times[ib]) {
                    double span = times[ib] - times[ia];
                    a = ia;
                    b = ib;
                    w = span > 1.0e-9 ? (target - times[ia]) / span : 0.0;
                    break;
                }
            }
            if (Double.isNaN(w)) {
                return false;               // 理论上到不了（上面已夹住），防御
            }
        }

        if (a == b) {
            System.arraycopy(values[a], 0, out, 0, dim);
            return true;
        }
        if (quat) {
            slerp(values[a], values[b], w, out);
        } else {
            for (int i = 0; i < dim; i++) {
                out[i] = values[a][i] + (values[b][i] - values[a][i]) * w;
            }
        }
        return true;
    }

    /**
     * 四元数 slerp（取<b>最短弧</b>）。
     *
     * <p>不做"q 与 −q 是同一旋转"的短弧修正的话，两个朝向接近、符号相反的样本之间会绕一大圈
     * —— 本项目在牵引枪角伺服上已经栽过一次同一个坑（见 {@code PhysgunRotation}）。</p>
     */
    private static void slerp(double[] q0, double[] q1, double t, double[] out) {
        double dot = q0[0] * q1[0] + q0[1] * q1[1] + q0[2] * q1[2] + q0[3] * q1[3];
        double sign = dot < 0.0 ? -1.0 : 1.0;
        dot *= sign;
        double w0;
        double w1;
        if (dot > 0.9995) {
            // 几乎同向：线性插值再归一化（slerp 的极限形式，避免除以 ~0）
            w0 = 1.0 - t;
            w1 = t * sign;
        } else {
            double theta = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
            double sinTheta = Math.sin(theta);
            w0 = Math.sin((1.0 - t) * theta) / sinTheta;
            w1 = Math.sin(t * theta) / sinTheta * sign;
        }
        double len = 0.0;
        for (int i = 0; i < 4; i++) {
            out[i] = q0[i] * w0 + q1[i] * w1;
            len += out[i] * out[i];
        }
        len = Math.sqrt(len);
        if (len > 1.0e-9) {
            for (int i = 0; i < 4; i++) {
                out[i] /= len;
            }
        }
    }

    /** 相邻两拍的时间间隔（秒）；样本不足返回 {@code NaN}。 */
    public double sampleIntervalSeconds() {
        if (count < 2) {
            return Double.NaN;
        }
        int newest = (next - 1 + cap) % cap;
        int prev = (next - 2 + cap) % cap;
        return times[newest] - times[prev];
    }

    /** 环里的时间跨度（秒）；样本不足返回 {@code NaN}。 */
    public double historySeconds() {
        if (count < 2) {
            return Double.NaN;
        }
        int oldest = (next - count + cap) % cap;
        int newest = (next - 1 + cap) % cap;
        return times[newest] - times[oldest];
    }

    /** 清空（换维度/重连时用）。 */
    public void clear() {
        count = 0;
        next = 0;
    }
}
