/**
 * 地表⇄太空映射的<b>二维旋转对</b>可逆性探针（离线，无依赖）。
 *
 * <h2>为什么单测这 2×2 这一小块</h2>
 * 完整映射还有 asin/atan2 与自转四元数，但那几段按构造就可逆；
 * 唯一可能不可逆的是 {@code pos_shadow.rotate} 那对矩阵。所以把 2D 部分单独拎出来测。
 *
 * <h2>两套矩阵对</h2>
 * <pre>
 *   旧（照 space 0.1.3 抄的）：正向 [[c, s], [s, c]]（对称阵，**不是**旋转）
 *                             反向 [[c,-s], [s, c]]（真旋转）
 *   现在用的（space 0.3.0 的形式）：正向 [[c,-s], [s, c]]（真旋转 R）
 *                                 反向 [[c, s],[-s, c]]（严格逆 R⁻¹）
 * </pre>
 * 对称阵 M=[[c,s],[s,c]] 的行列式是 c²−s²，它的逆是 1/(c²−s²)·[[c,−s],[−s,c]]，
 * <b>不等于</b> [[c,−s],[s,c]]。所以"对称正向 + 旋转反向"只有在 c²−s²=1（即 rotate=0）时互逆。
 *
 * <p><b>本探针的定位</b>：B/C 组把旧缺陷钉死（说明"为什么要改"），
 * D 组是**回归闸门** —— 断言我们现在用的那一对在任意 rotate 下都可逆、且是正交阵。
 * 2026-09-30 起 {@code CelestialWorld} 已改用下面这一对（{@code EarthSpaceMapping} 无需改：
 * 它把地球硬编码成无自转，rotate 恒为 0，两向本来就互逆）。</p>
 *
 * <p>跑法：{@code javac -encoding UTF-8 -d <临时目录> MappingRoundTripProbe.java}
 * 然后 {@code java -cp <临时目录> -Dstdout.encoding=UTF-8 MappingRoundTripProbe}</p>
 */
public final class MappingRoundTripProbe {

    private static int passed = 0;
    private static int failed = 0;

    // ---------- 旧矩阵对（0.1.3，缺陷所在；保留作反例） ----------
    static double[] legacyForward(double dx, double dz, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { dx * c + dz * s, dx * s + dz * c };
    }

    static double[] legacyInverse(double x0, double z0, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { x0 * c - z0 * s, x0 * s + z0 * c };
    }

    // ---------- 现在用的矩阵对（space 0.3.0 形式；= CelestialWorld 的当前实现） ----------
    static double[] oursForward(double dx, double dz, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { dx * c - dz * s, dx * s + dz * c };
    }

    static double[] oursInverse(double x0, double z0, double r) {
        double c = Math.cos(r), s = Math.sin(r);
        return new double[] { x0 * c + z0 * s, -x0 * s + z0 * c };
    }

    /** 往返一次的最大分量误差（相对输入尺度）。 */
    static double roundTripError(double dx, double dz, double r, boolean legacy) {
        double[] f = legacy ? legacyForward(dx, dz, r) : oursForward(dx, dz, r);
        double[] b = legacy ? legacyInverse(f[0], f[1], r) : oursInverse(f[0], f[1], r);
        double scale = Math.max(1.0, Math.max(Math.abs(dx), Math.abs(dz)));
        return Math.max(Math.abs(b[0] - dx), Math.abs(b[1] - dz)) / scale;
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  [PASS] %-48s %s%n", name, detail); }
        else    { failed++; System.out.printf("  [FAIL] %-48s %s%n", name, detail); }
    }

    public static void main(String[] args) {
        System.out.println("=== MappingRoundTripProbe: 地表<->太空 2D 旋转对可逆性 ===");
        System.out.println();

        double[] rotates = { 0.0, 1.0e-9, 0.1, 0.5, Math.PI / 4, 1.0, Math.PI / 2, 3.0, -0.7, -Math.PI / 3 };
        double[][] samples = { { 1.0, 0.0 }, { 0.0, 1.0 }, { 1234.5, -678.9 }, { -300.0, 250.0 } };

        System.out.println("--- A 组：rotate = 0 时两套都精确（⇒ 旧缺陷在默认配置下不显形，是潜伏的）---");
        for (double[] smp : samples) {
            double eLegacy = roundTripError(smp[0], smp[1], 0.0, true);
            double eOurs = roundTripError(smp[0], smp[1], 0.0, false);
            check(String.format("rotate=0  (%.1f, %.1f)", smp[0], smp[1]),
                    eLegacy == 0.0 && eOurs == 0.0,
                    String.format("旧=%.3e 现在=%.3e", eLegacy, eOurs));
        }

        System.out.println();
        System.out.println("--- B 组：rotate ≠ 0 时旧矩阵对不闭合，现在这一对闭合（这就是改动理由）---");
        System.out.printf("  %-12s %-16s %-16s %s%n", "rotate", "旧(最大误差)", "现在(最大误差)", "比值");
        double worstLegacy = 0.0;
        double worstOurs = 0.0;
        for (double r : rotates) {
            double eLegacy = 0.0, eOurs = 0.0;
            for (double[] smp : samples) {
                eLegacy = Math.max(eLegacy, roundTripError(smp[0], smp[1], r, true));
                eOurs = Math.max(eOurs, roundTripError(smp[0], smp[1], r, false));
            }
            if (r != 0.0) { worstLegacy = Math.max(worstLegacy, eLegacy); worstOurs = Math.max(worstOurs, eOurs); }
            String ratio = eLegacy == 0.0 ? "—"
                    : (eOurs == 0.0 ? "无穷" : String.format("%.3e", eLegacy / Math.max(eOurs, Double.MIN_NORMAL)));
            System.out.printf("  %-12.6f %-16.3e %-16.3e %s%n", r, eLegacy, eOurs, ratio);
        }

        System.out.println();
        check("rotate≠0 时旧矩阵对确实不闭合", worstLegacy > 1.0e-6,
                String.format("最大相对误差=%.3e", worstLegacy));
        check("rotate≠0 时现在这一对闭合到机器精度", worstOurs < 1.0e-12,
                String.format("最大相对误差=%.3e（约 %d ulp）", worstOurs, (int) Math.ceil(worstOurs / 2.2e-16)));
        check("默认配置(rotate=0)下旧写法也是对的（所以是潜伏缺陷）",
                roundTripError(1234.5, -678.9, 0.0, true) == 0.0, "误差=0");

        System.out.println();
        System.out.println("--- C 组：旧缺陷的数学形式（往返后 dx 被乘上 |cos²−sin²|）---");
        for (double r : new double[] { 0.1, 0.5, Math.PI / 4, 1.0 }) {
            double c = Math.cos(r), s = Math.sin(r);
            double predicted = Math.abs(c * c - s * s);
            double dx = 1000.0;
            double[] f = legacyForward(dx, 0.0, r);
            double[] b = legacyInverse(f[0], f[1], r);
            double measured = Math.abs(b[0] / dx);
            check(String.format("rotate=%.3f 旧缩放因子 == |cos²−sin²|", r),
                    Math.abs(measured - predicted) < 1.0e-12,
                    String.format("解析=%.12f 实测=%.12f", predicted, measured));
        }

        System.out.println();
        System.out.println("--- D 组（回归闸门）：现在这一对必须是正交阵、行列式 +1、任意 rotate 可逆 ---");
        for (double r : rotates) {
            double c = Math.cos(r), s = Math.sin(r);
            // 正向矩阵按行存：[a b; c d] = [[c,-s],[s,c]]
            double a = c, b = -s, cc = s, d = c;
            double det = a * d - b * cc;
            // 正交性：RᵀR = I ⇒ 列向量单位正交
            double col1 = a * a + cc * cc;
            double col2 = b * b + d * d;
            double dot = a * b + cc * d;
            check(String.format("rotate=%-12.6f det=+1 且列正交", r),
                    Math.abs(det - 1.0) < 1.0e-12
                            && Math.abs(col1 - 1.0) < 1.0e-12
                            && Math.abs(col2 - 1.0) < 1.0e-12
                            && Math.abs(dot) < 1.0e-12,
                    String.format("det=%.12f |c1|²=%.12f |c2|²=%.12f c1·c2=%.2e", det, col1, col2, dot));
        }
        // 旧写法必须是**非**正交的，否则说明 C/B 组的反例失效
        {
            double r = 0.5, c = Math.cos(r), s = Math.sin(r);
            double a = c, b = s, cc = s, d = c;
            double dot = a * b + cc * d;
            check("旧写法的列不满足正交（反例有效）", Math.abs(dot) > 0.1,
                    String.format("rotate=0.5 时 c1·c2=%.6f（应为 2cs=%.6f）", dot, 2 * c * s));
        }

        System.out.println();
        System.out.printf("=== MappingRoundTripProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
