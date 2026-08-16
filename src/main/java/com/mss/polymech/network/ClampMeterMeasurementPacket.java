package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.powergrid.ClampMeterMeasurementState;
import com.mss.polymech.powergrid.GridConnection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 钳形表测量结果包（服务端 → 客户端）。
 * <p>
 * 服务端在玩家使用钳形表命中电线时发送该线缆的实际电压与电流。
 * </p>
 */
public record ClampMeterMeasurementPacket(GridConnection connection, double current, int voltage)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClampMeterMeasurementPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "clamp_meter_measurement"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClampMeterMeasurementPacket> STREAM_CODEC =
            StreamCodec.composite(
                    GridConnection.STREAM_CODEC, ClampMeterMeasurementPacket::connection,
                    ByteBufCodecs.DOUBLE, ClampMeterMeasurementPacket::current,
                    ByteBufCodecs.VAR_INT, ClampMeterMeasurementPacket::voltage,
                    ClampMeterMeasurementPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ClampMeterMeasurementPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level().isClientSide()) {
                ClampMeterMeasurementState.set(packet.connection(), packet.current(), packet.voltage());
            }
        });
    }
}
