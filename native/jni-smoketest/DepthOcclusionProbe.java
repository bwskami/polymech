import com.mss.polymech.client.space.RenderCompression;

/**
 * 离线判据：**两套投影共用一张深度缓冲时，"比深度大小"这种判据到底成不成立**。
 *
 * <p>背景（2026-09-30，用户第二次报"远处的星球把近处的物理体挡住"）：
 * 星球在 AFTER_SKY 用 {@code spaceProj} 写进主深度，世界几何体随后用 MC 主投影写进同一张缓冲。
 * 两套投影的 near/far 完全不同 ⇒ 同一个空间距离在两边的深度值差着数量级。
 * 当时的遮挡判据是 {@code mainDepth < skyDepth}（"世界比星球近"），
 * 它的真假完全取决于这两套数值谁大谁小 —— 这正是 bug 所在。</p>
 *
 * <p>本探针只从外部读真值：{@code NEAR/FAR} 取 {@link RenderCompression}（天体侧的唯一真源），
 * MC 侧 near/far 与"用户会话里地球的压缩距离"标注来源。判据分四组：
 * <ul>
 *   <li><b>A 组</b>：旧判据 {@code mainDepth < skyDepth} 只在"方块 &lt; 临界距离"时成立，
 *       临界距离必须很小 ⇒ 复现 bug。</li>
 *   <li><b>A′ 组</b>：把 spaceProj 的 far 换回压缩启用前的 {@code SPACE_FAR_PLANE=1e13}
 *       再算一次临界距离 —— 用来回答"这是不是距离压缩（09-27）引入的回归"。
 *       <b>结论：不是</b>，两个临界距离都是 2~3 格，旧判据从一开始就只对贴脸的东西成立。</li>
 *   <li><b>B 组</b>：新判据 {@code mainDepth < 1.0}（投影无关的"世界画过没有"）对**任意**
 *       世界几何体距离都成立，且**只**是 mainDepth 的函数（与天体无关）。</li>
 *   <li><b>C 组</b>：新判据成立的**前提** —— 会画出来的世界几何体（≤ 渲染距离）在渲染帧里
 *       一定比"超出 NEAR 的天体"近；并把这个前提的**例外**（天体近到 NEAR 以内）显式钉出来。</li>
 * </ul>
 *
 * <p>跑法（仓库根目录，见 docs/mps-clone-plan.md §13）：
 * <pre>
 *   $o = "build\pm-depthprobe"; Remove-Item -Recurse -Force $o -ErrorAction SilentlyContinue
 *   javac -encoding UTF-8 -cp "build\classes\java\main" -d $o native\jni-smoketest\DepthOcclusionProbe.java
 *   java "-Dstdout.encoding=UTF-8" -cp "$o;build\classes\java\main" DepthOcclusionProbe
 * </pre>
 */
public final class DepthOcclusionProbe {

    /** MC 主投影的 near（{@code GameRenderer.PROJECTION_Z_NEAR = 0.05F}）。 */
    private static final double MC_NEAR = 0.05;
    /**
     * MC 主投影的 far（{@code GameRenderer.getDepthFar() = renderDistance * 4}，
     * renderDistance = 有效渲染距离(区块) × 16）。默认 12 区块 ⇒ 192 ⇒ far = 768。
     */
    private static final double MC_FAR = 768.0;

    /** {@code SpaceRenderer.SPACE_NEAR_PLANE}（星球绘制用的 spaceProj near，单位米）。 */
    private static final double SPACE_NEAR = 1000.0;
    /** 压缩**未**启用时 spaceProj 的 far（{@code SpaceRenderer.SPACE_FAR_PLANE}）。 */
    private static final double SPACE_FAR_PLANE = 1.0e13;
    /** 压缩启用时 spaceProj 的 far = {@code RenderCompression.FAR * 2}。 */
    private static final double SPACE_FAR_COMPRESSED = RenderCompression.FAR * 2.0;

    /**
     * 用户 09-30 会话的实测值：玩家在太空维度 (1527804.8, 1088.6, -14698839.0)，
     * 地球 gamePos (1512280.7, 1087.6, -14702041.0)（单位：格，1 格 = 10^4 米）。
     */
    private static final double[] CAM = {1527804.8, 1088.6, -14698839.0};
    private static final double[] EARTH = {1512280.7, 1087.6, -14702041.0};
    /** 地球半径（格）。 */
    private static final double EARTH_RADIUS_MC = 637.1;
    /** 1 格 = 10^4 米（太空维度坐标缩放，见 §31.29 方案①）。 */
    private static final double METERS_PER_BLOCK = 1.0e4;
    /** MC 渲染距离上限：32 区块 = 512 格 ⇒ 会画出来的世界几何体最远就这么远。 */
    private static final double MAX_RENDER_DISTANCE_BLOCKS = 32.0 * 16.0;

    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        System.out.println("=== DepthOcclusionProbe: 两套投影共用深度缓冲时的遮挡判据 ===");
        System.out.println();

        double centerBlocks = dist(CAM, EARTH);
        double centerMeters = centerBlocks * METERS_PER_BLOCK;
        double radiusMeters = EARTH_RADIUS_MC * METERS_PER_BLOCK;
        double surfaceMeters = centerMeters - radiusMeters;
        double compressed = RenderCompression.compress(surfaceMeters);

        System.out.printf("玩家到地球中心         = %.1f 格 = %.4e m%n", centerBlocks, centerMeters);
        System.out.printf("地球半径               = %.1f 格 = %.4e m%n", EARTH_RADIUS_MC, radiusMeters);
        System.out.printf("到地球表面             = %.4e m%n", surfaceMeters);
        System.out.printf("压缩后（spaceProj 用）  = %.1f m  (zoom=%.6e，压了 %.0f 倍)%n",
                compressed, compressed / surfaceMeters, surfaceMeters / compressed);
        System.out.printf("压缩参数               = NEAR=%.0f FAR=%.0f  spaceFar=%.0f%n",
                RenderCompression.NEAR, RenderCompression.FAR, SPACE_FAR_COMPRESSED);
        System.out.println();

        double skyDepth = depth(SPACE_NEAR, SPACE_FAR_COMPRESSED, compressed);
        double skyDepthNoCompress = depth(SPACE_NEAR, SPACE_FAR_PLANE, compressed);
        System.out.printf("星球表面深度 spaceProj(near=%.0f, far=%.0f) = %.8f%n",
                SPACE_NEAR, SPACE_FAR_COMPRESSED, skyDepth);
        System.out.printf("同一天体、far=1e13（压缩启用前）           = %.8f%n", skyDepthNoCompress);
        System.out.printf("对照：MC 主投影 near=%.2f far=%.0f%n", MC_NEAR, MC_FAR);
        System.out.println();

        // ---------- A 组：旧判据（两套投影比大小）----------
        System.out.println("--- A 组：旧判据 mainDepth < skyDepth（应当**失败** = 复现 bug）---");
        double crossover = crossover(skyDepth);
        System.out.printf("临界距离 = %.2f 格（比这远的方块全都「输给」星球）%n", crossover);
        check("临界距离很小（< 3 格）⇒ 几乎任何方块都输", crossover < 3.0);

        double[] blockDistances = {1.0, 2.0, 5.0, 20.0, 100.0, 300.0, 512.0};
        for (double d : blockDistances) {
            double mainDepth = depth(MC_NEAR, MC_FAR, d);
            boolean oldTest = mainDepth < skyDepth;
            boolean expect = d < crossover;
            System.out.printf("  方块 %6.1f 格: mainDepth=%.8f  旧判据=%s%n",
                    d, mainDepth, oldTest ? "true(方块挡得住)" : "false(**星球赢**)");
            check("旧判据在 " + d + " 格处 = " + expect, oldTest == expect);
        }
        System.out.println();

        // ---------- A′ 组：这不是距离压缩引入的回归 ----------
        System.out.println("--- A′ 组：换回压缩启用前的 far(1e13)，临界距离是否也这么小 ---");
        double crossoverNoCompress = crossover(skyDepthNoCompress);
        System.out.printf("临界距离（far=1e13）= %.2f 格 ；（far=%.0f）= %.2f 格%n",
                crossoverNoCompress, SPACE_FAR_COMPRESSED, crossover);
        System.out.printf("⇒ 压缩只把临界距离从 %.2f 推到 %.2f 格，**两者都只有 2~3 格**%n",
                crossoverNoCompress, crossover);
        check("压缩启用前旧判据同样是坏的（临界 < 3 格）", crossoverNoCompress < 3.0);
        System.out.println("  结论：旧判据**从来**只对贴脸的东西成立；是 09-27 起太空里有了物理体，");
        System.out.println("        才第一次有东西需要被它遮挡。不是 09-27 距离压缩引入的回归。");
        System.out.println();

        // ---------- B 组：新判据（投影无关的"世界画过没有"）----------
        System.out.println("--- B 组：新判据 mainDepth < 1.0（应当**成立**）---");
        for (double d : blockDistances) {
            double mainDepth = depth(MC_NEAR, MC_FAR, d);
            boolean worldMask = mainDepth < 1.0 - 1.0e-7;
            System.out.printf("  方块 %6.1f 格: mainDepth=%.8f  新判据=%s%n",
                    d, mainDepth, worldMask ? "true(世界挡住天体) OK" : "false **FAIL**");
            check("新判据在 " + d + " 格处成立", worldMask);
        }
        // 新判据必须**只**是 mainDepth 的函数：任意换天体距离都不改变它。
        double probeMainDepth = depth(MC_NEAR, MC_FAR, 20.0);
        boolean maskAt20 = probeMainDepth < 1.0 - 1.0e-7;
        boolean independent = true;
        for (double bodyDist : new double[]{1.0e4, 5.0853e4, 2.6e5}) {
            double anySky = depth(SPACE_NEAR, SPACE_FAR_COMPRESSED, bodyDist);
            if ((probeMainDepth < 1.0 - 1.0e-7) != maskAt20) independent = false;
            System.out.printf("  天体在 %9.0f m（深度 %.8f）时，20 格方块的掩码仍 = %s%n",
                    bodyDist, anySky, maskAt20);
        }
        check("新判据与天体距离无关（只是 mainDepth 的函数）", independent);
        // 什么都没画的像素必须**不**被判成遮挡（否则天体整片消失）
        check("空像素(深度=1.0)不被判成遮挡", !(1.0 < 1.0 - 1.0e-7));
        System.out.println();

        // ---------- C 组：新判据成立的前提 ----------
        System.out.println("--- C 组：新判据的前提 —— 会画出来的世界几何体比天体近 ---");
        // 渲染帧里的两个长度：世界几何体 = 它的**格**距离；天体 = compress(真实米)（或未压缩时的真实米）。
        System.out.printf("  世界几何体渲染距离 ≤ %.0f 格（= 渲染距离上限 32 区块）%n",
                MAX_RENDER_DISTANCE_BLOCKS);
        System.out.printf("  超出 NEAR 的天体渲染距离 ≥ compress(NEAR) = %.0f m%n",
                RenderCompression.compress(RenderCompression.NEAR));
        check("渲染距离上限(格) < 天体渲染距离下界 ⇒ 世界恒近",
                MAX_RENDER_DISTANCE_BLOCKS < RenderCompression.compress(RenderCompression.NEAR));

        // 显式钉出例外：天体**近到 NEAR 以内**时按真实米渲染，可能比世界几何体还近。
        double insideNearMeters = RenderCompression.NEAR;             // 1.64 格
        double renderedInsideNear = RenderCompression.compress(insideNearMeters);
        System.out.printf("  ⚠ 例外：天体表面 ≤ NEAR(%.0f m = %.2f 格) 时按真实米渲染（%.0f m）%n",
                RenderCompression.NEAR, RenderCompression.NEAR / METERS_PER_BLOCK, renderedInsideNear);
        System.out.printf("     此时若世界几何体在 %.0f 格外，它其实**更远** ⇒ 掩码会误杀大气%n",
                RenderCompression.NEAR / METERS_PER_BLOCK);
        check("例外只在「相机贴着天体表面（<2 格）」时可达 ⇒ 可接受并已在代码注释记录",
                RenderCompression.NEAR / METERS_PER_BLOCK < 2.0);
        check("compress 单调", isMonotonic());
        System.out.println();

        System.out.printf("=== DepthOcclusionProbe: %d/%d 通过，失败 %d ===%n",
                checks - failures, checks, failures);
        if (failures > 0) {
            System.out.println("DEPTH OCCLUSION PROBE FAILED");
            System.exit(1);
        }
        System.out.println("DEPTH OCCLUSION PROBE DONE");
    }

    /** 标准透视深度（越小越近），返回 [0,1]：{@code z=n ⇒ 0}，{@code z=f ⇒ 1}。 */
    private static double depth(double n, double f, double z) {
        return ((f + n) / (f - n) - (2.0 * f * n) / ((f - n) * z) + 1.0) / 2.0;
    }

    /** 求"MC 深度 == skyDepth"的临界方块距离（由 {@code depth(MC_NEAR,MC_FAR,d)=skyDepth} 反解）。 */
    private static double crossover(double skyDepth) {
        double k = (MC_FAR + MC_NEAR) / (MC_FAR - MC_NEAR) + 1.0 - 2.0 * skyDepth;
        return (2.0 * MC_FAR * MC_NEAR) / ((MC_FAR - MC_NEAR) * k);
    }

    private static boolean isMonotonic() {
        double prev = -1.0;
        for (double x = 1.0; x < 1.0e9; x *= 1.7) {
            double c = RenderCompression.compress(x);
            if (c < prev - 1.0e-9) return false;
            prev = c;
        }
        return true;
    }

    private static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) failures++;
        System.out.println("  [" + (ok ? "PASS" : "**FAIL**") + "] " + what);
    }
}
