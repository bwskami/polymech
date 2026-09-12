package com.mss.polymech.space;

import com.mss.polymech.network.SpaceRotationPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * 太空朝向同步的服务端补充：
 * <p>客户端每 2 tick 发一次朝向（见 SpaceTravelMixin），服务端在收到时转播给其他玩家。
 * 但"玩家进入他人视野"这一刻需要补发一次当前朝向，否则对方要等到下一次朝向变化
 * 才会看到正确的身体姿态。</p>
 */
public final class SpaceRotationSync {

    private SpaceRotationSync() {
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getTarget() instanceof ServerPlayer tracked)) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer tracker)) {
            return;
        }
        SpacePlayerData data = SpacePlayerData.get(tracked);
        if (!data.isInitialized()) {
            return;
        }
        tracker.connection.send(SpaceRotationPayload.clientToServer(
                data.bodyFacing(), data.bodyLeft(),
                (float) data.headYaw(), (float) data.headPitch())
                .withEntityId(tracked.getId()));
    }
}
