package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.SideConfig;
import com.mss.polymech.machine.production.BatteryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 面配置变更网络包（客户端 → 服务端）。
 * <p>
 * 玩家在面配置 UI 中点击某个面时发送，客户端先本地更新 config 以立即刷新 UI，
 * 然后发送目标 IO 状态到服务端，服务端直接设置（不循环），避免双重循环。
 * </p>
 */
public record SideConfigPacket(BlockPos pos, CapabilityType capType, Direction face, SideIO targetIO) implements CustomPacketPayload {

    public enum CapabilityType {
        ENERGY, ITEM, FLUID
    }

    public enum SideIO {
        NONE, IN, OUT
    }

    public static final CustomPacketPayload.Type<SideConfigPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "side_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SideConfigPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SideConfigPacket::pos,
                    ByteBufCodecs.BYTE.cast(), p -> (byte) p.capType().ordinal(),
                    ByteBufCodecs.BYTE.cast(), p -> (byte) p.face().ordinal(),
                    ByteBufCodecs.BYTE.cast(), p -> (byte) p.targetIO().ordinal(),
                    SideConfigPacket::new
            );

    public SideConfigPacket(BlockPos pos, byte capTypeOrdinal, byte faceOrdinal, byte targetIOOrdinal) {
        this(pos, CapabilityType.values()[capTypeOrdinal], Direction.values()[faceOrdinal], SideIO.values()[targetIOOrdinal]);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SideConfigPacket packet, IPayloadContext context) {
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
            SideConfig.SideIO sideIO = switch (packet.targetIO()) {
                case NONE -> SideConfig.SideIO.NONE;
                case IN -> SideConfig.SideIO.IN;
                case OUT -> SideConfig.SideIO.OUT;
            };
            // 直接设置目标状态（不循环），避免与客户端双重循环
            config.setConfig(sideCapType, packet.face(), sideIO);
        });
    }
}
