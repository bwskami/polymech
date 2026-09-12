package com.mss.polymech.mixin;

import com.mss.polymech.space.SpaceWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空维度：流体不流动。
 *
 * <p>零重力下"水往低处流"本身就不成立；space 0.1.0 同样是直接取消
 * {@code FlowingFluid.tick}（其 {@code MixinFlowingFluid}）。</p>
 */
@Mixin(FlowingFluid.class)
public class SpaceFlowingFluidMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void polymech$noFluidFlowInSpace(Level level, BlockPos pos, FluidState state, CallbackInfo ci) {
        if (SpaceWorld.isSpace(level)) {
            ci.cancel();
        }
    }
}
