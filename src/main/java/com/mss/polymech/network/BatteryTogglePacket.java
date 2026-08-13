package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.production.BatteryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 蓄电池 UI 按钮操作包（客户端 → 服务端）。
 */
public record BatteryTogglePacket(BlockPos pos, Action action) implements CustomPacketPayload {

    public enum Action {
        TOGGLE_ENABLE
    }

    public static final CustomPacketPayload.Type<BatteryTogglePacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "battery_toggle"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BatteryTogglePacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BatteryTogglePacket::pos,
                    ByteBufCodecs.BYTE.cast(), p -> (byte) p.action().ordinal(),
                    BatteryTogglePacket::new
            );

    public BatteryTogglePacket(BlockPos pos, byte actionOrdinal) {
        this(pos, Action.values()[actionOrdinal]);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BatteryTogglePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            BlockEntity be = player.level().getBlockEntity(packet.pos());
            if (be instanceof BatteryBlockEntity battery) {
                switch (packet.action()) {
                    case TOGGLE_ENABLE -> battery.toggleEnabled();
                }
            }
        });
    }
}
