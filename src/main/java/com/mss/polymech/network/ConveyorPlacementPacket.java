package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.util.ConveyorPathCalculator;
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
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * 传送带平面铺设网络数据包。
 * <p>
 * 客户端发送起点、终点，服务端计算路径并放置传送带。
 * 传送带朝向沿路径方向自动设置。
 * </p>
 */
public record ConveyorPlacementPacket(BlockPos start, BlockPos end) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ConveyorPlacementPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "conveyor_placement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConveyorPlacementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ConveyorPlacementPacket::start,
                    BlockPos.STREAM_CODEC, ConveyorPlacementPacket::end,
                    ConveyorPlacementPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ConveyorPlacementPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            Level level = player.level();

            List<BlockPos> path = ConveyorPathCalculator.calculatePath(packet.start(), packet.end());
            if (path.isEmpty()) return;

            List<Direction> facings = ConveyorPathCalculator.calculateFacings(path, packet.start(), packet.end());

            var conveyorBlock = ModBlocks.CONVEYOR.get();
            var conveyorItem = conveyorBlock.asItem();

            ItemStack heldItem = player.getMainHandItem();
            if (!heldItem.is(conveyorItem)) return;

            int available = player.isCreative() ? Integer.MAX_VALUE : heldItem.getCount();
            int placedCount = 0;

            for (int i = 0; i < path.size() && i < facings.size(); i++) {
                if (placedCount >= available) break;

                BlockPos pos = path.get(i);
                if (level.isEmptyBlock(pos) || level.getBlockState(pos).canBeReplaced()) {
                    Direction facing = facings.get(i);
                    BlockState state = conveyorBlock.defaultBlockState()
                            .setValue(ConveyorBlock.FACING, facing)
                            .setValue(ConveyorBlock.TYPE, ConveyorType.HORIZONTAL);

                    level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE | Block.UPDATE_NEIGHBORS);
                    placedCount++;
                }
            }

            if (placedCount > 0 && !player.isCreative()) {
                heldItem.shrink(placedCount);
            }
        });
    }
}