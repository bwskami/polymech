package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.SideConfig;
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
 * 自动输出开关网络包（客户端 → 服务端）。
 * <p>
 * 对应 Mekanism {@code PacketEjectConfiguration} 语义：
 * 面配置 UI 右上角自动弹出按钮点击时发送，切换某能力类型的 autoEject。
 * </p>
 */
public record AutoEjectPacket(BlockPos pos, SideConfigPacket.CapabilityType capType, boolean eject) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AutoEjectPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "auto_eject"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoEjectPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, AutoEjectPacket::pos,
                    ByteBufCodecs.BYTE.cast(), p -> (byte) p.capType().ordinal(),
                    ByteBufCodecs.BOOL, AutoEjectPacket::eject,
                    AutoEjectPacket::new
            );

    public AutoEjectPacket(BlockPos pos, byte capTypeOrdinal, boolean eject) {
        this(pos, SideConfigPacket.CapabilityType.values()[capTypeOrdinal], eject);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AutoEjectPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            BlockEntity be = player.level().getBlockEntity(packet.pos());
            SideConfig config = null;
            if (be instanceof BaseIOBlockEntity machine) {
                config = machine.getSideConfig();
            } else if (be instanceof BatteryBlockEntity battery) {
                config = battery.getSideConfig();
            }
            if (config == null) return;
            SideConfig.CapabilityType sideCapType = switch (packet.capType()) {
                case ENERGY -> SideConfig.CapabilityType.ENERGY;
                case ITEM -> SideConfig.CapabilityType.ITEM;
                case FLUID -> SideConfig.CapabilityType.FLUID;
            };
            config.setAutoEject(sideCapType, packet.eject());
        });
    }
}
