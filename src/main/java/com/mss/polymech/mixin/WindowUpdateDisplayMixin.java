package com.mss.polymech.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 窗口最小化时跳过 glfwSwapBuffers，避免在“保存世界”等场景下后台交换缓冲卡死。
 */
@Mixin(Window.class)
public abstract class WindowUpdateDisplayMixin {

    @Shadow
    private long window;

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void polymech$skipSwapWhenIconified(CallbackInfo ci) {
        if (window != 0L && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE) {
            ci.cancel();
        }
    }
}
