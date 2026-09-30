package com.mss.polymech.space;

import com.mss.polymech.Polymech;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 坐标约定的**存档迁移** —— 翻 {@code identityMode} 之后，已有存档里太空维度的实体坐标
 * 仍然是**旧尺度**（1 格 = 10000 米），于是玩家停在 1.67e6 而同一颗地球在 1.67e10 ——
 * **正好差 10⁴**，人与天体彻底脱钩。
 *
 * <h2>为什么要专门做这个（而不是"让玩家自己重新传一次"）</h2>
 * 2026-09 实机日志就是证据：`玩家=(1668627.3, 1095.0, -14683521.1)` 而
 * `earth gamePos(格)=(16689358669.7, …)` —— 1.53e10÷1e4=1.53e6、−1.47e11÷1e4=−1.47e7，
 * 两个数都精确对上"旧的 ÷ZOOM"。这正是文档反复警告的"**迁一半比不迁更糟**"：
 * 坐标换了、存档里的位置没换。留着不管，用户会看到"天体在极远处"且怎么飞都到不了。
 *
 * <h2>判据（单位要对口径！）</h2>
 * <ol>
 *   <li>当前坐标**离所有天体都极远**（{@code ≥1e8} 方块）—— 正常落点总紧贴某颗天体，所以这只能是"另一套尺度"；</li>
 *   <li>按**另一套**约定换算一次之后**恰好贴住某颗天体**（且没有钻进天体内部）。</li>
 * </ol>
 * 两条配对使用，缺一条都会误伤（单看"坐标小"会把原点附近的正常坐标当成旧尺度；单看"换算后贴近"
 * 会把缩放下真实深空点 {@code ÷ZOOM} 后落进太阳里的那种假阳性算进来）。
 *
 * <h2>⚠️ 2026-09-27 的两处修正（① 缩放方案的前置）</h2>
 * <ol>
 *   <li><b>口径错配</b>：旧版 {@code nearestBodyDistance} 用的是 {@code SpaceWorld.gamePos}（**米**），
 *       却拿去和玩家的**方块坐标**比 —— 恒等约定下两者同值所以看不出来，一旦切到缩放约定，
 *       "贴着地球"会被算成 1e10 远，判据立刻反向。现在改用 {@code gamePosMc}（方块口径）。</li>
 *   <li><b>单向</b>：旧版只处理"旧 ÷ZOOM 存档 → 恒等"这一个方向。现在由 {@code identityMode}
 *       决定方向：恒等 ⇒ {@code ×ZOOM}；缩放 ⇒ {@code ÷ZOOM}。判据仍是同一个纯函数
 *       （{@link #staleScaleDirection(boolean, double, double, double, double)}），可离线复核。</li>
 * </ol>
 *
 * <h2>为什么挂"进入维度"事件</h2>
 * 那时实体已完全就位（不像 {@code EntityJoinLevelEvent} 在放置过程中），
 * 且它正好是"旧坐标要开始生效"的那一刻。搬完后由 {@code SpaceTransitionHandler} 正常接管。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class SpaceScaleMigration {

    private SpaceScaleMigration() {
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            migrate(player);
        }
    }

    /**
     * 直接登录进太空维度（存档就在太空里）**不会**触发"换维度"事件 ——
     * 玩家是在构造时直接放进存档维度的。所以登录事件也要挂一遍，
     * 否则"上次退出时人在太空"的存档会一直停在旧尺度上。
     */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            migrate(player);
        }
    }

    private static void migrate(ServerPlayer player) {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        int dir = staleScaleDirection(player.level(), x, y, z);   // +1 = 存档偏小（旧 ÷ZOOM 时代）⇒ ×ZOOM；−1 = 偏大 ⇒ ÷ZOOM
        if (dir == 0) {
            return;
        }
        double f = dir > 0 ? SpaceWorld.ZOOM : 1.0 / SpaceWorld.ZOOM;
        double nx = x * f;
        double ny = y * f;
        double nz = z * f;
        player.teleportTo(nx, ny, nz);
        Polymech.LOGGER.warn("[坐标迁移] 太空维度检测到**跨约定**坐标 ({}, {}, {})"
                        + "（当前约定={}，最近天体(方块口径) {}）⇒ {} {} 搬到 ({}, {}, {})",
                x, y, z,
                SpaceWorld.identityMode() ? "恒等(1格=1米)" : "缩放(1格=" + (long) SpaceWorld.ZOOM + "米)",
                String.format(java.util.Locale.ROOT, "%.3e", nearestBodyDistance(x, z)),
                dir > 0 ? "×" : "÷", (long) SpaceWorld.ZOOM, nx, ny, nz);
    }

    /**
     * 判据本体：**玩家与物理体共用这一个**（避免两处判据各自演化而漂移）。
     *
     * <p>两条同时成立才算"旧尺度"：</p>
     * <ol>
     *   <li>{@code max(|x|,|z|) < 3.0e7} —— 米尺度下"靠近任何天体"必然 ≥1e8，所以小坐标是旧尺度的强特征；</li>
     *   <li>到最近天体的距离 {@code ≥1.0e8} —— 正常落点总是紧贴某颗天体，所以"离所有天体都极远"只可能是旧尺度。</li>
     * </ol>
     *
     * <p>反例（为什么不能只看"坐标小"）：太空维度里<b>原点附近也有正常的小坐标区域</b>——
     * 22 日实机日志里就有一条 {@code [太空阴影] ... 实体=ItemEntity，世界坐标=(-1.33, -59.6, -0.47)}。
     * 它没有被误判，靠的正是第二条判据：<b>太阳就在原点</b>（{@code sun gamePos(格)=(0,0,0)}），
     * 到它的距离只有约 1.4 ⇒ 判定"紧贴天体"、不搬。
     * 也就是说这两条判据是<b>配对</b>的，单独任何一条都会误伤。</p>
     */
    /** 迁移方向：{@code +1} = ×ZOOM，{@code −1} = ÷ZOOM，{@code 0} = 不动。 */
    public static int staleScaleDirection(Level level, double x, double y, double z) {
        if (!SpaceWorld.isSpace(level)) {
            return 0;
        }
        double f = SpaceWorld.identityMode() ? SpaceWorld.ZOOM : 1.0 / SpaceWorld.ZOOM;
        return staleScaleDirection(SpaceWorld.identityMode(),
                nearestBodyDistance3D(x, y, z),
                nearestBodyDistance3D(x * f, y * f, z * f),
                nearestBodyRadius3D(x * f, y * f, z * f), f);
    }

    /** "离所有天体都极远"的阈值（方块口径）。 */
    public static final double FAR_FROM_EVERY_BODY = 1.0e8;

    /** "贴住天体"的判定：允许到天体中心 4 倍半径（覆盖 2.2R 的到达距离），下限 1000 格兜住小天体。 */
    public static double hugLimit(double nearestBodyRadius) {
        return Math.max(4.0 * nearestBodyRadius, 1.0e3);
    }

    /**
     * 判据的<b>纯函数部分</b>（不碰 {@code Level} 与天文数据表），便于离线复核
     * （`native/jni-smoketest/SpaceMappingProbe.java` 第 5 节就是拿它跑场景表的）。
     *
     * @param identityMode 当前是否为恒等约定（决定换算方向）
     * @param dCurrent     当前坐标到最近天体的<b>三维</b>距离（方块口径）
     * @param dCandidate   按另一套约定换算一次之后到最近天体的三维距离（方块口径）
     * @param candidateNearestRadius 换算后那颗最近天体的半径（方块口径）
     * @param factor       换算因子（{@code >1} ⇒ 方向是 ×ZOOM）
     * @return {@code +1}（×ZOOM）/ {@code −1}（÷ZOOM）/ {@code 0}（不动）
     */
    public static int staleScaleDirection(boolean identityMode, double dCurrent, double dCandidate,
                                          double candidateNearestRadius, double factor) {
        if (dCurrent < FAR_FROM_EVERY_BODY) {
            return 0;                                  // 已经贴着某颗天体 ⇒ 就是本约定的正常坐标
        }
        if (dCandidate > hugLimit(candidateNearestRadius)) {
            return 0;                                  // 换过去还在远处 ⇒ 不是尺度问题（真深空）
        }
        if (dCandidate < candidateNearestRadius) {
            return 0;                                  // 换过去钻进天体内部 ⇒ 典型假阳性（缩放下真深空点 ÷ZOOM 会落进太阳）
        }
        return factor > 1.0 ? 1 : -1;
    }

    /** 到最近天体的水平距离（**方块口径**，与玩家坐标同一约定）；判据与日志共用（日志用）。 */
    public static double nearestBodyDistance(double x, double z) {
        double nearest = Double.MAX_VALUE;
        for (RealAstroData b : RealAstroData.BODIES) {
            double[] p = SpaceWorld.gamePosMc(b);
            nearest = Math.min(nearest, Math.hypot(p[0] - x, p[2] - z));
        }
        return nearest;
    }

    /**
     * 到最近天体的**三维**距离（方块口径；用真实 Y，不压平）。
     *
     * <p>为什么判据必须用三维：<b>水平距离会把"站在星球正上方"算成 0</b> ——
     * 那正好落进"钻进天体里"那条守卫里，把最常见的"停在地球 2.2R 高处"判成假阳性。
     * 三维距离下"地面上方 2.2R"= 2.2R &gt; R，正确地落在天体外面。</p>
     */
    public static double nearestBodyDistance3D(double x, double y, double z) {
        double nearest = Double.MAX_VALUE;
        double blockPerMeter = SpaceWorld.toMc(1.0);
        for (RealAstroData b : RealAstroData.BODIES) {
            double[] p = SpaceWorld.renderPos(b);
            nearest = Math.min(nearest, Math.sqrt(
                    sq(p[0] * blockPerMeter - x) + sq(p[1] * blockPerMeter - y) + sq(p[2] * blockPerMeter - z)));
        }
        return nearest;
    }

    /** 最近天体的**方块口径**半径（挡"换算后钻进天体里"那类假阳性）；用三维距离挑最近。 */
    private static double nearestBodyRadius3D(double x, double y, double z) {
        double nearest = Double.MAX_VALUE;
        double radiusBlocks = 0.0;
        double blockPerMeter = SpaceWorld.toMc(1.0);
        for (RealAstroData b : RealAstroData.BODIES) {
            double[] p = SpaceWorld.renderPos(b);
            double d = Math.sqrt(
                    sq(p[0] * blockPerMeter - x) + sq(p[1] * blockPerMeter - y) + sq(p[2] * blockPerMeter - z));
            if (d < nearest) {
                nearest = d;
                radiusBlocks = b.radiusMeters() * blockPerMeter;
            }
        }
        return radiusBlocks;
    }

    private static double sq(double v) {
        return v * v;
    }
}
