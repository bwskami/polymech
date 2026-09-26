/**
 * 天体渲染插值的离线对拍：**旧的两拍 lerp（按 partialTick）** vs **新的时间戳插值（延迟渲染）**。
 *
 * <h2>要证明的事</h2>
 * 旧的 {@code lerp(old_pos, pos, partialTick)} 隐含一个前提：`old_pos ← pos; pos ← 新值`
 * 这次交换必须发生在 **tick 边界**。而客户端 tick 与服务端/网络拍子是**各自独立的**，
 * 交换实际落在一个漂移的相位上，于是**每个 tick 凭空跳一整个采样间隔 Δ**。
 *
 * <p>本探针把两种实现在**同一串带相位漂移的样本**上跑一遍，只用"人的眼睛能判断的量"比较：
 * <b>每帧位移的抖动 = 最大单帧位移 / 平均单帧位移</b>。匀速平移 ≈ 1.0；每 tick 混一次跳变就明显 &gt; 1。</p>
 *
 * <p>不依赖任何游戏类，纯 double 数学。运行：
 * <pre>java -Dstdout.encoding=UTF-8 native/jni-smoketest/InterpProbe.java</pre></p>
 */
public class InterpProbe {

    // ---- 太空维度真实数据（地球）----
    /** 地球轨道速度 × 71.8033 倍时间 = 每秒位移（米）。 */
    static final double V = 30151.0 * 71.80333;
    /** "传送到地球上空的太空里"的距离：2.2 倍地球半径。 */
    static final double DIST = 6.371e6 * 2.2;
    /** 屏幕：1080 高 / 70° 垂直 FOV。 */
    static final double PX_PER_DEG = 1080.0 / 70.0;

    static final double FRAME_DT = 1.0 / 60.0;   // 60 fps
    static final double TICK_DT = 0.05;          // 客户端 20 TPS
    static final double SIM_SECONDS = 4.0;

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  [%s] %-46s %s%n", ok ? "PASS" : "FAIL", what, detail);
        if (!ok) {
            fails++;
        }
    }

    /** 天体的真实角位置（弧度）：横向匀速掠过，观察者在距离 DIST 处。 */
    static double trueAngle(double t) {
        return Math.atan(V * t / DIST);
    }

    /** 第 k 拍的"真实发生时刻"（服务端 tick 时刻）。 */
    static double sampleTrueTime(int k) {
        return k * TICK_DT;
    }

    /**
     * 第 k 拍的**到达客户端时刻**：服务端 tick 时刻 + 一个**缓慢漂移**的网络/线程相位。
     * 这正是"客户端 tick 与服务端 tick 各跑各的"的后果（不是随机噪声，是慢漂移）。
     */
    static double sampleArrivalTime(int k) {
        double drift = 0.012 + 0.010 * Math.sin(k * 0.05);   // 12ms ± 10ms，缓慢漂移
        return sampleTrueTime(k) + drift;
    }

    /** 主循环：三种插值各跑一遍，返回 [均位移px, 最大位移px, 抖动=max/中位, 最大"与真值的偏差"px]。 */
    static double[] run(String mode) {
        // 时间戳环（新模式用）
        double[] histT = new double[256];
        double[] histA = new double[256];
        int histCount = 0;

        // 旧模式的两拍
        double oldA = trueAngle(0.0);
        double posA = trueAngle(0.0);

        int nextSample = 0;
        double lastAngle = Double.NaN;
        double lastTrue = Double.NaN;
        double sum = 0.0;
        double max = 0.0;
        double maxDev = 0.0;
        int frames = 0;
        double[] disp = new double[4096];

        for (double t = 0.0; t <= SIM_SECONDS; t += FRAME_DT) {
            // 该到的样本都到齐（到达时刻 = 服务端时刻 + 漂移相位）
            while (sampleArrivalTime(nextSample) <= t) {
                double a = trueAngle(sampleTrueTime(nextSample));
                if ("old".equals(mode)) {
                    oldA = posA;      // ← 这两行就是 moveToDirect
                    posA = a;         // ← 交换发生在"包到达"这一刻，而不是 tick 边界
                } else {
                    histT[histCount] = sampleArrivalTime(nextSample);
                    histA[histCount] = a;
                    histCount++;
                }
                nextSample++;
            }

            double shown;
            if ("old".equals(mode)) {
                double phi = (t / TICK_DT) % 1.0;        // 客户端 tick 相位（与包到达无关！）
                shown = oldA + (posA - oldA) * phi;
            } else {
                double want = t - 0.10;                   // 时间戳模式：渲染"100ms 之前"
                shown = sampleAt(histT, histA, histCount, want);
            }

            double trueAngleNow = trueAngle(t);
            if (!Double.isNaN(lastAngle) && t > 0.5) {   // 前 0.5s 暖机不计
                double d = Math.abs(shown - lastAngle) * 180.0 / Math.PI * PX_PER_DEG;   // 像素
                double dTrue = Math.abs(trueAngleNow - lastTrue) * 180.0 / Math.PI * PX_PER_DEG;
                // 时间戳模式渲染的是"100ms 之前"，真值也要对齐到同一时刻才可比
                double dTrueAligned = Math.abs(trueAngle(t - 0.10) - trueAngle(lastT - 0.10))
                        * 180.0 / Math.PI * PX_PER_DEG;
                double ref = "old".equals(mode) ? dTrue : dTrueAligned;
                if (frames > 0) {
                    sum += d;
                    if (d > max) {
                        max = d;
                    }
                    if (disp[Math.min(frames, disp.length - 1)] == 0.0) {
                        disp[Math.min(frames, disp.length - 1)] = d;
                    }
                    double dev = Math.abs(d - ref);
                    if (dev > maxDev) {
                        maxDev = dev;
                    }
                }
                frames++;
            }
            lastAngle = shown;
            lastTrue = trueAngleNow;
            lastT = t;
        }
        // 中位数（对"天体越掠越快"这种真实加速度免疫 —— 均值会被加速段拉高，中位数不会）
        int n = Math.min(frames, disp.length);
        double[] copy = java.util.Arrays.copyOf(disp, n);
        java.util.Arrays.sort(copy);
        double median = n > 0 ? copy[n / 2] : Double.NaN;
        double mean = sum / Math.max(1, frames - 1);
        return new double[]{mean, max, max / median, maxDev};
    }

    static double lastT = 0.0;

    /** 时间戳环里取"want 时刻"的位置：在包住它的两拍之间线性插值（超出范围则夹住）。 */
    static double sampleAt(double[] histT, double[] histA, int n, double want) {
        if (n == 0) {
            return 0.0;
        }
        if (n == 1 || want <= histT[0]) {
            return histA[0];
        }
        if (want >= histT[n - 1]) {
            return histA[n - 1];
        }
        for (int i = 0; i < n - 1; i++) {
            if (want >= histT[i] && want <= histT[i + 1]) {
                double w = (want - histT[i]) / (histT[i + 1] - histT[i]);
                return histA[i] + (histA[i + 1] - histA[i]) * w;
            }
        }
        return histA[n - 1];
    }

    public static void main(String[] args) {
        System.out.printf("场景：地球以 %.3e m/s 掠过（= 轨道速度×71.8），观察者距离 %.3e m%n", V, DIST);
        System.out.printf("      屏幕 %.1f px/°（1080px / 70°），60fps，客户端 tick 50ms%n", PX_PER_DEG);
        System.out.printf("      真实视运动 = %.3f °/秒 = %.2f px/帧%n%n",
                Math.toDegrees(V / DIST), Math.toDegrees(V / DIST) / 60.0 * PX_PER_DEG);

        double[] oldR = run("old");
        double[] newR = run("new");

        System.out.printf("  旧：两拍 lerp（按 partialTick）  每帧均=%.2f 最大=%.2f px  抖动(max/中位)=%.2f"
                        + "  与真值最大偏差=%.2f px%n",
                oldR[0], oldR[1], oldR[2], oldR[3]);
        System.out.printf("  新：时间戳插值（延迟 100ms）    每帧均=%.2f 最大=%.2f px  抖动(max/中位)=%.2f"
                        + "  与真值最大偏差=%.2f px%n%n",
                newR[0], newR[1], newR[2], newR[3]);

        // 三个判据：
        //  ① 旧实现必须**复现出病**（否则本探针的场景不成立，结论不可信）；
        //  ② 新实现与"真值"的偏差必须很小（这是最硬的判据：它直接问"画出来的位移对不对"）；
        //  ③ 新实现的抖动(max/中位)必须接近 1。
        check("旧实现在此场景下确实有跳变（抖动 > 1.5）", oldR[2] > 1.5,
                String.format("抖动=%.2f（若 ≈1 则本探针没复现出问题，结论不可信）", oldR[2]));
        check("新实现与真值的最大偏差 < 0.3 px", newR[3] < 0.30,
                String.format("偏差=%.3f px（旧 %.2f px）", newR[3], oldR[3]));
        check("新实现抖动(max/中位) < 1.30", newR[2] < 1.30,
                String.format("抖动=%.2f", newR[2]));
        check("新实现把最大单帧位移压到接近真值", newR[1] < oldR[1] * 0.5,
                String.format("旧最大 %.2f px → 新最大 %.2f px", oldR[1], newR[1]));

        System.out.printf("%n%s（失败 %d 条）%n", fails == 0 ? "全部通过 ✔" : "★有失败", fails);
        if (fails != 0) {
            System.exit(1);
        }
    }
}
