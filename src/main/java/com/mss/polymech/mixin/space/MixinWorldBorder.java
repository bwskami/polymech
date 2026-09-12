package com.mss.polymech.mixin.space;

import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 对应 space 0.1.0 的 {@code ...space.mixin.common.level.MixinWorldBorder}：把世界边界整套放开。
 *
 * <p>space 的做法是：所有边界坐标返回 ±{@code Double.MAX_VALUE}、{@code isWithinBounds} 恒真、
 * {@code getAbsoluteMaxSize} 返回 {@code Integer.MAX_VALUE}、边界伤害安全区视为无穷。
 * 这样任何坐标都不会被边界拦下 —— 太空里行星坐标可达 4×10<sup>8</sup> 格，
 * 而我们此前是"给太空维度造一个专用大边界对象"（{@code SpaceWorld.spaceBorder}），
 * 那条路要自己处理"ServerLevel 构造时把边界重置回服务端上限"之类的坑。</p>
 *
 * <p><b>副作用（照 space，需知晓）</b>：这是<b>全局</b>放开 —— 主世界的世界边界也不再限制玩家，
 * 边界只作为显示与命令口径存在。对这类科技/太空模组是可接受的取舍；若要保留主世界边界，
 * 就得改回"按维度区分"的做法。</p>
 */
@Mixin(WorldBorder.class)
public class MixinWorldBorder {

    @Inject(method = "getMaxX", at = @At("HEAD"), cancellable = true)
    private void polymech$getMaxX(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(Double.MAX_VALUE);
    }

    @Inject(method = "getMaxZ", at = @At("HEAD"), cancellable = true)
    private void polymech$getMaxZ(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(Double.MAX_VALUE);
    }

    @Inject(method = "getMinX", at = @At("HEAD"), cancellable = true)
    private void polymech$getMinX(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(-Double.MAX_VALUE);
    }

    @Inject(method = "getMinZ", at = @At("HEAD"), cancellable = true)
    private void polymech$getMinZ(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(-Double.MAX_VALUE);
    }

    @Inject(method = "isWithinBounds(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void polymech$isWithinBounds(double x, double z, double offset, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }

    @Inject(method = "getDistanceToBorder(DD)D", at = @At("HEAD"), cancellable = true)
    private void polymech$getDistanceToBorder(double x, double z, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(Double.MAX_VALUE);
    }

    @Inject(method = "getAbsoluteMaxSize", at = @At("HEAD"), cancellable = true)
    private void polymech$getAbsoluteMaxSize(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Integer.MAX_VALUE);
    }

    @Inject(method = "getDamageSafeZone", at = @At("HEAD"), cancellable = true)
    private void polymech$getDamageSafeZone(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(Double.MAX_VALUE);
    }

    @Inject(method = "isInsideCloseToBorder", at = @At("HEAD"), cancellable = true)
    private void polymech$isInsideCloseToBorder(CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(true);
    }
}
