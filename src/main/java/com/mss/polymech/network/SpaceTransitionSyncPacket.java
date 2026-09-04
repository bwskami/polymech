package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.client.space.ClientSpaceTransition;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端→客户端：同步无缝切换的目标位置与朝向。
 */
public record SpaceTransitionSyncPacket(double x, double y, double z, float yRot, float xRot)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SpaceTransitionSyncPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "space_transition_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpaceTransitionSyncPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.DOUBLE, SpaceTransitionSyncPacket::x,
                    ByteBufCodecs.DOUBLE, SpaceTransitionSyncPacket::y,
                    ByteBufCodecs.DOUBLE, SpaceTransitionSyncPacket::z,
                    ByteBufCodecs.FLOAT, SpaceTransitionSyncPacket::yRot,
                    ByteBufCodecs.FLOAT, SpaceTransitionSyncPacket::xRot,
                    SpaceTransitionSyncPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SpaceTransitionSyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level().isClientSide()) {
                ClientSpaceTransition.begin(packet.x(), packet.y(), packet.z(), packet.yRot(), packet.xRot());
            }
        });
    }
}
