import com.mss.polymech.client.space.RenderCompression;

/**
 * S3 的离线验证：距离压缩必须满足 space 那条不变式 —— <b>角直径不变</b>。
 *
 * 跑法（见 docs/mps-clone-plan.md §13）：
 *   javac -encoding UTF-8 -cp "build\classes\java\main" -d build\pm-probe RenderCompressionTest.java
 *   java "-Dstdout.encoding=UTF-8" -cp "build\pm-probe;build\classes\java\main" RenderCompressionTest
 *
 * 只依赖 RenderCompression（纯数学，无 MC 类），所以 classpath 不需要游戏 jar。
 */
public final class RenderCompressionTest {

    public static void main(String[] args) {
        int fails = 0;

        // ① 近处（<= NEAR）必须原样
        fails += check("x=0 原样", RenderCompression.compress(0.0) == 0.0);
        fails += check("x=NEAR 原样", RenderCompression.compress(RenderCompression.NEAR) == RenderCompression.NEAR);

        // ② 单调递增
        double prev = -1.0;
        boolean mono = true;
        for (double d = 0.0; d < 1.0e13; d = d == 0.0 ? 1.0 : d * 1.7) {
            double c = RenderCompression.compress(d);
            if (c < prev) {
                mono = false;
                break;
            }
            prev = c;
        }
        fails += check("单调递增", mono);

        // ③ 上界：压缩后恒 <= FAR。
        //    注意这里是 **<=** 而不是 <：数学上 FAR 是渐近上界，但 exp(-巨大) 在浮点下会下溢到 0，
        //    于是"无穷远"会**恰好**压到 FAR。这一条直接决定了投影的 far 必须**严格大于** FAR，
        //    否则最远的天体会正好落在远平面上被裁掉 —— 这正是 space 把 getDepthFar 设成 FAR×2 的原因。
        double maxSeen = 0.0;
        for (double d : new double[]{1.0e5, 1.0e8, 1.5e11, 5.9e12, 1.0e13, 1.0e15}) {
            maxSeen = Math.max(maxSeen, RenderCompression.compress(d));
        }
        fails += check("压缩后 <= FAR（实际最大 " + String.format("%.1f", maxSeen) + "）",
                maxSeen <= RenderCompression.FAR);

        // ④ ★核心不变式：角直径不变  atan(R/L) == atan((R*zoom)/compress(L))
        System.out.println("天体     中心距(米)      半径(米)     真实角直径(°)   压缩后角直径(°)   相对误差");
        double[][] cases = {
                {3.08e11, 6.371e6, 0},   // 火星看地球
                {2.06e11, 6.96e8, 0},    // 火星看太阳
                {1.5e11, 1.737e6, 0},    // 地球看月球
                {9.4e6, 1.1e4, 0},       // 火星看火卫一
                {2.0e13, 6.371e6, 0},    // 极远
        };
        String[] names = {"地球", "太阳", "月球", "火卫一", "极远"};
        for (int i = 0; i < cases.length; i++) {
            double dist = cases[i][0];
            double radius = cases[i][1];
            double length = dist - radius;
            double zoom = RenderCompression.zoomFor(dist, radius);
            double compressed = RenderCompression.compress(length);
            // 角直径（真实 vs 压缩后）
            double trueAngle = 2.0 * Math.atan(radius / length);
            double compAngle = 2.0 * Math.atan(radius * zoom / Math.max(1.0e-9, compressed));
            double rel = Math.abs(compAngle - trueAngle) / trueAngle;
            System.out.printf("%-8s %14.3e %12.3e %14.6f %16.6f %12.2e%n",
                    names[i], dist, radius, Math.toDegrees(trueAngle), Math.toDegrees(compAngle), rel);
            fails += check("角直径不变(" + names[i] + ")", rel < 1e-12);
        }

        // ⑤ ★ 压缩对 shader 的影响（0.1.3 的 CelestialBodyDataUBO 把 Pos/半径/大气高度都乘了 zoom；
        //    我们的 UBO 里有 Pos 与 RealPos 两份，而 shader 用 RealPos 算光照方向与遮挡
        //    ——planet_atmosphere.fsh:168）。所以"要不要开压缩"必须先回答两个量化问题：
        //      ① 样点与光源**都**按各自 zoom 压缩后，光照方向还准不准？
        //      ② 只压样点、RealPos 不压（两套坐标系混用）会错多少？
        System.out.println();
        System.out.println("--- ⑤ 压缩对光照方向的影响（决定 UBO 要不要跟着压）---");
        double earthR = 6.371e6;
        double arrival = 2.2 * earthR;                 // 相机在地球 2.2R（传送到达点，§31.28）
        double sunR = 6.96e8;
        double sunDist = 1.5e11;
        double[] earthC = {arrival, 0.0, 0.0};         // 相机在原点，地球在 +X
        double[] sunC = {sunDist * 0.94, sunDist * 0.34, 0.0};
        double zEarth = RenderCompression.zoomFor(len(earthC), earthR);
        double zSun = RenderCompression.zoomFor(len(sunC), sunR);
        double earthSurfLen = len(earthC) - earthR;
        System.out.printf("   地球: 中心距=%.3e 半径=%.3e ⇒ zoom=%.6f（表面 %.3e → %.3e，压了 %.1f 倍）%n",
                len(earthC), earthR, zEarth, earthSurfLen, RenderCompression.compress(earthSurfLen),
                earthSurfLen / RenderCompression.compress(earthSurfLen));
        System.out.printf("   太阳: 中心距=%.3e 半径=%.3e ⇒ zoom=%.6e（压缩后 %.1f，FAR=%.0f，far 取 FAR×2）%n",
                len(sunC), sunR, zSun, RenderCompression.compress(len(sunC) - sunR), RenderCompression.FAR);
        String[] dirs = {"正对相机", "背对相机", "侧向", "朝太阳侧"};
        double[][] surf = {
                {earthC[0] + earthR, 0.0, 0.0},
                {earthC[0] - earthR, 0.0, 0.0},
                {earthC[0], earthR, 0.0},
                {earthC[0] + earthR * 0.94, earthR * 0.34, 0.0},
        };
        double maxBoth = 0.0;
        double maxMixed = 0.0;
        for (int i = 0; i < surf.length; i++) {
            double[] s = surf[i];
            double[] trueDir = norm(sub(sunC, s));                              // 真实方向
            double[] both = norm(sub(scale(sunC, zSun), scale(s, zEarth)));     // 两侧都压（space 做法）
            double[] mixed = norm(sub(sunC, scale(s, zEarth)));                 // 只压样点（我们现状）
            double eBoth = angleDeg(trueDir, both);
            double eMixed = angleDeg(trueDir, mixed);
            maxBoth = Math.max(maxBoth, eBoth);
            maxMixed = Math.max(maxMixed, eMixed);
            System.out.printf("   样点%-8s 全压误差=%.4f°   只压一半误差=%.3f°%n", dirs[i], eBoth, eMixed);
        }
        // 判据：**我们自己的双份设计**（Pos 压缩、RealPos 真实）必须准；全压只是拿来当"不照抄"的证据。
        fails += check("半压（Pos 压缩 / RealPos 真实）光照方向误差 < 0.05°", maxMixed < 0.05);
        fails += check("全压明显更差（= 不照抄 space 这一处的量化依据）", maxBoth > maxMixed * 100.0);
        System.out.printf("   ⇒ 最大误差：全压 %.4f° / 半压 %.4f° ⇒ UBO 里 RealPos 必须保持**真实**（%s）%n",
                maxBoth, maxMixed, maxBoth > maxMixed * 100.0 ? "已量化" : "数值不支持这个结论，需复查");
        System.out.println();

        System.out.println(fails == 0 ? "全部通过（角直径在浮点误差内逐位不变）" : ("失败 " + fails + " 项"));
        if (fails != 0) {
            System.exit(1);
        }
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }

    private static double[] norm(double[] a) {
        double l = len(a);
        return l < 1e-12 ? new double[]{0.0, 0.0, 0.0} : new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    private static double angleDeg(double[] a, double[] b) {
        double d = Math.max(-1.0, Math.min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2]));
        return Math.toDegrees(Math.acos(d));
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "[ OK ] " : "[FAIL] ") + name);
        return ok ? 0 : 1;
    }
}
