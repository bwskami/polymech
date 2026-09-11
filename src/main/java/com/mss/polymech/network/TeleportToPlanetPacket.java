package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.item.TeleporterItem;
import com.mss.polymech.space.RealAstroData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：传送器请求传送到指定星球。
 * planetIndex 为 GUI 星图的天体序号（地表传送用，服务端 PlanetDimensions 按同一索引空间解析）。
 * bodyName 为天体中文名：toSpace=true 时按名查找 RealAstroData（两套列表顺序无关，杜绝索引错位）。
 * toSpace=true 传送到太空维度中该天体上方的宇宙空间，否则传送到星球地表。
 * 服务端校验玩家手持传送器，然后执行跨维度传送。
 */
public record TeleportToPlanetPacket(int planetIndex, boolean toSpace, String bodyName) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<TeleportToPlanetPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "teleport_to_planet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TeleportToPlanetPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TeleportToPlanetPacket::planetIndex,
                    ByteBufCodecs.BOOL, TeleportToPlanetPacket::toSpace,
                    ByteBufCodecs.STRING_UTF8, TeleportToPlanetPacket::bodyName,
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
            if (packet.toSpace()) {
                // 按名字解析太空天体（GUI 星图序号与 RealAstroData 索引是两套空间，禁止混用）
                PlanetDimensions.teleportToSpaceAbove(player, RealAstroData.byName(packet.bodyName()));
            } else {
                PlanetDimensions.teleport(player, packet.planetIndex());
            }
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
