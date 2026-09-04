package com.mss.polymech.mixin;

import com.mss.polymech.client.space.ClientSpaceTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 无缝切换时，在 respawn/login 完成后把目标位置和朝向应用到 LocalPlayer。
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerSpaceMixin {

    @Inject(method = "handleLogin", at = @At("RETURN"))
    private void space$applyAfterLogin(ClientboundLoginPacket packet, CallbackInfo ci) {
        ClientSpaceTransition.apply(Minecraft.getInstance().player);
    }

    @Inject(method = "handleRespawn", at = @At("RETURN"))
    private void space$applyAfterRespawn(ClientboundRespawnPacket packet, CallbackInfo ci) {
        ClientSpaceTransition.apply(Minecraft.getInstance().player);
    }
}
