package com.mss.polymech.mixin;

import com.mss.polymech.client.space.ClientSpaceTransition;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 无缝切换时，取消原版“加载地形”画面的渲染。
 */
@Mixin(ReceivingLevelScreen.class)
public class ReceivingLevelScreenMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void space$cancelReceivingLevelRender(GuiGraphics graphics, int mouseX, int mouseY,
                                                  float partialTick, CallbackInfo ci) {
        if (ClientSpaceTransition.isActive()) {
            ci.cancel();
        }
    }
}
