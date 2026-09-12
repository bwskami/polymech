package com.mss.polymech.mixin.space;

import com.mss.polymech.network.SmoothTeleportPosPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 对应 space 0.1.0 的 {@code ...space.mixin.common.entity.MixinServerPlayer}。
 *
 * <p>在 {@code teleportTo(ServerLevel, DDD, FF)} 里、{@code changeDimension} 之后，
 * 把落点发给该玩家 —— 客户端换维度时会重建 LocalPlayer，有了这个落点才能一次性
 * 把位置与插值状态对齐（见 {@code space.MixinClientPacketListener}），避免镜头抖动。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class MixinServerPlayer {

    @Inject(
            method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDFF)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerPlayer;changeDimension(Lnet/minecraft/world/level/portal/DimensionTransition;)Lnet/minecraft/world/entity/Entity;",
                    shift = At.Shift.AFTER)
    )
    private void polymech$sendNewPos(ServerLevel level, double x, double y, double z,
                                     float yRot, float xRot, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        PacketDistributor.sendToPlayer(self, new SmoothTeleportPosPacket(x, y, z));
    }
}
