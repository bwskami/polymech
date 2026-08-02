package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseIOBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MachineTogglePacket(BlockPos pos) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MachineTogglePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "machine_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MachineTogglePacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, MachineTogglePacket::pos,
                    MachineTogglePacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(MachineTogglePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            BlockEntity be = player.level().getBlockEntity(packet.pos());
            if (be instanceof BaseIOBlockEntity machine) {
                machine.toggleEnable();
            }
        });
    }
}
