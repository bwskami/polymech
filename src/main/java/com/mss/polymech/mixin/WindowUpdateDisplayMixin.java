package com.mss.polymech.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 窗口最小化时的显示更新：跳过交换缓冲，但<i>保留 GLFW 事件泵</i>。
 *
 * <p><b>修复的 bug：最小化后点任务栏无法恢复窗口。</b></p>
 *
 * <p>原版 updateDisplay() → RenderSystem.flipFrame() 依次执行
 * pollEvents → replayQueue → glfwSwapBuffers → pollEvents。
 * 旧实现最小化时 cancel 整个 updateDisplay，连 pollEvents 一起跳过，
 * 于是 GLFW 事件队列无人消费。而 GLFW 的窗口属性（ICONIFIED / 窗口尺寸 /
 * 焦点）只有在事件循环里才能更新——用户点任务栏恢复窗口的系统消息
 * 永远不被处理，{@code glfwGetWindowAttrib(ICONIFIED)} 永远停在 1，
 * 本 mixin 于是永远 cancel，形成自锁：窗口再也回不来。
 * （帧率限制器 glfwWaitEventsTimeout 只在"帧比上限快"时才顺带泵事件，
 * 帧率不限 + 关垂直同步时这条退路也没有。）</p>
 *
 * <p>现在：最小化时手动泵一次 {@link GLFW#glfwPollEvents()}（事件队列保持消费、
 * 窗口属性始终最新），只跳过 glfwSwapBuffers —— 这保留了本 mixin 最初的
 * 目的（部分 GPU 驱动对后台/最小化窗口的缓冲交换会卡死），同时消除了自锁。</p>
 */
@Mixin(Window.class)
public abstract class WindowUpdateDisplayMixin {

    @Shadow
    private long window;

    @Inject(method = "updateDisplay", at = @At("HEAD"), cancellable = true)
    private void polymech$skipSwapWhenIconified(CallbackInfo ci) {
        if (window == 0L) return;
        if (GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) != GLFW.GLFW_TRUE) return;

        // 跳过交换缓冲（防驱动卡死），但事件泵必须继续转（否则最小化状态自锁）
        ci.cancel();
        GLFW.glfwPollEvents();
    }
}
