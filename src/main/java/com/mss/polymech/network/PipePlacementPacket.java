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

public record PipePlacementPacket(BlockPos start, BlockPos end, String pipeType) implements CustomPacketPayload {
    
    public static final CustomPacketPayload.Type<PipePlacementPacket> TYPE = 
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "pipe_placement"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, PipePlacementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PipePlacementPacket::start,
                    BlockPos.STREAM_CODEC, PipePlacementPacket::end,
                    net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8, PipePlacementPacket::pipeType,
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
            
            // 根据管道类型获取对应的方块和物品
            var pipeBlock = getPipeBlock(packet.pipeType());
            var pipeItem = getPipeItem(packet.pipeType());
            
            ItemStack heldItem = player.getMainHandItem();
            if (!heldItem.is(pipeItem)) return;
            
            int placedCount = 0;
            for (BlockPos pos : path) {
                if (level.isEmptyBlock(pos)) {
                    level.setBlock(pos, pipeBlock.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                    placedCount++;
                }
            }
            
            if (placedCount > 0 && !player.isCreative()) {
                heldItem.shrink(placedCount);
            }
        });
    }
    
    private static net.minecraft.world.level.block.Block getPipeBlock(String pipeType) {
        return switch (pipeType) {
            case "pipe" -> ModBlocks.PIPE.get();
            case "small_pipe" -> ModBlocks.SMALL_PIPE.get();
            case "big_pipe" -> ModBlocks.BIG_PIPE.get();
            case "huge_pipe" -> ModBlocks.HUGE_PIPE.get();
            default -> ModBlocks.PIPE.get();
        };
    }
    
    private static net.minecraft.world.item.Item getPipeItem(String pipeType) {
        return switch (pipeType) {
            case "pipe" -> ModBlocks.PIPE.asItem();
            case "small_pipe" -> ModBlocks.SMALL_PIPE.asItem();
            case "big_pipe" -> ModBlocks.BIG_PIPE.asItem();
            case "huge_pipe" -> ModBlocks.HUGE_PIPE.asItem();
            default -> ModBlocks.PIPE.asItem();
        };
    }
}
