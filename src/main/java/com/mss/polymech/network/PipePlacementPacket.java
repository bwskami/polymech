package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.util.PipePathCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public record PipePlacementPacket(BlockPos start, BlockPos end, String materialName, String sizeName) implements CustomPacketPayload {
    
    public static final CustomPacketPayload.Type<PipePlacementPacket> TYPE = 
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "pipe_placement"));
    
    public static final StreamCodec<RegistryFriendlyByteBuf, PipePlacementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PipePlacementPacket::start,
                    BlockPos.STREAM_CODEC, PipePlacementPacket::end,
                    ByteBufCodecs.STRING_UTF8, PipePlacementPacket::materialName,
                    ByteBufCodecs.STRING_UTF8, PipePlacementPacket::sizeName,
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
            
            PipeMaterial material = resolveMaterial(packet.materialName());
            PipeBlock.PipeSize size = resolveSize(packet.sizeName());
            
            var pipeBlock = ModBlocks.getPipe(material, size).get();
            var pipeItem = pipeBlock.asItem();
            
            ItemStack heldItem = player.getMainHandItem();
            if (!heldItem.is(pipeItem)) return;
            
            int available = player.isCreative() ? Integer.MAX_VALUE : heldItem.getCount();
            
            int placedCount = 0;
            for (BlockPos pos : path) {
                if (placedCount >= available) break;
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
    
    private static PipeMaterial resolveMaterial(String name) {
        for (PipeMaterial m : PipeMaterial.values()) {
            if (m.getName().equals(name)) return m;
        }
        return PipeMaterial.IRON;
    }
    
    private static PipeBlock.PipeSize resolveSize(String name) {
        for (PipeBlock.PipeSize s : PipeBlock.PipeSize.values()) {
            if (s.getName().equals(name)) return s;
        }
        return PipeBlock.PipeSize.NORMAL;
    }
}
