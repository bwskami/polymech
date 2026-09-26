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
 * <h2>判据（两条同时成立才搬，避免误伤正常位置）</h2>
 * <ol>
 *   <li>{@code max(|x|,|z|) < 3.0e7} —— 米尺度下"靠近任何天体"必然 ≥1e8，所以这是旧尺度的强特征；</li>
 *   <li>到**最近天体**的距离 {@code ≥1.0e8} —— 正常落点总是紧贴某颗天体（传送目标就是天体坐标），
 *       所以"离所有天体都极远"只可能是旧尺度。</li>
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
        if (!looksLikeLegacyScale(player.level(), x, z)) {
            return;
        }
        double nx = x * SpaceWorld.ZOOM;
        double ny = y * SpaceWorld.ZOOM;
        double nz = z * SpaceWorld.ZOOM;
        player.teleportTo(nx, ny, nz);
        Polymech.LOGGER.warn("[坐标迁移] 太空维度检测到 ZOOM 尺度旧坐标 ({}, {}, {})（最近天体 {}) ⇒ ×{} 搬到 ({}, {}, {})",
                x, y, z, String.format(java.util.Locale.ROOT, "%.3e", nearestBodyDistance(x, z)),
                (long) SpaceWorld.ZOOM, nx, ny, nz);
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
    public static boolean looksLikeLegacyScale(Level level, double x, double z) {
        return looksLikeLegacyScale(SpaceWorld.identityMode(), SpaceWorld.isSpace(level), x, z,
                nearestBodyDistance(x, z));
    }

    /**
     * 判据的<b>纯函数部分</b>（不碰 {@code Level} 与天文数据表），便于离线复核与将来做回归。
     *
     * <p>注意"验算 ≠ 回归"：离线只能验算这几条阈值的取值方向，
     * 真正接上游戏的那条路径（{@link Level} / {@code RealAstroData} / 存档读写）仍必须实机确认。</p>
     *
     * @param identityMode 当前是否为恒等约定（{@code false} 说明还是旧 ZOOM 约定，存档本来就是旧尺度）
     * @param spaceDim     该坐标所在维度是否为太空世界
     * @param nearestBodyDistance 到最近天体（含原点处的太阳）的水平距离
     */
    public static boolean looksLikeLegacyScale(boolean identityMode, boolean spaceDim,
                                               double x, double z, double nearestBodyDistance) {
        if (!identityMode || !spaceDim) {
            return false;
        }
        if (Math.max(Math.abs(x), Math.abs(z)) >= 3.0e7) {
            return false; // 已经在米尺度（远离原点）
        }
        return nearestBodyDistance >= 1.0e8;
    }

    /** 到最近天体的水平距离（米），供判据与日志共用。 */
    public static double nearestBodyDistance(double x, double z) {
        double nearest = Double.MAX_VALUE;
        for (RealAstroData b : RealAstroData.BODIES) {
            double[] p = SpaceWorld.gamePos(b);
            nearest = Math.min(nearest, Math.hypot(p[0] - x, p[2] - z));
        }
        return nearest;
    }
}
