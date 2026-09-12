package com.mss.polymech.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空维度：重力方块（沙/砾石等）不下落，也不播放"扬尘"粒子。
 *
 * <p>零重力下它们本来就不该掉；space 0.1.0 的 {@code MixinFallingBlock} 同样是
 * 在太空世界取消 {@code tick} 的落体、并把 {@code animateTick} 里的
 * {@code isFree} 判成 false。</p>
 */
@Mixin(FallingBlock.class)
public abstract class SpaceFallingBlockMixin {

    @Unique
    private Level polymech$capturedLevel;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void polymech$noFallingInSpace(BlockState state, ServerLevel level, BlockPos pos,
                                           RandomSource random, CallbackInfo ci) {
        if (SpaceWorld.isSpace(level)) {
            ci.cancel();
        }
    }

    @Inject(method = "animateTick", at = @At("HEAD"))
    private void polymech$captureLevel(BlockState state, Level level, BlockPos pos,
                                       RandomSource random, CallbackInfo ci) {
        this.polymech$capturedLevel = level;
    }

    @ModifyExpressionValue(
            method = "animateTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/FallingBlock;isFree(Lnet/minecraft/world/level/block/state/BlockState;)Z",
                    ordinal = 0)
    )
    private boolean polymech$noDustInSpace(boolean original) {
        return this.polymech$capturedLevel != null && SpaceWorld.isSpace(this.polymech$capturedLevel)
                ? false
                : original;
    }
}
