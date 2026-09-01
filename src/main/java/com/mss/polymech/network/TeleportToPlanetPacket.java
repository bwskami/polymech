package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.item.TeleporterItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：传送器请求传送到指定星球（对应太阳系天体索引）。
 * 服务端校验玩家手持传送器，然后执行跨维度传送。
 */
public record TeleportToPlanetPacket(int planetIndex) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TeleportToPlanetPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "teleport_to_planet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TeleportToPlanetPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TeleportToPlanetPacket::planetIndex,
                    TeleportToPlanetPacket::new
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TeleportToPlanetPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            if (!hasTeleporter(player)) return;
            PlanetDimensions.teleport(player, packet.planetIndex());
        });
    }

    private static boolean hasTeleporter(ServerPlayer player) {
        if (player.getMainHandItem().getItem() instanceof TeleporterItem) return true;
        if (player.getOffhandItem().getItem() instanceof TeleporterItem) return true;
        for (var stack : player.getInventory().items) {
            if (stack.getItem() instanceof TeleporterItem) return true;
        }
        return false;
    }
}
