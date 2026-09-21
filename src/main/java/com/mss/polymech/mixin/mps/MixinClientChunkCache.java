package com.mss.polymech.mixin.mps;

import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData.BlockEntityTagOutput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

/**
 * 客户端区块载入/卸载 → 地形碰撞体重建 —— <b>与
 * {@code org.polaris2023.mps.mixin.MixinClientChunkCache} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 区块真的到手时（{@code replaceWithPacketData} 返回）与真的卸载时（{@code drop}）
 * 通知 {@link ClientPhysicalWorld} 重建/移除对应的地形碰撞体。
 *
 * <h2>为什么用"区块事件驱动"而不是每 tick 扫描</h2>
 * 客户端地形碰撞体是<b>按区块</b>建的（体素化 16×384×16）。每 tick 扫描周围区块
 * 既贵又没必要 —— 区块的变化本来就只发生在"载入"和"卸载"两个时刻。
 * 所以 {@code ClientPhysicalWorld} 只在收到这两个事件时动，
 * 方块改动走 {@link MixinLevelChunk} 的 {@code onChunkBlockChanged}（第三种变化）。
 * <p>三个入口分别对应区块生命周期的三个阶段，缺一个就会出现
 * "走到新地方脚下没有碰撞"或"离开后碰撞体还在"。</p>
 *
 * <p>注入点选在 <b>RETURN</b> 且用 {@code getReturnValue()}：必须等原版把区块
 * 真正装好（方块、光照、方块实体都就位）之后再体素化，否则会读到半成品。
 * 返回 null（区块没装上）时直接跳过。</p>
 */
@Mixin(ClientChunkCache.class)
public class MixinClientChunkCache {

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void polymech$onChunkReceived(int x, int z, FriendlyByteBuf buffer, CompoundTag tag,
                                         Consumer<BlockEntityTagOutput> consumer,
                                         CallbackInfoReturnable<LevelChunk> cir) {
        ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
        if (physicalWorld == null) {
            return;
        }
        LevelChunk levelChunk = cir.getReturnValue();
        if (levelChunk != null) {
            physicalWorld.onChunkReceived(levelChunk);
        }
    }

    @Inject(method = "drop", at = @At("HEAD"))
    private void polymech$onChunkDropped(ChunkPos pos, CallbackInfo ci) {
        ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
        if (physicalWorld != null) {
            physicalWorld.onChunkDropped(pos);
        }
    }
}
