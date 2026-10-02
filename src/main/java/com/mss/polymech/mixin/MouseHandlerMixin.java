package com.mss.polymech.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mss.polymech.client.physics.PhysgunInputHandler;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 牵引枪<b>旋转模式</b>下冻结相机：把鼠标移动让给物体，而不是转视角。
 *
 * <h2>参考怎么做的</h2>
 * {@code simulated.mixin.hold_interaction.MouseHandlerMixin}：
 * <pre>
 *   &#64;Inject(method = "turnPlayer", cancellable = true,
 *           at = &#64;At(value = "INVOKE", shift = At.Shift.BEFORE,
 *                      target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
 *   private void simulated$turnPlayer(double d, CallbackInfo ci,
 *                                     &#64;Local(ordinal = 4) double j, &#64;Local(ordinal = 5) double k,
 *                                     &#64;Local(ordinal = 0) int l) {
 *       final InteractCallback.Result status = SimulatedCommonClientEvents.onMouseMove(j, k * l);
 *       if (status.cancelled()) { ci.cancel(); }
 *   }
 * </pre>
 *
 * <h2>为什么我们不用 {@code @Inject + @Local(ordinal)}</h2>
 * 序号取决于目标方法的局部变量表，而 NeoForge 1.21.1 的 {@code MouseHandler.turnPlayer}
 * 与参考写这条时<b>已经不一样</b>了 —— 它开头多了一行
 * {@code ClientHooks.getTurnPlayerValues(...)}（见下面的真身）：
 * <pre>
 *   private void turnPlayer(double movementTime) {
 *       var event = net.neoforged.neoforge.client.ClientHooks.getTurnPlayerValues(...);
 *       double d2 = ...; double d3 = ...; double d4 = ...;
 *       double d0; double d1;
 *       if (...) { double d5 = ...; double d6 = ...; d0 = d5; d1 = d6; }
 *       else if (...) { ... d0 = ...; d1 = ...; }
 *       else { ... d0 = ...; d1 = ...; }
 *       int i = 1;
 *       if (this.minecraft.options.invertYMouse().get()) { i = -1; }
 *       this.minecraft.getTutorial().onMouse(d0, d1);
 *       if (this.minecraft.player != null) {
 *           this.minecraft.player.turn(d0, d1 * (double)i);     // ← 我们要包住的就是这一句
 *       }
 *   }
 * </pre>
 * 用 {@code @WrapWithCondition} 包住那一句，参数就是 {@code (player, yaw, pitch)} ——
 * <b>直接拿到值，不依赖任何序号</b>，而且 {@code pitch} 已经乘过 {@code i}（反转 Y 轴）了，
 * 比参考还要省一步。本项目已在 {@code mixin.space.MixinClientPacketListener} 用过同一个注入器。
 *
 * <h2>为什么"返回 false 吃掉"不会导致鼠标增量无限累积</h2>
 * {@code accumulatedDX/DY} 的清零在 {@code MouseHandler.onMove} 里、<b>调用 turnPlayer 之后</b>，
 * 且是<b>无条件</b>的：
 * <pre>
 *   if (this.isMouseGrabbed() &amp;&amp; this.minecraft.player != null) { this.turnPlayer(d1); }
 *   ...
 *   this.accumulatedDX = 0.0;      // ← 与 turnPlayer 有没有真的转视角无关
 *   this.accumulatedDY = 0.0;
 * </pre>
 * 所以吃掉视角转动之后，增量照样归零 —— 松手时不会突然甩一下。
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    /**
     * @return true = 照常转视角；false = 吃掉这次转动（牵引枪旋转模式）
     */
    @WrapWithCondition(
            method = "turnPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private boolean polymech$physgunRotate(LocalPlayer player, double yaw, double pitch) {
        return !PhysgunInputHandler.onMouseMove(yaw, pitch);
    }
}
