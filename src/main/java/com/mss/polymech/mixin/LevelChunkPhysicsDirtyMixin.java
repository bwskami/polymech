package com.mss.polymech.mixin;

import com.mss.polymech.physics.PhysicsTerrain;
import com.mss.polymech.physics.PhysicsWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 方块变更 → 标记对应区块为"物理脏"，下一 tick 重建体素碰撞体。
 * <p>对应 space 模组 MPS 的 MixinLevelChunk（他们按空气/非空气变化细化，
 * 这里更保守：任何方块变化都重建该区块）。</p>
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkPhysicsDirtyMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void polymech$markPhysicsDirty(BlockPos pos, BlockState state, boolean isMoving,
                                           CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        if (self.getLevel() instanceof ServerLevel serverLevel) {
            PhysicsTerrain terrain = PhysicsWorldManager.terrainIfPresent(serverLevel);
            if (terrain != null) {
                terrain.markDirty(self.getPos());
            }
        } else if (self.getLevel() instanceof net.minecraft.client.multiplayer.ClientLevel) {
            // 客户端地形碰撞体同样需要跟着方块变化重建
            com.mss.polymech.client.physics.ClientPhysics.markChunkDirty(self.getPos().toLong());
        }
    }
}
