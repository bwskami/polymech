package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseMachineBlock;
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

public record MachinePlacementPacket(BlockPos target, String facingName, String machineId) implements CustomPacketPayload {

    public static final Type<MachinePlacementPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "machine_placement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachinePlacementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, MachinePlacementPacket::target,
                    ByteBufCodecs.STRING_UTF8, MachinePlacementPacket::facingName,
                    ByteBufCodecs.STRING_UTF8, MachinePlacementPacket::machineId,
                    MachinePlacementPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MachinePlacementPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            Level level = player.level();

            Block machineBlock = BaseMachineBlock.getMachineBlock(packet.machineId());
            if (!(machineBlock instanceof BaseMachineBlock machine)) return;

            Direction facing = Direction.byName(packet.facingName());
            if (facing == null) facing = Direction.NORTH;
            BlockPos targetPos = packet.target();

            BlockState state = machine.defaultBlockState().setValue(BaseMachineBlock.FACING, facing);
            BlockPos[] sidePositions = machine.getSidePositions(state, targetPos);

            if (!canPlaceMachine(level, targetPos, sidePositions)) return;

            boolean creative = player.isCreative();
            if (!creative) {
                ItemStack machineItem = machineBlock.asItem().getDefaultInstance();
                boolean hasItem = player.getMainHandItem().is(machineItem.getItem())
                        || player.getOffhandItem().is(machineItem.getItem());
                if (!hasItem) return;
            }

            level.setBlockAndUpdate(targetPos, state);
            machine.setPlacedBy(level, targetPos, state, player, ItemStack.EMPTY);

            if (!creative) {
                ItemStack mainHand = player.getMainHandItem();
                if (mainHand.is(machineBlock.asItem())) {
                    mainHand.shrink(1);
                } else {
                    player.getOffhandItem().shrink(1);
                }
            }
        });
    }

    private static boolean canPlaceMachine(Level level, BlockPos mainPos, BlockPos[] sidePositions) {
        if (!level.isEmptyBlock(mainPos) && !level.getBlockState(mainPos).canBeReplaced()) {
            return false;
        }
        for (BlockPos sidePos : sidePositions) {
            if (!level.isEmptyBlock(sidePos) && !level.getBlockState(sidePos).canBeReplaced()) {
                return false;
            }
        }
        return true;
    }
}
