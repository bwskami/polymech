package com.mss.polymech.mixin;

import com.mss.polymech.physics.ProjectionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 投影维度的"脏区块"标记 —— 阶段 2b 的入口。
 *
 * <p>对应 space/MPS 的 {@code MixinLevelChunk}：挂在 {@code LevelChunk#setBlockState} 上，
 * 凡是投影维度里发生的方块改动（红石亮灭、活塞推块、机器放置/破坏）都会经过这里，
 * 于是我们能把那个区块标脏，每 tick 只回写脏区块（而不是扫 128³ 全区）。</p>
 *
 * <p>非投影维度<b>一行都不做</b>（{@code markDirty} 内部先判 level），普通存档零开销。</p>
 */
@Mixin(LevelChunk.class)
public abstract class ProjectionChunkDirtyMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void polymech$markProjectionDirty(BlockPos pos, BlockState state, boolean isMoving,
                                              CallbackInfoReturnable<BlockState> cir) {
        ProjectionManager.markDirty((LevelChunk) (Object) this);
    }
}
