package com.mss.polymech.space;

import com.mss.polymech.Polymech;
import net.minecraft.server.level.ServerPlayer;
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
        if (!SpaceWorld.identityMode()) {
            return; // 旧约定下存档本来就是旧尺度，不需要搬
        }
        if (!SpaceWorld.isSpace(player.level())) {
            return;
        }
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        if (Math.max(Math.abs(x), Math.abs(z)) >= 3.0e7) {
            return; // 已经在米尺度（远离原点）
        }
        double nearest = Double.MAX_VALUE;
        for (RealAstroData b : RealAstroData.BODIES) {
            double[] p = SpaceWorld.gamePos(b);
            nearest = Math.min(nearest, Math.hypot(p[0] - x, p[2] - z));
        }
        if (nearest < 1.0e8) {
            return; // 本来就贴着某颗天体 ⇒ 是正常落点，别动它
        }
        double nx = x * SpaceWorld.ZOOM;
        double ny = y * SpaceWorld.ZOOM;
        double nz = z * SpaceWorld.ZOOM;
        player.teleportTo(nx, ny, nz);
        Polymech.LOGGER.warn("[坐标迁移] 太空维度检测到 ZOOM 尺度旧坐标 ({}, {}, {})（最近天体 {}) ⇒ ×{} 搬到 ({}, {}, {})",
                x, y, z, String.format(java.util.Locale.ROOT, "%.3e", nearest),
                (long) SpaceWorld.ZOOM, nx, ny, nz);
    }
}
