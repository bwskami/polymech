package com.mss.polymech.mps.space.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.space.network.ReliableCreateSender;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 创建包回执 —— <b>与 {@code org.deep_space_studio.space.network.packet.SyncCreateAck}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 客户端处理完一个创建包后，把它的 id 回给服务端，让
 * {@link ReliableCreateSender} 停止重发。
 *
 * <h2>为什么是"处理完之后"回执，而不是"收到就"回执</h2>
 * 回执的语义是"我这边<b>已经存下了</b>"。若一收到就回，服务端停止重发的那一刻，
 * 客户端可能还在主线程队列里排队；一旦它随后处理失败（解码异常、缺依赖），
 * 服务端再也不会补发，客户端就永久缺这个世界。所以回执必须发在
 * {@code ctx.enqueueWork(...)} <b>之内</b>、真正写入客户端状态之后。
 *
 * <p>包极小（一个 int），所以编码用最简单的 {@code ofMember}。</p>
 */
public record SyncCreateAck(int id) implements CustomPacketPayload {

    public static final Type<SyncCreateAck> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_create_ack"));
    public static final StreamCodec<FriendlyByteBuf, SyncCreateAck> STREAM_CODEC =
            StreamCodec.ofMember(SyncCreateAck::encode, SyncCreateAck::decode);

    private static void encode(SyncCreateAck packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.id);
    }

    private static SyncCreateAck decode(FriendlyByteBuf buffer) {
        return new SyncCreateAck(buffer.readInt());
    }

    public static void handle(SyncCreateAck packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer serverPlayer) {
                ReliableCreateSender.ack(serverPlayer, packet.id());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
