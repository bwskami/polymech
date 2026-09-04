package com.mss.polymech.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 窗口最小化时跳过主菜单全景图渲染，避免部分 GPU 驱动在后台上传全景图顶点时卡死。
 */
@Mixin(Screen.class)
public abstract class PanoramaSkipWhenIconifiedMixin {

    @Inject(method = "renderPanorama", at = @At("HEAD"), cancellable = true)
    private void polymech$skipPanoramaWhenIconified(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        long window = Minecraft.getInstance().getWindow().getWindow();
        if (window != 0L && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
            ci.cancel();
        }
    }
}
