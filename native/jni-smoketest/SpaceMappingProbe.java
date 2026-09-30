import com.mss.polymech.space.EarthSpaceMapping;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.SpaceScaleMigration;
import com.mss.polymech.space.SpaceWorld;

/**
 * 地表 ⇄ 太空 映射的<b>离线</b>往返闭环探针（不需要开游戏）。
 *
 * <h2>它回答什么</h2>
 * <ol>
 *   <li><b>角向往返是否逐位闭合</b>：{@code spaceToWorld(worldToSpace(x,y,z))} 的 x/z
 *       是否回到原值。§31.28 把"回程"列为<b>从来没走过</b>，但映射的逆变换是纯函数 ——
 *       这一半根本不需要实机，实机只负责验"集成"（2 tick 延迟、落点 surfaceY、速度朝向）。</li>
 *   <li><b>卡门线捕获壳反算回多少格</b>：捕获半径 = R + 卡门线 − 1 时，
 *       {@code spaceToWorld} 给出的 y 必须<b>小于</b> 10000，否则"下去又被吸回太空"
 *       （回程 ping-pong，§31.28 第 5 条）。</li>
 *   <li>升空到达距离 2.2R 反算回多少格（只是让对方程的 y 有数，不是判据）。</li>
 *   <li>地表维度的<b>实际比例</b>：1 格 = 多少米（水平/竖直各一个数）——
 *       两侧尺度不同是后面所有"缩放方案"的代价来源。</li>
 * </ol>
 *
 * <h2>怎么跑</h2>
 * <pre>
 *   javac -encoding UTF-8 -cp "build\classes\java\main" -d &lt;临时目录&gt; native\jni-smoketest\SpaceMappingProbe.java
 *   java -Dstdout.encoding=UTF-8 -cp "&lt;临时目录&gt;;build\classes\java\main" SpaceMappingProbe
 * </pre>
 * 只依赖 {@code com.mss.polymech.space.*} 里那几个纯数学类；{@code kelvinAuthority}
 * 默认关，所以 {@code SpaceWorld.blockPos} 走静态值（与游戏内"天体位置随时间漂移"无关：
 * 往返闭环对地球位置是平移不变的）。
 *
 * <p>退出码：0 = 全部判据 PASS；1 = 有 FAIL。</p>
 */
public final class SpaceMappingProbe {

    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("================ 地表 ⇄ 太空 映射离线往返探针 ================");
        System.out.println("约定           : " + (SpaceWorld.identityMode() ? "恒等(1格=1米)"
                : "缩放(1格=" + (long) SpaceWorld.ZOOM + "米，照抄 space 0.0.6 的 position_zoom)"));
        RealAstroData earth = RealAstroData.EARTH;
        double R = earth.radiusMeters();
        double carmen = earth.carmenLineHeightMeters();
        System.out.printf("地球 R=%.6e m  卡门线=%.6e m%n", R, carmen);
        System.out.println("静态地球位置(米) : " + fmt(SpaceWorld.blockPos(earth)));
        System.out.println("地表→太空 到达距离倍率 = 2.2 × R（EarthSpaceMapping.ARRIVAL_RADIUS_FACTOR）");
        System.out.println();

        // ---------- 1. 角向往返闭环 ----------
        double y = 10000.3125;                       // 与实机日志同一高度
        double[] spots = {0.5, -9.5, 12345.5, -43210.25, 49999.5, -49999.5, 25000.0, -1.5};
        double maxDx = 0.0;
        double maxDz = 0.0;
        System.out.println("--- 1) 角向往返：worldToSpace → spaceToWorld 的 (x,z) 闭合 ---");
        for (double sx : spots) {
            for (double sz : spots) {
                double[] up = EarthSpaceMapping.worldToSpace(sx, y, sz, 0.0);
                double[] back = EarthSpaceMapping.spaceToWorld(up[0], up[1], up[2], 0.0);
                maxDx = Math.max(maxDx, Math.abs(back[0] - sx));
                maxDz = Math.max(maxDz, Math.abs(back[2] - sz));
            }
        }
        System.out.printf("   81 个 (x,z) 组合：max|Δx|=%.3e 格  max|Δz|=%.3e 格%n", maxDx, maxDz);
        check("角向往返闭合 < 1e-6 格", maxDx < 1e-6 && maxDz < 1e-6);
        System.out.println();

        // ---------- 2. 捕获壳反算高度 ----------
        System.out.println("--- 2) 卡门线捕获壳 → 地表维度 y（判据：必须 < 10000，否则回程 ping-pong）---");
        double rCapture = R + carmen - 1.0;
        double[] shellPt = radial(rCapture, 0.0, 0.0);
        double[] shellWorld = EarthSpaceMapping.spaceToWorld(
                earthX(shellPt, 0), earthY(shellPt, 0), earthZ(shellPt, 0), 0.0);
        System.out.printf("   r = R+卡门线−1 = %.6e m  ⇒  落点 y = %.3f 格%n", rCapture, shellWorld[1]);
        check("捕获壳反算 y < 10000（留出余量 > 0）", shellWorld[1] < 10000.0);
        System.out.printf("   余量 = %.1f 格（越小越容易「落地即被再吸走」）%n", 10000.0 - shellWorld[1]);
        System.out.println();

        // ---------- 3. 到达距离 2.2R 反算 ----------
        System.out.println("--- 3) 升空到达距离 2.2R 反算回地表 y（只是让对方程的数有出处）---");
        double[] arrival = radial(2.2 * R, 0.0, 0.0);
        double[] arrivalWorld = EarthSpaceMapping.spaceToWorld(
                earthX(arrival, 0), earthY(arrival, 0), earthZ(arrival, 0), 0.0);
        System.out.printf("   r = 2.2R = %.6e m  ⇒  若直接反算，y = %.3f 格（远高于 10000）%n", 2.2 * R, arrivalWorld[1]);
        System.out.println("   ⇒ 说明「到达距离」与「落点还原」本来就不同口径：回程靠的是卡门线捕获，不是把到达点反算。");
        System.out.println();

        // ---------- 4. 地表维度的实际比例 ----------
        System.out.println("--- 4) 地表维度映射的实际比例（两侧尺度不同 = 所有缩放方案的代价来源）---");
        double lonLen = 100000.0;   // EarthSpaceMapping.LONGITUDE_LENGTH
        double height = 10000.0;    // HEIGHT
        double minY = -64.0;        // MIN_Y
        double quarterCircumference = Math.PI * R / 2.0;
        double horizontalMetersPerBlock = quarterCircumference / (lonLen / 2.0);
        double verticalMetersPerBlock = carmen / (height - minY);
        System.out.printf("   水平: 1 格 = %.1f m（%.0f 格 = 四分之一周长 %.3e m）%n",
                horizontalMetersPerBlock, lonLen / 2.0, quarterCircumference);
        System.out.printf("   竖直: 1 格 = %.1f m（%.0f 格 = 卡门线 %.3e m）%n",
                verticalMetersPerBlock, height - minY, carmen);
        System.out.printf("   ⇒ 地表维度本身就不是等比的：水平/竖直 = %.1f 倍%n",
                horizontalMetersPerBlock / verticalMetersPerBlock);
        System.out.println();

        // ---------- 5. 存档迁移判据的场景表（纯函数，离线就能判） ----------
        System.out.println("--- 5) 跨约定存档迁移判据（SpaceScaleMigration.staleScaleDirection 纯函数）---");
        System.out.println("   约定: 恒等 = 1 格 1 米；缩放 = 1 格 " + (long) SpaceWorld.ZOOM
                + " 米。方向 +1 = ×ZOOM，−1 = ÷ZOOM，0 = 不动");
        double rEarthId = R;                                  // 恒等帧里地球的方块半径 = 米
        double rEarthSc = R / SpaceWorld.ZOOM;                // 缩放帧里 = 637.1 格
        double rSunSc = 6.96e8 / SpaceWorld.ZOOM;             // 缩放帧里太阳 69600 格
        double twoTwoRId = 2.2 * R;                           // 恒等帧 2.2R = 1.4016e7 m
        double twoTwoRSc = 2.2 * R / SpaceWorld.ZOOM;         // 缩放帧 2.2R = 1401.6 格
        expect("恒等帧 + 旧÷ZOOM 存档（停在地球 2.2R 高处）⇒ 应当 ×ZOOM",
                1, SpaceScaleMigration.staleScaleDirection(true, 1.53e10, twoTwoRId, rEarthId, SpaceWorld.ZOOM));
        expect("恒等帧 + 正常坐标（就在地球旁 2.2R）⇒ 不动",
                0, SpaceScaleMigration.staleScaleDirection(true, twoTwoRId, 1.4e11, rEarthId, SpaceWorld.ZOOM));
        expect("缩放帧 + 恒等存档（2.2R/ZOOM）⇒ 应当 ÷ZOOM",
                -1, SpaceScaleMigration.staleScaleDirection(false, 1.53e10, twoTwoRSc, rEarthSc, 1.0 / SpaceWorld.ZOOM));
        expect("缩放帧 + 真实深空（÷ZOOM 后落进太阳内部）⇒ 不动（防假阳性）",
                0, SpaceScaleMigration.staleScaleDirection(false, 2.0e8, 2.0e4, rSunSc, 1.0 / SpaceWorld.ZOOM));
        expect("恒等帧 + 真实深空（×ZOOM 后更远）⇒ 不动",
                0, SpaceScaleMigration.staleScaleDirection(true, 2.0e8, 2.0e12, rEarthId, SpaceWorld.ZOOM));
        expect("缩放帧 + 正常坐标（贴着地球 2.2R）⇒ 不动",
                0, SpaceScaleMigration.staleScaleDirection(false, twoTwoRSc, 1.4e7, rEarthSc, 1.0 / SpaceWorld.ZOOM));
        System.out.println();

        // ---------- 6. 缩放约定（① 方案）下同一批数的表现 ----------
        System.out.println("--- 6) 缩放约定下的关键数（S2 翻 identityMode 之后世界长什么样）---");
        boolean savedMode = SpaceWorld.identityMode();
        // ★ 先钉死三个访问器的**契约**（名字与口径必须一一对应，否则恒等约定会把混用掩盖掉）：
        //   blockPos / renderPos = **米**（天文坐标系，与方块约定无关）
        //   gamePosMc            = **格**（= toMc(blockPos)，随 identityMode 变）
        SpaceWorld.setIdentityMode(true);
        double[] earthIdMeters = SpaceWorld.blockPos(earth);
        double[] earthIdMc = SpaceWorld.gamePosMc(earth);
        SpaceWorld.setIdentityMode(false);
        double[] earthScMeters = SpaceWorld.blockPos(earth);
        double[] earthScMc = SpaceWorld.gamePosMc(earth);
        double blockPerMeter = SpaceWorld.toMc(1.0);
        double limit = SpaceWorld.DEEP_SPACE_LIMIT;
        System.out.printf("   地球: blockPos(米) 恒等=(%.3e) vs 缩放=(%.3e)  ← **不该变**（米口径）%n",
                earthIdMeters[0], earthScMeters[0]);
        System.out.printf("   地球: gamePosMc(格) 恒等=(%.3e) vs 缩放=(%.3e, %.3e, %.3e)%n",
                earthIdMc[0], earthScMc[0], earthScMc[1], earthScMc[2]);
        System.out.printf("   地球方块半径: 恒等=%.1f 格 vs 缩放=%.1f 格；地月距离: %.3e 格%n",
                R, R * blockPerMeter, 3.844e8 * blockPerMeter);
        System.out.printf("   地球每 tick 位移: %.1f 格（= 30151 m/s × 71.8033 × 0.05 s ÷ %d）%n",
                30151.0 * 71.8033 * 0.05 * blockPerMeter, (long) SpaceWorld.ZOOM);
        check("blockPos 的契约是**米**（缩放约定下不变）",
                Math.abs(earthIdMeters[0] - earthScMeters[0]) < 1.0);
        check("gamePosMc 随约定变：缩放后落进 BlockPos 26 位上限内（深空分支应当消失）",
                Math.abs(earthScMc[0]) < limit && Math.abs(earthScMc[2]) < limit);
        check("gamePosMc 恒等约定下**超出**上限（这正是今天每次升空都打『深空』的原因）",
                Math.abs(earthIdMc[0]) > limit);
        // 往返闭环在两种约定下都必须闭合（映射本身用米算，与方块约定无关）
        double[] upSc = EarthSpaceMapping.worldToSpace(12345.5, y, -43210.25, 0.0);
        double[] backSc = EarthSpaceMapping.spaceToWorld(upSc[0], upSc[1], upSc[2], 0.0);
        check("缩放约定下往返仍闭合（|Δx|,|Δz| < 1e-6 格）",
                Math.abs(backSc[0] - 12345.5) < 1e-6 && Math.abs(backSc[2] + 43210.25) < 1e-6);
        double[] shellSc = radial(rCapture, 0.0, 0.0);
        double[] shellWorldSc = EarthSpaceMapping.spaceToWorld(
                earthX(shellSc, 0), earthY(shellSc, 0), earthZ(shellSc, 0), 0.0);
        check("缩放约定下捕获壳反算 y 仍是 9935.9（< 10000）", Math.abs(shellWorldSc[1] - 9935.9) < 0.2);
        SpaceWorld.setIdentityMode(savedMode);
        System.out.println("   （已把 identityMode 还原成探针启动时的值: " + savedMode + "）");
        System.out.println();

        // ---------- 7. [坐标自检] 的闸门为什么会漂（谁动了静态位置表） ----------
        System.out.println("--- 7) 静态 blockPos 校验和：闸门 vs 实测，差在哪颗天体上 ---");
        final long gateOld = -5410990681030L;   // 历史权威值（S1–S3 重构期基线）
        final long gateNow = SpaceWorld.STATIC_CHECKSUM_GATE;
        String[] ids = new String[RealAstroData.BODIES.size()];
        long[] contrib = new long[RealAstroData.BODIES.size()];
        long sum = 0L;
        for (int i = 0; i < RealAstroData.BODIES.size(); i++) {
            RealAstroData b = RealAstroData.BODIES.get(i);
            ids[i] = b.id();
            double[] st = staticGamePos(b);
            contrib[i] = (long) Math.floor(st[0] / 10.0 + 0.5)
                    + 2L * (long) Math.floor(st[1] / 10.0 + 0.5)
                    + 3L * (long) Math.floor(st[2] / 10.0 + 0.5);
            sum += contrib[i];
        }
        long delta = sum - gateOld;
        System.out.printf("   历史闸门=%d  现行闸门=%d  实测=%d  Δ(实测−历史)=%d  天体数=%d%n",
                gateOld, gateNow, sum, delta, ids.length);
        check("现行闸门 == 实测（[坐标自检] 现在会真的比较，不再静默）", sum == gateNow);
        System.out.println("   Δ 是否**恰好等于某 1 颗**天体的贡献（⇒ 它是在定基线之后加进来的）：");
        boolean found1 = false;
        for (int i = 0; i < ids.length; i++) {
            if (contrib[i] == delta) {
                System.out.printf("     ★命中: %s 贡献=%d%n", ids[i], contrib[i]);
                found1 = true;
            }
        }
        if (!found1) {
            System.out.println("     未命中单颗；查**两颗之差**（⇒ 某一颗的位置被改过）：");
            for (int i = 0; i < ids.length && !found1; i++) {
                for (int j = 0; j < ids.length; j++) {
                    if (i != j && contrib[i] - contrib[j] == delta) {
                        System.out.printf("     ★命中: %s(%d) − %s(%d) = %d%n",
                                ids[i], contrib[i], ids[j], contrib[j], delta);
                        found1 = true;
                    }
                }
            }
        }
        if (!found1) {
            System.out.println("     仍未命中 —— 说明是**多处位置同时改过**。");
            System.out.println("     已查明根因：提交 a23ac6e（\"天体太空\"）改了卫星轨道参数化");
            System.out.println("     （相位/轴向互换 cos,sin → sin,cos + 加入轨道倾角分量）⇒ 多颗卫星同时移动；");
            System.out.println("     故此项**按有意改动处理**：2026-09-27 已把闸门重定为实测值（SpaceWorld.STATIC_CHECKSUM_GATE）。");
        }
        System.out.println("   全部天体贡献（绝对值最大的前 8 个）：");
        Integer[] order = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, c) -> Long.compare(Math.abs(contrib[c]), Math.abs(contrib[a])));
        for (int k = 0; k < Math.min(8, order.length); k++) {
            int i = order[k];
            System.out.printf("     %-12s %d%n", ids[i], contrib[i]);
        }
        System.out.println();

        System.out.println(failed == 0 ? "==== 全部判据 PASS ====" : "==== 有 " + failed + " 项 FAIL ====");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** 与 SpaceWorld.staticGamePos 同形（探针内重算，用来定位"谁改过"）。 */
    private static double[] staticGamePos(RealAstroData b) {
        if (b == RealAstroData.EARTH) {
            return new double[]{b.posX(), b.posY(), b.posZ()};
        }
        RealAstroData parent = RealAstroData.parentOf(b);
        if (parent != null) {
            double[] pg = staticGamePos(parent);
            return new double[]{pg[0] + (b.posX() - parent.posX()), pg[1],
                    pg[2] + (b.posZ() - parent.posZ())};
        }
        return new double[]{b.posX(), 0.0, b.posZ()};
    }

    private static void expect(String what, int want, int got) {
        boolean ok = want == got;
        System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + what
                + "（期望 " + show(want) + "，实得 " + show(got) + "）");
        if (!ok) {
            failed++;
        }
    }

    private static String show(int v) {
        return v > 0 ? "+1" : Integer.toString(v);
    }

    /** 把"离地心 r、经度 lon、纬度 lat"的宇宙系点平移到地球位置上。 */
    private static double[] radial(double r, double lon, double lat) {
        double cosLat = Math.cos(lat);
        return new double[]{cosLat * Math.cos(lon) * r, Math.sin(lat) * r, cosLat * Math.sin(lon) * r};
    }

    private static double earthX(double[] local, double seconds) {
        return local[0] + SpaceWorld.blockPos(RealAstroData.EARTH)[0];
    }

    private static double earthY(double[] local, double seconds) {
        return local[1] + SpaceWorld.blockPos(RealAstroData.EARTH)[1];
    }

    private static double earthZ(double[] local, double seconds) {
        return local[2] + SpaceWorld.blockPos(RealAstroData.EARTH)[2];
    }

    private static String fmt(double[] v) {
        return String.format(java.util.Locale.ROOT, "(%.6e, %.6e, %.6e)", v[0], v[1], v[2]);
    }

    private static void check(String what, boolean ok) {
        System.out.println("   [" + (ok ? "PASS" : "FAIL") + "] " + what);
        if (!ok) {
            failed++;
        }
    }
}
