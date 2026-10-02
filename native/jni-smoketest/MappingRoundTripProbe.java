/**
 * 地表⇄太空映射的<b>二维旋转对</b>可逆性探针（离线，无依赖）。
 *
 * <h2>为什么单测这 2×2 这一小块</h2>
 * 完整映射还有 asin/atan2 与自转四元数，但那几段按构造就可逆；
 * 唯一可能不可逆的是 {@code pos_shadow.rotate} 那对矩阵。所以把 2D 部分单独拎出来测。
 *
 * <h2>两套矩阵对</h2>
 * <pre>
 *   我们（照 space 0.1.3 抄的）：正向 [[c, s], [s, c]]（对称阵，不是旋转）
 *                              反向 [[c,-s], [s, c]]（真旋转）
 *   space 0.3.0（已修正）：     正向 [[c,-s], [s, c]]（真旋转）
 *                              反向 [[c, s],[-s, c]]（严格逆）
 * </pre>
 * 对称阵 M=[[c,s],[s,c]] 的行列式是 c²−s²，它的逆是 1/(c²−s²)·[[c,−s],[−s,c]]，
 * <b>不等于</b> [[c,−s],[s,c]]。所以"对称正向 + 旋转反向"只有在 c²−s²=1（即 rotate=0）时互逆。
 *
 * <p>跑法：{@code javac -encoding UTF-8 -d <临时目录> MappingRoundTripProbe.java}
 * 然后 {@code java -cp <临时目录> -Dstdout.encoding=UTF-8 MappingRoundTripProbe}</p>
 */
public final class MappingRoundTripProbe {

    private static int passed = 0;
    private static int failed = 0;

    // ---------- 我们当前的矩阵对 ----------
    static double[] oursForward(double dx, double dz, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { dx * c + dz * s, dx * s + dz * c };
    }

    static double[] oursInverse(double x0, double z0, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { x0 * c - z0 * s, x0 * s + z0 * c };
    }

    // ---------- space 0.3.0 修正后的矩阵对 ----------
    static double[] fixedForward(double dx, double dz, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { dx * c - dz * s, dx * s + dz * c };
    }

    static double[] fixedInverse(double x0, double z0, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { x0 * c + z0 * s, -x0 * s + z0 * c };
    }

    /** 往返一次的最大分量误差（相对输入尺度）。 */
    static double roundTripError(double dx, double dz, double r, boolean ours) {
        double[] f = ours ? oursForward(dx, dz, r) : fixedForward(dx, dz, r);
        double[] b = ours ? oursInverse(f[0], f[1], r) : fixedInverse(f[0], f[1], r);
        double scale = Math.max(1.0, Math.max(Math.abs(dx), Math.abs(dz)));
        return Math.max(Math.abs(b[0] - dx), Math.abs(b[1] - dz)) / scale;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  [PASS] %-46s %s%n", name, detail); }
        else    { failed++; System.out.printf("  [FAIL] %-46s %s%n", name, detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== MappingRoundTripProbe: 地表<->太空 2D 旋转对可逆性 ===");
        System.out.println();

        double[] rotates = { 0.0, 1.0e-9, 0.1, 0.5, Math.PI / 4, 1.0, Math.PI / 2, 3.0 };
        double[][] samples = { { 1.0, 0.0 }, { 0.0, 1.0 }, { 1234.5, -678.9 }, { -300.0, 250.0 } };

        System.out.println("--- A 组：rotate = 0 时两套都必须精确（说明缺陷在默认配置下不显形）---");
        for (double[] smp : samples) {
            double eOurs = roundTripError(smp[0], smp[1], 0.0, true);
            double eFix = roundTripError(smp[0], smp[1], 0.0, false);
            check(String.format("rotate=0  (%.1f, %.1f)", smp[0], smp[1]),
                    eOurs == 0.0 && eFix == 0.0,
                    String.format("我们=%.3e 修正后=%.3e", eOurs, eFix));
        }

        System.out.println();
        System.out.println("--- B 组：rotate ≠ 0 时我们的矩阵对不可逆（缺陷所在）---");
        System.out.printf("  %-10s %-14s %-14s %s%n", "rotate", "我们(最大误差)", "修正后", "比值");
        double worstOurs = 0.0;
        double worstFix = 0.0;
        for (double r : rotates) {
            double eOurs = 0.0, eFix = 0.0;
            for (double[] smp : samples) {
                eOurs = Math.max(eOurs, roundTripError(smp[0], smp[1], r, true));
                eFix = Math.max(eFix, roundTripError(smp[0], smp[1], r, false));
            }
            if (r > 0) { worstOurs = Math.max(worstOurs, eOurs); worstFix = Math.max(worstFix, eFix); }
            String ratio = eOurs == 0.0 ? "—" : (eFix == 0.0 ? "无穷" : String.format("%.3e", eOurs / Math.max(eFix, Double.MIN_NORMAL)));
            System.out.printf("  %-10.6f %-14.3e %-14.3e %s%n", r, eOurs, eFix, ratio);
        }

        System.out.println();
        check("rotate≠0 时我们的往返确实不闭合", worstOurs > 1.0e-6,
                String.format("最大相对误差=%.3e", worstOurs));
        // 注意：判据是「到机器精度」而不是「恰好 0」—— 浮点往返不可能逐位归零。
        // （第一版这里写成 == 0.0，于是 1.9e-16 被误报成 FAIL：判据的期望错了、代码是对的。）
        check("rotate≠0 时修正后的往返闭合到机器精度", worstFix < 1.0e-12,
                String.format("最大相对误差=%.3e（约 %d ulp）", worstFix, (int) Math.ceil(worstFix / 2.2e-16)));
        check("默认配置(rotate=0)下我们是对的（所以这是潜伏缺陷）", roundTripError(1234.5, -678.9, 0.0, true) == 0.0,
                "误差=0");

        System.out.println();
        System.out.println("--- C 组：与解析解对照（c²−s² 就是我们的缩放因子）---");
        for (double r : new double[] { 0.1, 0.5, Math.PI / 4, 1.0 }) {
            double c = Math.cos(r), s = Math.sin(r);
            double predicted = Math.abs(c * c - s * s);      // 我们往返后的 dx 会被乘上 c²−s²
            double dx = 1000.0;
            double[] f = oursForward(dx, 0.0, r);
            double[] b = oursInverse(f[0], f[1], r);
            double measured = Math.abs(b[0] / dx);
            check(String.format("rotate=%.3f 缩放因子 == |cos²−sin²|", r),
                    Math.abs(measured - predicted) < 1.0e-12,
                    String.format("解析=%.12f 实测=%.12f", predicted, measured));
        }

        System.out.println();
        System.out.printf("=== MappingRoundTripProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
