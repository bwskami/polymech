package com.mss.polymech.client.physics;

import com.mss.polymech.Polymech;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端物理世界的 tick 驱动。
 *
 * <p>刻意放在 {@link ClientTickEvent.Pre}（实体 tick 之前）：这样玩家 {@code move()} 里
 * 施加的力会在紧接着的物理步进里生效、并且读回的是最新位置。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ClientPhysicsDriver {

    private ClientPhysicsDriver() {
    }

    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event) {
        ClientPhysics.tick();
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        // 玩家 tick 之后：飞行时把玩家刚体同步到玩家位置（保留物理碰撞）
        ClientPhysics.afterPlayerTick();
    }

    @SubscribeEvent
    public static void onRenderFramePre(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
        // 每帧：把刚体位置写进玩家（相机逐帧跟随 100Hz 物理状态，避免 20Hz 采样的"一顿一顿"）
        ClientPhysics.frameWriteBack();
    }
}
