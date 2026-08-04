package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.FluidCellItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端→服务端：设置手持流体单元的容量上限。
 * <p>
 * 服务端校验：目标手必须持有流体单元；数值夹取到
 * [已储存流体量, 单元种类最大容量]——有液体的单元不允许
 * 把上限设置到低于已储存量。
 * </p>
 */
public record SetCellCapacityPacket(InteractionHand hand, int limit) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SetCellCapacityPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "set_cell_capacity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetCellCapacityPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, packet -> packet.hand().ordinal(),
                    ByteBufCodecs.VAR_INT, SetCellCapacityPacket::limit,
                    (handOrdinal, limit) -> new SetCellCapacityPacket(InteractionHand.values()[handOrdinal], limit)
            );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetCellCapacityPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = (ServerPlayer) context.player();
            ItemStack stack = player.getItemInHand(packet.hand());
            if (!(stack.getItem() instanceof FluidCellItem)) return;
            int max = FluidCellItem.getMaxCapacity(stack);
            // 下限 = 已储存流体量：有液体的单元不能把上限设置到低于已储存量
            int min = FluidCellItem.getFluid(stack).getAmount();
            int value = Mth.clamp(packet.limit(), min, max);
            FluidCellItem.setCapacityLimit(stack, value);
        });
    }
}
