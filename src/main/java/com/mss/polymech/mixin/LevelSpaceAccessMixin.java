package com.mss.polymech.mixin;

import com.mss.polymech.space.SpaceWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <p>大坐标系地基（参考 space mod 新版的 MixinLevel/MixinBlockGetter/MixinWorldBorder）：
 * 只对太空维度生效，主世界与行星影子维度行为完全不变。</p>
 * <ol>
 *   <li>{@link Level#getBlockState} / {@link Level#getFluidState}：深空位置
 *       （超过 {@link SpaceWorld#DEEP_SPACE_LIMIT}，即 BlockPos 26 位极限）直接返回
 *       真空，不进入区块系统 —— 避免在无区块坐标上触发区块加载与 26 位长整型溢出。</li>
 * </ol>
 *
 * <p>边界那条（{@code getWorldBorder} → 太空专用大边界）已删除：
 * {@code space.MixinWorldBorder} 把边界整套放开后它成了冗余。</p>
 */
@Mixin(Level.class)
public abstract class LevelSpaceAccessMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void polymech$deepSpaceBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (SpaceWorld.isSpace((Level) (Object) this) && SpaceWorld.isDeepSpace(pos.getX(), pos.getZ())) {
            cir.setReturnValue(Blocks.VOID_AIR.defaultBlockState());
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void polymech$deepSpaceFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (SpaceWorld.isSpace((Level) (Object) this) && SpaceWorld.isDeepSpace(pos.getX(), pos.getZ())) {
            cir.setReturnValue(Fluids.EMPTY.defaultFluidState());
        }
    }
}
