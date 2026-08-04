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
                // 沿路径接线：路径上相邻两格都是管道则互连（含吸附格已有旧管道的情况）
                wirePathConnections(level, path);
                // 端点接线：起点侧面向起点设为抽取，终点侧面向终点设为连接；
                // 锚点是 OUTPUT 代理时无论点击顺序都取抽取方向（否则蒸汽/产物出不来）
                applyEndpoint(level, packet.start(), path.get(0), PipeBlock.PipeConnection.EXTRACT);
                applyEndpoint(level, packet.end(), path.get(path.size() - 1), PipeBlock.PipeConnection.CONNECTED);
                // 统一重建管网：批量铺设期间每根管各自成孤立网，
                // 这里把所有涉及的网按当前连通性整体重新聚类（守恒，不丢流体）
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    com.mss.polymech.pipenet.WorldPipeNet.get(serverLevel).onBatchConnectionsChanged(path);
                }
            }
            
            if (placedCount > 0 && !player.isCreative()) {
                heldItem.shrink(placedCount);
            }
        });
    }
    
    /**
     * 沿路径接线：路径上相邻的两格当前都是管道则互连（setConnection 会镜像同步对面）。
     * 不在路径上的旧管道不会被接上；路径上的格子若已有旧管道（如吸附格）则一并接入，
     * 保证整条路径连通。
     */
    private static void wirePathConnections(Level level, List<BlockPos> path) {
        for (int i = 0; i + 1 < path.size(); i++) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(i + 1);
            if (!(level.getBlockState(a).getBlock() instanceof PipeBlock)) continue;
            if (!(level.getBlockState(b).getBlock() instanceof PipeBlock)) continue;
            Direction dir = getStepDirection(a, b);
            if (dir != null) {
                PipeBlock.setConnection(level, a, dir, PipeBlock.PipeConnection.CONNECTED);
            }
        }
    }

    /** 相邻两格间的方向（仅支持单轴一步位移） */
    private static Direction getStepDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        for (Direction dir : Direction.values()) {
            if (dir.getStepX() == dx && dir.getStepY() == dy && dir.getStepZ() == dz) {
                return dir;
            }
        }
        return null;
    }
    
    /**
     * 为铺设端点接线：把路径端点格（或其相邻格）的管道面向锚点的面设为指定连接状态。
     * <p>
     * 吸附场景下路径端点格是锚点声明面所对的格子（可能就是管道格本身）；
     * 未吸附时路径端点格是锚点自身（容器/机器），取与其相邻的管道。
     * 若锚点是机器声明的 OUTPUT 代理，则强制设为抽取：输出代理只能被抽取，
     * 不受点击顺序影响（否则先点了机器一端会被设成 CONNECTED 导致抽不出来）。
     * </p>
     */
    private static void applyEndpoint(Level level, BlockPos anchor, BlockPos pathEndCell, PipeBlock.PipeConnection value) {
        PipeBlock.PipeConnection effective = value;
        if (value == PipeBlock.PipeConnection.CONNECTED && isOutputProxy(level, anchor)) {
            effective = PipeBlock.PipeConnection.EXTRACT;
        }

        BlockPos pipePos = null;
        Direction anchorDir = null;
        if (level.getBlockState(pathEndCell).getBlock() instanceof PipeBlock) {
            // 吸附格（或路径端点格本身就是管道）：面向锚点
            pipePos = pathEndCell;
            anchorDir = getStepDirection(pathEndCell, anchor);
        } else if (pathEndCell.equals(anchor)) {
            // 未吸附：锚点是容器/机器，找与其相邻的管道
            for (Direction dir : Direction.values()) {
                BlockPos candidate = anchor.relative(dir);
                if (level.getBlockState(candidate).getBlock() instanceof PipeBlock) {
                    pipePos = candidate;
                    anchorDir = dir.getOpposite();
                    break;
                }
            }
        }
        if (pipePos == null || anchorDir == null) return;
        PipeBlock.setConnection(level, pipePos, anchorDir, effective);
    }

    /** 锚点是否为机器声明的 OUTPUT 代理（物品或流体） */
    private static boolean isOutputProxy(net.minecraft.world.level.BlockGetter level, BlockPos anchor) {
        if (!(level.getBlockEntity(anchor) instanceof com.mss.polymech.machine.BaseIOSideBlockEntity sideEntity)) return false;
        BlockPos parentPos = sideEntity.getParentPos();
        if (parentPos == null) return false;
        if (!(level.getBlockState(parentPos).getBlock() instanceof com.mss.polymech.machine.BaseMachineBlock machineBlock)) return false;
        net.minecraft.core.Direction facing = level.getBlockState(parentPos).getValue(com.mss.polymech.machine.BaseMachineBlock.FACING);
        net.minecraft.core.Vec3i offset = new net.minecraft.core.Vec3i(
                anchor.getX() - parentPos.getX(), anchor.getY() - parentPos.getY(), anchor.getZ() - parentPos.getZ());
        net.minecraft.core.Vec3i local = com.mss.polymech.machine.BaseMachineBlock.unrotateVec3i(offset, facing);
        com.mss.polymech.machine.BaseMachineBlock.FluidProxy fluidProxy = machineBlock.getFluidProxy(local);
        if (fluidProxy != null) return fluidProxy.io() == com.mss.polymech.machine.BaseMachineBlock.ProxyIO.OUTPUT;
        com.mss.polymech.machine.BaseMachineBlock.ItemProxy itemProxy = machineBlock.getItemProxy(local);
        return itemProxy != null && itemProxy.io() == com.mss.polymech.machine.BaseMachineBlock.ProxyIO.OUTPUT;
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
