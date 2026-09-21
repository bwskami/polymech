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

        System.out.println();
        System.out.println(fails == 0 ? "全部通过（角直径在浮点误差内逐位不变）" : ("失败 " + fails + " 项"));
        if (fails != 0) {
            System.exit(1);
        }
    }

    private static int check(String name, boolean ok) {
        System.out.println((ok ? "[ OK ] " : "[FAIL] ") + name);
        return ok ? 0 : 1;
    }
}
