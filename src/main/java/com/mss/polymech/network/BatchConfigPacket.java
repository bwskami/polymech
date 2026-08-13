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
 * 批量面配置网络包（客户端 → 服务端）。
 * <p>
 * 对应 Mekanism {@code PacketBatchConfiguration} 语义：
 * 面配置 UI 左下"清除面"按钮点击时发送，批量设置某类型（或所有类型）的全部面 IO。
 * </p>
 */
public record BatchConfigPacket(BlockPos pos, SideConfigPacket.CapabilityType capType,
                                SideConfigPacket.SideIO targetIO, boolean allTypes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<BatchConfigPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "batch_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BatchConfigPacket> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BatchConfigPacket::pos,
                    ByteBufCodecs.BYTE.cast(), p -> (byte) p.capType().ordinal(),
                    ByteBufCodecs.BYTE.cast(), p -> (byte) p.targetIO().ordinal(),
                    ByteBufCodecs.BOOL, BatchConfigPacket::allTypes,
                    BatchConfigPacket::new
            );

    public BatchConfigPacket(BlockPos pos, byte capTypeOrdinal, byte targetIOOrdinal, boolean allTypes) {
        this(pos, SideConfigPacket.CapabilityType.values()[capTypeOrdinal],
                SideConfigPacket.SideIO.values()[targetIOOrdinal], allTypes);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BatchConfigPacket packet, IPayloadContext context) {
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
            SideConfig.SideIO io = switch (packet.targetIO()) {
                case NONE -> SideConfig.SideIO.NONE;
                case IN -> SideConfig.SideIO.IN;
                case OUT -> SideConfig.SideIO.OUT;
            };
            if (packet.allTypes()) {
                config.setAllConfigAllTypes(io);
            } else {
                SideConfig.CapabilityType type = switch (packet.capType()) {
                    case ENERGY -> SideConfig.CapabilityType.ENERGY;
                    case ITEM -> SideConfig.CapabilityType.ITEM;
                    case FLUID -> SideConfig.CapabilityType.FLUID;
                };
                config.setAllConfig(type, io);
            }
        });
    }
}
