package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsBodyTracker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 物理体快照的<b>确认</b>（客户端 → 服务端）—— 对应 space 0.1.3 的 {@code SyncCreateAck}。
 *
 * <p><b>它解决什么</b>：CREATE 快照可能"发早了"—— 客户端那时还在建关卡，
 * 收到后刚存进注册表就被"卸载旧关卡"的清空流程冲掉，而服务端<b>毫不知情</b>，
 * 于是玩家看到的世界里"船没了"，只能靠重进游戏或换维度碰运气。
 * 本项目此前用的是"换维度后延迟 2 秒再补发一次"的权宜做法（见 {@code PhysicsBodyEvents}）。</p>
 *
 * <p>现在改成握手：服务端发出快照后把它记成"待确认"，
 * 客户端<b>真正存下之后</b>回这个包；超过 {@code 40} tick 没确认就重发，
 * 最多 5 次（与 space 0.1.3 的 {@code ReliableCreateSender} 同参数）。</p>
 */
public record SyncPhysicsBodyAckPacket(long bodyId) implements CustomPacketPayload {

    public static final Type<SyncPhysicsBodyAckPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncPhysicsBodyAckPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SyncPhysicsBodyAckPacket decode(RegistryFriendlyByteBuf buf) {
                    return new SyncPhysicsBodyAckPacket(buf.readLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, SyncPhysicsBodyAckPacket p) {
                    buf.writeLong(p.bodyId);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncPhysicsBodyAckPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                PhysicsBodyTracker.ackBody(player, packet.bodyId());
            }
        });
    }
}
