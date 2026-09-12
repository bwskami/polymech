package com.mss.polymech.mixin.space;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mss.polymech.network.SmoothTeleportPosPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 对应 space 0.1.0 的 {@code sunshine.mixin.network.MixinClientPacketListener}：
 * 换维度/传送导致的 LocalPlayer 重建时，把"服务端落点 + 旧玩家插值状态"一次性对齐，
 * 避免镜头抖一下。
 *
 * <p>两步（与 space 相同）：</p>
 * <ol>
 *   <li>阻止原版把 {@code Minecraft.cameraEntity} 指向新玩家（否则相机会先跳到旧位置）；</li>
 *   <li>在 {@code LocalPlayer.setId} 之后，把 {@link SmoothTeleportPosPacket} 收到的落点写进新玩家，
 *       并从旧玩家搬 xo/yo/zo、xOld…、yRotO、bob、walkDist、cloak 等插值字段与飞行开关。</li>
 * </ol>
 *
 * <p>没有收到落点包时（普通重生）完全保持原版行为。</p>
 */
@Mixin(ClientPacketListener.class)
public class MixinClientPacketListener {

    @WrapWithCondition(
            method = "handleRespawn",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/Minecraft;cameraEntity:Lnet/minecraft/world/entity/Entity;",
                    opcode = org.objectweb.asm.Opcodes.PUTFIELD,
                    ordinal = 0)
    )
    private boolean polymech$keepCameraEntity(Minecraft instance, Entity value) {
        return !SmoothTeleportPosPacket.hasPos();
    }

    @Inject(
            method = "handleRespawn",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;setId(I)V",
                    shift = At.Shift.AFTER)
    )
    private void polymech$applyNewPos(ClientboundRespawnPacket packet, CallbackInfo ci,
                                      @Local(ordinal = 0) LocalPlayer oldPlayer,
                                      @Local(ordinal = 1) LocalPlayer newPlayer) {
        if (!SmoothTeleportPosPacket.hasPos()) {
            return;
        }
        double[] p = SmoothTeleportPosPacket.pos();
        newPlayer.setPos(p[0], p[1], p[2]);
        newPlayer.xo = p[0];
        newPlayer.yo = p[1];
        newPlayer.zo = p[2];
        newPlayer.xOld = p[0];
        newPlayer.yOld = p[1];
        newPlayer.zOld = p[2];

        newPlayer.setXRot(oldPlayer.getXRot());
        newPlayer.setYRot(oldPlayer.getYRot());
        newPlayer.setYBodyRot(oldPlayer.getYRot());
        newPlayer.setYHeadRot(oldPlayer.getYHeadRot());
        newPlayer.xRotO = oldPlayer.getXRot();
        newPlayer.yRotO = oldPlayer.getYRot();
        newPlayer.yBodyRotO = oldPlayer.getYRot();
        newPlayer.yHeadRotO = oldPlayer.getYRot();

        newPlayer.xBob = oldPlayer.xBob;
        newPlayer.yBob = oldPlayer.yBob;
        newPlayer.xBobO = oldPlayer.xBobO;
        newPlayer.yBobO = oldPlayer.yBobO;
        newPlayer.walkDist = oldPlayer.walkDist;
        newPlayer.walkDistO = oldPlayer.walkDistO;
        newPlayer.xCloak = oldPlayer.xCloak;
        newPlayer.yCloak = oldPlayer.yCloak;
        newPlayer.zCloak = oldPlayer.zCloak;
        newPlayer.xCloakO = oldPlayer.xCloakO;
        newPlayer.yCloakO = oldPlayer.yCloakO;
        newPlayer.zCloakO = oldPlayer.zCloakO;

        Abilities oldAbilities = oldPlayer.getAbilities();
        Abilities newAbilities = newPlayer.getAbilities();
        newAbilities.flying = oldAbilities.flying;
    }
}
