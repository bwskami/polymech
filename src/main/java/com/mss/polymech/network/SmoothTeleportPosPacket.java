package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 平滑传送落点：服务端在 {@code changeDimension} 之后把玩家的落点发给该玩家。
 *
 * <p>对应 space 0.1.0 的 {@code SyncSmoothTeleportNewPosPacket}。用途是让客户端在
 * {@code handleRespawn}（换维度/传送会重建 LocalPlayer）时知道"服务端把我放在哪"，
 * 从而在新玩家上一次性把位置与所有插值字段（xo/yo/zo、xOld…、yRotO、bob、walkDist…）
 * 对齐 —— 否则换维度瞬间相机会抖一下。</p>
 */
public record SmoothTeleportPosPacket(double x, double y, double z) implements CustomPacketPayload {

    public static final Type<SmoothTeleportPosPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "smooth_teleport_pos"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SmoothTeleportPosPacket> STREAM_CODEC =
            StreamCodec.of(
                    (buf, p) -> {
                        buf.writeDouble(p.x);
                        buf.writeDouble(p.y);
                        buf.writeDouble(p.z);
                    },
                    buf -> new SmoothTeleportPosPacket(buf.readDouble(), buf.readDouble(), buf.readDouble())
            );

    /** 最近一次收到的落点（客户端在 handleRespawn 里取用）。 */
    private static volatile double[] lastPos = new double[]{0.0, 0.0, 0.0};
    private static volatile boolean hasLastPos = false;

    public static void handle(SmoothTeleportPosPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            lastPos = new double[]{packet.x, packet.y, packet.z};
            hasLastPos = true;
        });
    }

    /** 是否有落点可用；没有时调用方应保持原版行为。 */
    public static boolean hasPos() {
        return hasLastPos;
    }

    public static double[] pos() {
        return lastPos;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
