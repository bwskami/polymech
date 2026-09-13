package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsClientHooks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理体<b>批量</b>运动同步（服务端 → 客户端）—— 对应 space 0.1.3 的
 * {@code SyncPhysicalBodyMoveBatch}。
 *
 * <p><b>为什么要把每 tick 的 N 个包并成 1 个</b>：运动同步是每 tick 都发的，
 * 每个物理体一个包意味着 N 个体 → N 个包/tick。每个包都要走一遍网络的
 * 编码、排队、发送与客户端分发，开销与**包数**成正比而不是与字节数成正比。
 * 并成一个列表包后，包数恒为 1/tick/维度，体数再多也只多几行字节。</p>
 *
 * <p>0.1.3 把旧的单体包 {@code SyncPhysicalBodyMove} 直接删掉换成了这个 —— 方向很明确。</p>
 *
 * <p>坐标用 double（不是 float）：太空坐标可达 10<sup>7</sup> 格，float 在那个量级
 * 的最小间隔已经接近 1 格，会直接毁掉平滑度。</p>
 */
public record PhysicsBodyMoveBatchPacket(List<Entry> moves) implements CustomPacketPayload {

    /** 一个刚体本 tick 的变换与速度。 */
    public record Entry(long bodyId,
                        double x, double y, double z,
                        float qx, float qy, float qz, float qw,
                        double vx, double vy, double vz,
                        double avx, double avy, double avz) {
    }

    public static final Type<PhysicsBodyMoveBatchPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_move_batch"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodyMoveBatchPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodyMoveBatchPacket decode(RegistryFriendlyByteBuf buf) {
                    int count = buf.readVarInt();
                    List<Entry> moves = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        moves.add(new Entry(
                                buf.readLong(),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                                buf.readDouble(), buf.readDouble(), buf.readDouble(),
                                buf.readDouble(), buf.readDouble(), buf.readDouble()));
                    }
                    return new PhysicsBodyMoveBatchPacket(moves);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodyMoveBatchPacket p) {
                    buf.writeVarInt(p.moves.size());
                    for (Entry e : p.moves) {
                        buf.writeLong(e.bodyId());
                        buf.writeDouble(e.x());
                        buf.writeDouble(e.y());
                        buf.writeDouble(e.z());
                        buf.writeFloat(e.qx());
                        buf.writeFloat(e.qy());
                        buf.writeFloat(e.qz());
                        buf.writeFloat(e.qw());
                        buf.writeDouble(e.vx());
                        buf.writeDouble(e.vy());
                        buf.writeDouble(e.vz());
                        buf.writeDouble(e.avx());
                        buf.writeDouble(e.avy());
                        buf.writeDouble(e.avz());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsBodyMoveBatchPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> PhysicsClientHooks.acceptBodyMoveBatch(packet));
    }
}
