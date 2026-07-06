package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.util.PipePathCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public record PipePlacementPacket(BlockPos start, BlockPos end) implements CustomPacketPayload {
    
    public static final CustomPacketPayload.Type<PipePlacementPacket> TYPE = 
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "pipe_placement"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, PipePlacementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PipePlacementPacket::start,
                    BlockPos.STREAM_CODEC, PipePlacementPacket::end,
                    PipePlacementPacket::new
            );
    
    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    
    public static void handle(PipePlacementPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            Level level = player.level();
            
            List<BlockPos> path = PipePathCalculator.calculatePath(packet.start(), packet.end());
            
            if (path.isEmpty()) return;
            
            ItemStack heldItem = player.getMainHandItem();
            if (!heldItem.is(ModBlocks.PIPE.asItem())) return;
            
            int placedCount = 0;
            for (BlockPos pos : path) {
                if (level.isEmptyBlock(pos)) {
                    level.setBlock(pos, ModBlocks.PIPE.get().defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                    placedCount++;
                }
            }
            
            if (placedCount > 0 && !player.isCreative()) {
                heldItem.shrink(placedCount);
            }
        });
    }
}
