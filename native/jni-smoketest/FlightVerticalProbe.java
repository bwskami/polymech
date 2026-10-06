import com.mss.polymech.space.SpaceFlight;
import com.mss.polymech.space.SpacePlayerData;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>创造飞行的竖直</b>：按空格/Shift 之后，速度到底朝哪个世界方向。
 *
 * <p>用户 2026-10-03 报"创造飞行头朝下（滚转 180°，屏幕的'上'= 世界下）时按空格往<b>屏幕下</b>飞"。
 * 本探针把"该往哪飞"变成可程序判定的量，并且<b>必须能 FAIL</b> —— 对照组就是改动前的原版结构。</p>
 *
 * <h2>判据的推导（不依赖"读代码觉得对"）</h2>
 * 稳态速度方向由"冲量的基底"和"阻尼的基底"共同决定：每 tick 做
 * {@code v ← D(v + 冲量)}，若 {@code D} 在某个基底下是对角的，
 * 则固定点也落在<b>同一个基</b>下，方向 = 冲量方向。于是：
 * <pre>
 *   原版：冲量在**视线基底**（我们的 moveRelative 注入），阻尼在**世界轴**（x,z×0.91 / y×0.6）
 *         ⇒ 两个基不同 ⇒ 稳态方向偏，偏多少取决于姿态（水平姿态下两基重合，所以平时看不出来）；
 *         而且 y 通道还被 {@code Player.travel} 的 {@code d2*0.6} 整条覆盖 ⇒ 竖直输入直接丢。
 *   现在：冲量与阻尼都在**视线基底**（{@link SpaceFlight}）
 *         ⇒ 稳态方向 = 输入方向；水平姿态下与原版等价（差只在 float 精度内 ~1e-7）。
 * </pre>
 * 所以判据分别钉：
 * <ol>
 *   <li><b>中性</b>：水平姿态（pitch=0, roll=0，任意 yaw）下，新实现与原版<b>一致到 float 精度</b>
 *       （基底是 float 四元数 ⇒ 分解/合成各一次，相对误差约 float epsilon，容差 1e-6）
 *       —— 证明这不是"顺手改了手感"；</li>
 *   <li><b>方向</b>：任意姿态下，只按空格 ⇒ 速度沿<b>屏幕上</b>；只按 A ⇒ 沿<b>屏幕左</b>；</li>
 *   <li><b>量级</b>：稳态 = <b>阻尼×冲量/(1−阻尼)</b>（冲量自己也吃阻尼）⇒ 竖直
 *       {@code 0.6/0.4 = 1.5} 倍、水平 {@code 0.91/0.09 = 10.11} 倍，与原版同数。
 *       ⚠️ <b>别写成 {@code 冲量/(1−阻尼)}（= 2.5 / 11.11）</b> —— 那是 {@code v ← D·v + 冲量}
 *       的解，差一个阻尼因子。本探针第一版就是这么算错的，于是 D/E 两组把<b>正确</b>的实现
 *       报成 FAIL（"判据与真实现不一致时，先怀疑判据" —— 这次又是判据错）。
 *       容差取 <b>1e-4</b>：稳态增益 {@code 1/(1−0.91)=11.11} 把基底误差放大约 11.7 倍，
 *       而基底是 float（实测 |屏幕左| 偏 1.2e-7）⇒ 稳态相对误差 ~4e-6（实测最坏 4.019e-06）。
 *       第一版取 1e-6 是判据太紧，不是实现错 —— <b>又一次"先怀疑判据"</b>。</li>
 * </ol>
 *
 * <h2>为什么必须调真实现</h2>
 * 本探针<b>不</b>复刻冲量/阻尼公式：它调 {@link SpaceFlight#impulse} 与
 * {@link SpaceFlight#damp}（{@code SpaceFlightMixin} 用的就是这两个），
 * 基底来自 {@link SpacePlayerData#viewFrame}（{@code SpaceInputRotationMixin} 与相机自检用的也是它）。
 * 唯一被"复刻"的是 {@link #steadyVanilla} —— 它是<b>被反驳的对象</b>（原版结构），
 * 不是判据的来源。判据的方向轴（屏幕上/屏幕左）来自真 {@code viewFrame}。
 * 本项目为此栽过 5 次死判据（复刻对复刻必然自洽），见 {@code docs/space-input-basis.md} §四。</p>
 *
 * <p>跑法：需要<b>全量 classpath</b>（{@link SpacePlayerData} 要解析 MC 类），
 * 见 {@code run-offline-checks.ps1} 第 22 步的 @argfile。</p>
 */
public final class FlightVerticalProbe {

    private static int checks = 0;
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        checks++;
        if (ok) {
            passed++;
            System.out.println("[PASS] " + name + (detail.isEmpty() ? "" : "   " + detail));
        } else {
            failed++;
            System.out.println("[FAIL] " + name + "   " + detail);
        }
    }

    // ==================== 两个模型 ====================

    /**
     * <b>新实现</b>的稳态速度：调 {@link SpaceFlight} 的真方法，逐 tick 迭代到收敛。
     * 与 {@code SpaceFlightMixin} 的每 tick 递推同形：{@code v ← damp(v + impulse)}。
     */
    static Vector3d steadyNew(Quaternionf frame, float xxa, float zza, float ascend,
                              float base, boolean sprint) {
        Vector3d v = new Vector3d();
        Vector3d tmp = new Vector3d();
        for (int i = 0; i < 400; i++) {
            SpaceFlight.impulse(frame, xxa, zza, ascend, base, sprint, tmp);
            v.add(tmp);
            SpaceFlight.damp(frame, v.x, v.y, v.z, tmp);
            v.set(tmp);
        }
        return v;
    }

    /**
     * <b>原版（= 改动前的当前实现）</b>的稳态速度 —— 本探针的<b>对照组 / 被反驳的对象</b>。
     *
     * <p>照 1.21.1 的真实链子复刻，四步：</p>
     * <pre>
     *   ① LocalPlayer.aiStep  : delta.y += ascend · flySpeed · 3      （沿**世界 +Y**）
     *   ② Player.travel       : d2 = delta.y                          （读于 super.travel **之前**）
     *   ③ super.travel        : moveRelative 加视线基底的水平冲量；然后 (x,z)×0.91、y×0.98
     *   ④ Player.travel       : delta.y = d2 × 0.6                    （★ y 通道被整条覆盖）
     * </pre>
     * ④ 就是"竖直输入加进去就被丢掉"（③ 里那份冲量的 y 分量进不了 ④），
     * ① 就是"飞行的竖直其实沿世界 +Y"。
     */
    static Vector3d steadyVanilla(Quaternionf frame, float xxa, float zza, float ascend,
                                  float base, boolean sprint) {
        Vector3d v = new Vector3d();
        Vector3d imp = new Vector3d();
        for (int i = 0; i < 400; i++) {
            // ① 世界 +Y 的竖直冲量
            v.y += ascend * base * SpaceFlight.ASCEND_IMPULSE;
            // ② 原版在这里读 d2
            double d2 = v.y;
            // ③ moveRelative：视线基底的水平冲量（竖直分量随后被丢）
            SpaceFlight.impulse(frame, xxa, zza, 0.0F, base, sprint, imp);
            v.add(imp);
            v.x *= SpaceFlight.HORIZONTAL_DAMP;
            v.z *= SpaceFlight.HORIZONTAL_DAMP;
            v.y *= 0.98;
            // ④ y 被覆盖
            v.y = d2 * SpaceFlight.UP_DAMP;
        }
        return v;
    }

    // ==================== 小工具 ====================

    static Vector3d unit(Vector3d v) {
        double len = v.length();
        return len < 1.0e-12 ? new Vector3d() : new Vector3d(v).div(len);
    }

    static double maxDiff(Vector3d a, Vector3d b) {
        return Math.max(Math.abs(a.x - b.x), Math.max(Math.abs(a.y - b.y), Math.abs(a.z - b.z)));
    }

    static String fmt(Vector3d v) {
        return String.format(java.util.Locale.ROOT, "(%+.4f, %+.4f, %+.4f)", v.x, v.y, v.z);
    }

    /** 建一个真 {@link SpacePlayerData} 并摆到给定姿态。 */
    static Quaternionf frameFor(double yaw, double pitch, double roll) {
        SpacePlayerData data = new SpacePlayerData();
        data.initFromVanilla((float) yaw, (float) pitch);
        data.rollBody(Math.toRadians(roll));
        return data.viewFrame(new Quaternionf());
    }

    // ==================== main ====================

    public static void main(String[] args) {
        float base;
        try {
            base = new net.minecraft.world.entity.player.Abilities().getFlyingSpeed();
        } catch (Throwable t) {
            System.out.println("★ 无法离线加载 net.minecraft.world.entity.player.Abilities：" + t);
            System.out.println("  本探针要拿**原版的飞行速度**当真值，否则又是复刻对复刻。");
            System.out.println("  请用全量 classpath 重跑（见 run-offline-checks.ps1 第 22 步的 @argfile）。");
            System.exit(2);
            return;
        }
        System.out.println("飞行速度来源：net.minecraft.world.entity.player.Abilities（**真原版类**）"
                + " getFlyingSpeed() = " + base);
        System.out.println("阻尼来源：SpaceFlight.HORIZONTAL_DAMP=" + SpaceFlight.HORIZONTAL_DAMP
                + " UP_DAMP=" + SpaceFlight.UP_DAMP
                + " ASCEND_IMPULSE=" + SpaceFlight.ASCEND_IMPULSE);

        double[] yaws = {0, 45, 90, 135, 180, -90, -135};
        double[] pitches = {-90, -60, -30, 0, 30, 60, 90};
        double[] rolls = {0, 30, 60, 90, 135, 180, -135, -90, -60, -30};

        // ── A 中性：水平姿态下必须与原版一致（任意 yaw；水平基底的旋转不改变阻尼分解）──
        //    容差 1e-6 而不是 1e-12：基底来自 SpacePlayerData.viewFrame 的 **float** 四元数，
        //    分解到基底再合成回来各一次 ⇒ 相对误差约 float epsilon（实测最坏 1.4e-7）。
        //    这是"照 space 0.3.0 用 Vector3f"的真实代价，物理上可忽略（~1e-7 格/tick）。
        double worstNeutral = 0.0;
        String worstNeutralAt = "";
        for (double yaw : yaws) {
            Quaternionf f = frameFor(yaw, 0, 0);
            Vector3d nUp = steadyNew(f, 0, 0, 1.0F, base, false);
            Vector3d vUp = steadyVanilla(f, 0, 0, 1.0F, base, false);
            Vector3d nLeft = steadyNew(f, 1.0F, 0, 0, base, false);
            Vector3d vLeft = steadyVanilla(f, 1.0F, 0, 0, base, false);
            double d = Math.max(maxDiff(nUp, vUp), maxDiff(nLeft, vLeft));
            if (d > worstNeutral) {
                worstNeutral = d;
                worstNeutralAt = String.format(java.util.Locale.ROOT, "yaw=%.0f", yaw);
            }
        }
        check("A 中性：水平姿态下新实现 == 原版（float 精度内）", worstNeutral < 1.0e-6,
                String.format(java.util.Locale.ROOT, "最大分量差 %.3e（%s）", worstNeutral, worstNeutralAt));

        // ── B/C 方向 + 量级：遍历全部姿态 ──
        int poses = 0;
        int dirUpOk = 0;
        int dirLeftOk = 0;
        int magUpOk = 0;
        int magLeftOk = 0;
        double worstUpDot = 2.0;
        double worstLeftDot = 2.0;
        String worstUpAt = "";
        String worstLeftAt = "";
        // E 组的诊断（量出来，别猜）：最坏相对偏差 + 那个姿态的基底几何
        double worstMagLeftRel = 0.0;
        String worstMagLeftAt = "";
        double worstMagLeftVal = 0.0;
        double worstMagLeftBasisLen = 0.0;
        double worstMagLeftBasisDot = 0.0;
        double worstMagLeftQuatLen = 0.0;
        // 稳态 = 阻尼 × 冲量 / (1 − 阻尼)：冲量也吃一次阻尼（v ← D(v + 冲量)）。
        double expectUp = SpaceFlight.UP_DAMP * base * SpaceFlight.ASCEND_IMPULSE
                / (1.0 - SpaceFlight.UP_DAMP);
        double expectLeft = SpaceFlight.HORIZONTAL_DAMP * base / (1.0 - SpaceFlight.HORIZONTAL_DAMP);

        // 对照组（被反驳的原版结构）统计
        int vanillaUpWrong = 0;
        double worstVanillaUpDot = 2.0;
        String worstVanillaUpAt = "";
        Vector3d worstVanillaUpVec = new Vector3d();
        Vector3d worstVanillaUpAxis = new Vector3d();

        for (double yaw : yaws) {
            for (double pitch : pitches) {
                for (double roll : rolls) {
                    poses++;
                    Quaternionf f = frameFor(yaw, pitch, roll);
                    Vector3f left = f.transform(new Vector3f(1.0F, 0.0F, 0.0F));
                    Vector3f up = f.transform(new Vector3f(0.0F, 1.0F, 0.0F));

                    // 只按空格（ascend = +1）
                    Vector3d nUp = steadyNew(f, 0, 0, 1.0F, base, false);
                    double dotUp = unit(nUp).dot(up.x, up.y, up.z);
                    if (dotUp > 0.999) {
                        dirUpOk++;
                    }
                    // 不论过没过都记最小值，否则"全过"时会打印一个没意义的初值 2.0。
                    if (dotUp < worstUpDot) {
                        worstUpDot = dotUp;
                        worstUpAt = String.format(java.util.Locale.ROOT,
                                "yaw=%.0f pitch=%.0f roll=%.0f", yaw, pitch, roll);
                    }
                    // 容差 1e-4：稳态增益 1/(1−0.91)=11.11 ⇒ 对基底误差的敏感度约 11.7 倍，
                    // 而基底是 float（实测 |屏幕左| 偏离 1 约 1.2e-7）⇒ 稳态相对误差 ~4e-6
                    // （实测最坏 4.019e-06）。所以 1e-6 是**判据自己太紧**，不是实现错。
                    // 任何真正的行为改动都是 O(1) 量级（比如把阻尼换回世界轴），1e-4 一样能抓住。
                    if (Math.abs(nUp.length() - expectUp) < expectUp * 1.0e-4) {
                        magUpOk++;
                    }

                    // 只按 A（xxa = 1）
                    Vector3d nLeft = steadyNew(f, 1.0F, 0, 0, base, false);
                    double dotLeft = unit(nLeft).dot(left.x, left.y, left.z);
                    if (dotLeft > 0.999) {
                        dirLeftOk++;
                    }
                    if (dotLeft < worstLeftDot) {
                        worstLeftDot = dotLeft;
                        worstLeftAt = String.format(java.util.Locale.ROOT,
                                "yaw=%.0f pitch=%.0f roll=%.0f", yaw, pitch, roll);
                    }
                    if (Math.abs(nLeft.length() - expectLeft) < expectLeft * 1.0e-4) {
                        magLeftOk++;
                    }
                    double leftRel = Math.abs(nLeft.length() - expectLeft) / expectLeft;
                    if (leftRel > worstMagLeftRel) {
                        Vector3f view = f.transform(new Vector3f(0.0F, 0.0F, 1.0F));
                        worstMagLeftRel = leftRel;
                        worstMagLeftVal = nLeft.length();
                        worstMagLeftAt = String.format(java.util.Locale.ROOT,
                                "yaw=%.0f pitch=%.0f roll=%.0f", yaw, pitch, roll);
                        worstMagLeftBasisLen = left.length();
                        worstMagLeftBasisDot = left.dot(up.x, up.y, up.z);
                        worstMagLeftQuatLen = Math.sqrt(f.x * f.x + f.y * f.y + f.z * f.z + f.w * f.w);
                    }

                    // 对照组：同一姿态下原版结构的竖直方向
                    Vector3d vUp = steadyVanilla(f, 0, 0, 1.0F, base, false);
                    double vDot = unit(vUp).dot(up.x, up.y, up.z);
                    if (vDot < 0.999) {
                        vanillaUpWrong++;
                    }
                    if (vDot < worstVanillaUpDot) {
                        worstVanillaUpDot = vDot;
                        worstVanillaUpAt = String.format(java.util.Locale.ROOT,
                                "yaw=%.0f pitch=%.0f roll=%.0f", yaw, pitch, roll);
                        worstVanillaUpVec.set(vUp);
                        worstVanillaUpAxis.set(up.x, up.y, up.z);
                    }
                }
            }
        }

        check("B 方向：只按空格 ⇒ 速度沿【屏幕上】（全部姿态）", dirUpOk == poses,
                String.format(java.util.Locale.ROOT, "%d/%d 姿态；最坏 dot=%+.6f（%s）",
                        dirUpOk, poses, worstUpDot, worstUpAt));
        check("C 方向：只按 A ⇒ 速度沿【屏幕左】（全部姿态）", dirLeftOk == poses,
                String.format(java.util.Locale.ROOT, "%d/%d 姿态；最坏 dot=%+.6f（%s）",
                        dirLeftOk, poses, worstLeftDot, worstLeftAt));
        check("D 量级：空格稳态 = 0.6·冲量/(1−0.6)（照原版数）", magUpOk == poses,
                String.format(java.util.Locale.ROOT, "%d/%d 姿态；期望 |v|=%.6f", magUpOk, poses, expectUp));
        check("E 量级：A 稳态 = 0.91·冲量/(1−0.91)（照原版数）", magLeftOk == poses,
                String.format(java.util.Locale.ROOT, "%d/%d 姿态；期望 |v|=%.6f", magLeftOk, poses, expectLeft));
        System.out.printf(java.util.Locale.ROOT,
                "  [诊断] E 最坏：%s |v|=%.9f（期望 %.9f，相对偏差 %.3e）"
                        + " |屏幕左|=%.9f 左·上=%.3e |q|=%.9f%n",
                worstMagLeftAt, worstMagLeftVal, expectLeft, worstMagLeftRel,
                worstMagLeftBasisLen, worstMagLeftBasisDot, worstMagLeftQuatLen);

        // ── F 对照组：原版结构在多数姿态下方向是错的（判据必须有区分力）──
        check("F 对照：原版结构（世界轴阻尼）在多数姿态下方向错", vanillaUpWrong > poses / 2,
                String.format(java.util.Locale.ROOT,
                        "%d/%d 姿态方向错；最坏 dot=%+.6f（%s） 原版速度=%s 屏幕上=%s",
                        vanillaUpWrong, poses, worstVanillaUpDot, worstVanillaUpAt,
                        fmt(worstVanillaUpVec), fmt(worstVanillaUpAxis)));

        // ── G 用户报的那个姿态：滚转 180°（屏幕的"上"= 世界下）按空格 ──
        System.out.println();
        System.out.println("—— 用户报的姿态：创造飞行 + 滚转 180°（倒过来）按空格 ——");
        double[][] reported = {
                {0, 0, 180},
                {0, 30, 180},
                {45, -60, 180},
                {90, 0, 180},
        };
        int reportedOk = 0;
        for (double[] att : reported) {
            Quaternionf f = frameFor(att[0], att[1], att[2]);
            Vector3f up = f.transform(new Vector3f(0.0F, 1.0F, 0.0F));
            Vector3d nUp = steadyNew(f, 0, 0, 1.0F, base, false);
            Vector3d vUp = steadyVanilla(f, 0, 0, 1.0F, base, false);
            double newDot = unit(nUp).dot(up.x, up.y, up.z);
            double oldDot = unit(vUp).dot(up.x, up.y, up.z);
            System.out.printf(java.util.Locale.ROOT,
                    "  yaw=%.0f pitch=%.0f roll=%.0f : 屏幕上=%s | 原版速度=%s dot=%+.3f | 新速度=%s dot=%+.3f%n",
                    att[0], att[1], att[2], fmt(new Vector3d(up.x, up.y, up.z)),
                    fmt(vUp), oldDot, fmt(nUp), newDot);
            // 原版必须明显偏（dot 远离 +1），新实现必须精确沿屏幕上。
            if (oldDot < 0.9 && newDot > 0.999) {
                reportedOk++;
            }
        }
        check("G 报告姿态：原版偏、新实现精确沿屏幕上", reportedOk == reported.length,
                reportedOk + "/" + reported.length + " 个姿态成立");

        System.out.println();
        System.out.printf(java.util.Locale.ROOT,
                "=== FlightVerticalProbe: %d/%d 通过，失败 %d ===%n", passed, checks, failed);
        if (failed > 0) {
            System.out.println("⇒ '按空格往哪飞'与'屏幕上的方向'不一致。检查 SpaceFlight 的基底是否");
            System.out.println("   仍取自 SpacePlayerData.viewFrame，以及阻尼是否仍按基底逐轴做");
            System.out.println("   （世界轴阻尼 = 原版结构 = 就是用户报的'往屏幕下飞'）。");
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    private FlightVerticalProbe() {
    }
}
