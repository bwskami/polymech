package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.util.PipePathCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

import java.util.ArrayList;
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
            
            // 端点吸附：声明了有效面的代理 → 路径端点换到该面所对的格子（接线锚点不变）
            BlockPos pathStart = PipePathCalculator.resolveEndpoint(level, packet.start(), packet.end());
            BlockPos pathEnd = PipePathCalculator.resolveEndpoint(level, packet.end(), packet.start());
            
            List<BlockPos> path = PipePathCalculator.calculatePath(level, pathStart, pathEnd);
            if (path.isEmpty()) return;
            
            PipeMaterial material = resolveMaterial(packet.materialName());
            PipeBlock.PipeSize size = resolveSize(packet.sizeName());
            
            var pipeBlock = ModBlocks.getPipe(material, size).get();
            var pipeItem = pipeBlock.asItem();
            
            ItemStack heldItem = player.getMainHandItem();
            if (!heldItem.is(pipeItem)) return;
            
            int available = player.isCreative() ? Integer.MAX_VALUE : heldItem.getCount();
            
            int placedCount = 0;
            List<BlockPos> placed = new ArrayList<>();
            // 批量铺设：期间新管不与旧管自动连接，结束后统一接线
            PipeBlock.setLayingBatch(true);
            try {
                for (BlockPos pos : path) {
                    if (placedCount >= available) break;
                    if (level.isEmptyBlock(pos)) {
                        level.setBlock(pos, pipeBlock.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE);
                        placed.add(pos);
                        placedCount++;
                    }
                }
            } finally {
                PipeBlock.setLayingBatch(false);
            }
            
            if (!placed.isEmpty()) {
                // 新铺设的管道彼此互连（不接旧管）
                wireNewConnections(level, placed);
                // 端点接线：起点侧管道面向起点设为抽取，终点侧管道面向终点设为连接
                applyEndpoint(level, packet.start(), PipeBlock.PipeConnection.EXTRACT);
                applyEndpoint(level, packet.end(), PipeBlock.PipeConnection.CONNECTED);
                // 统一重建管网，反映新接线（守恒，不丢流体）
                PipeBlock.notifyConnectionsChanged(level, placed.get(0));
            }
            
            if (placedCount > 0 && !player.isCreative()) {
                heldItem.shrink(placedCount);
            }
        });
    }
    
    /**
     * 新铺设的管道彼此相邻的面设为已连接；不在本次铺设列表内的旧管道不会被接上。
     */
    private static void wireNewConnections(Level level, List<BlockPos> placed) {
        for (BlockPos pos : placed) {
            for (Direction dir : Direction.values()) {
                if (placed.contains(pos.relative(dir))) {
                    PipeBlock.setConnection(level, pos, dir, PipeBlock.PipeConnection.CONNECTED);
                }
            }
        }
    }
    
    /**
     * 为铺设端点接线：锚点方块相邻的管道，其指向锚点的面设为指定连接状态。
     * 起点（容器/机器）侧为抽取，终点侧为连接；锚点本身是新铺设的管道时跳过。
     * 相邻管道无论是否本次铺设都接线（吸附端点格可能已有旧管道）。
     */
    private static void applyEndpoint(Level level, BlockPos anchor, PipeBlock.PipeConnection value) {
        if (level.getBlockState(anchor).getBlock() instanceof PipeBlock) return;
        
        for (Direction dir : Direction.values()) {
            BlockPos pipePos = anchor.relative(dir);
            if (!(level.getBlockState(pipePos).getBlock() instanceof PipeBlock)) continue;
            PipeBlock.setConnection(level, pipePos, dir.getOpposite(), value);
        }
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
