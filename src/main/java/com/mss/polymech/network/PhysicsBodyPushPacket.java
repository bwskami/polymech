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
 * 玩家推动物理体（客户端 → 服务端）。
 *
 * <p>玩家与物理体的碰撞是在客户端解算的（见 {@code PlayerPhysicsCollision}），
 * 所以"推得动船"必须回报给服务端：客户端在检测到与某刚体碰撞且自己在移动时发这个包，
 * 服务端用<b>冲量</b>施加到刚体上 —— 冲量是质量感知的，轻的小平台一推就走，
 * 大船几乎推不动（符合直觉，也避免"人肉推进器"）。</p>
 */
public record PhysicsBodyPushPacket(long bodyId, float dirX, float dirZ, float strength)
        implements CustomPacketPayload {

    /** 单次推动的最大冲量（N·s）。 */
    private static final double MAX_IMPULSE = 24.0;
    /** 服务端接受的最大距离（格）。 */
    private static final double MAX_RANGE = 6.0;

    public static final Type<PhysicsBodyPushPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_push"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodyPushPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodyPushPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysicsBodyPushPacket(buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readFloat());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodyPushPacket p) {
                    buf.writeLong(p.bodyId);
                    buf.writeFloat(p.dirX);
                    buf.writeFloat(p.dirZ);
                    buf.writeFloat(p.strength);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsBodyPushPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!PhysicsBodyTracker.isNear(packet.bodyId(), player, MAX_RANGE)) {
                return;
            }
            double len = Math.sqrt(packet.dirX * packet.dirX + packet.dirZ * packet.dirZ);
            if (len < 1.0e-4) {
                return;
            }
            double strength = Math.max(0.0, Math.min(1.0, packet.strength));
            double scale = MAX_IMPULSE * strength / len;
            PhysicsBodyTracker.applyImpulse(packet.bodyId(),
                    packet.dirX * scale, 0.0, packet.dirZ * scale);
        });
    }
}
