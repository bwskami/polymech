package com.mss.polymech.physics;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理体相关事件：补发物理体快照。
 *
 * <p><b>为什么不能只在登录时发</b>：客户端离开维度时会清空物理体注册表
 * （{@code LevelEvent.Unload} → {@code ClientPhysicsWorld.clear()}）。
 * 如果只在加入时补发，换维度再回来就会"物理体全消失"，重进游戏才恢复 ——
 * 走的正是加入那条路径。space/MPS 是靠玩家 tick 驱动的世界更新事件持续下发，
 * 我们这里做等价的补发。</p>
 *
 * <p><b>时序</b>：服务端的换维度事件比客户端"卸载旧关卡"更早，
 * 那一刻发的包可能刚到就被客户端的 {@code clear()} 冲掉，所以换维度后
 * 再延迟补发一次（2 秒后）。</p>
 */
public final class PhysicsBodyEvents {

    /** 换维度后延迟补发的时刻（tickCount）。 */
    private static final Map<UUID, Integer> RESEND_AT = new ConcurrentHashMap<>();

    private PhysicsBodyEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PhysicsBodyTracker.sendAllTo(player);
        }
    }

    /** 换维度：清掉旧维度的玩家刚体，并安排一次延迟补发。 */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerPlayerPhysics.forget(player);
            PhysicsBodyInteraction.forget(player); // 挖掘进度也要丢，否则换维度回来裂纹还在
            RESEND_AT.put(player.getUUID(), player.tickCount + 40);
            PhysicsBodyTracker.sendAllTo(player);
        }
    }

    /** 玩家 tick：到点后补发该维度的全部物理体（自愈，能盖住上面的时序问题）。 */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        Integer at = RESEND_AT.get(player.getUUID());
        if (at != null && player.tickCount >= at) {
            RESEND_AT.remove(player.getUUID());
            PhysicsBodyTracker.sendAllTo(player);
        }
    }

    /** 下线时清理该玩家的服务端物理体，避免残留。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ServerPlayerPhysics.forget(player);
            PhysicsBodyInteraction.forget(player);
            PhysicsBodyTracker.forgetAcks(player); // 可靠握手的待确认表一并清掉
            RESEND_AT.remove(player.getUUID());
        }
    }
}
