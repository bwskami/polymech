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
 * 物理体方块编辑包（客户端 → 服务端）：破坏 / 放置。
 *
 * <p>物理体的方块不在世界里，原版交互打不到它们，所以由客户端做自己的射线
 * （{@link com.mss.polymech.physics.PhysicsRaycast}）后发这个包请求修改。
 * 服务端会校验距离与方块是否合法，然后增删快照条目、重建体素碰撞体、写存档，
 * 并把最新的完整快照重发给该维度的客户端。</p>
 */
public record PhysicsBodyEditPacket(
        Action action,
        long bodyId,
        int dx,
        int dy,
        int dz,
        int stateId
) implements CustomPacketPayload {

    public enum Action {
        BREAK,
        PLACE
    }

    /** 允许的交互距离（格），比原版方块交互距离略宽松，防止绕角作弊。 */
    private static final double MAX_REACH = 8.0;

    public static final Type<PhysicsBodyEditPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_edit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodyEditPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodyEditPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysicsBodyEditPacket(Action.values()[buf.readByte()], buf.readLong(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodyEditPacket p) {
                    buf.writeByte(p.action.ordinal());
                    buf.writeLong(p.bodyId);
                    buf.writeVarInt(p.dx);
                    buf.writeVarInt(p.dy);
                    buf.writeVarInt(p.dz);
                    buf.writeVarInt(p.stateId);
                }
            };

    public static PhysicsBodyEditPacket breakBlock(long bodyId, int dx, int dy, int dz) {
        return new PhysicsBodyEditPacket(Action.BREAK, bodyId, dx, dy, dz, 0);
    }

    public static PhysicsBodyEditPacket placeBlock(long bodyId, int dx, int dy, int dz, int stateId) {
        return new PhysicsBodyEditPacket(Action.PLACE, bodyId, dx, dy, dz, stateId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsBodyEditPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            // 服务端校验：目标刚体必须在玩家附近（防止远程改别人的船）
            if (!PhysicsBodyTracker.isNear(packet.bodyId(), player, MAX_REACH + 8.0)) {
                return;
            }
            switch (packet.action()) {
                case BREAK -> PhysicsBodyTracker.breakBlock(packet.bodyId(), packet.dx(), packet.dy(), packet.dz());
                case PLACE -> PhysicsBodyTracker.placeBlock(packet.bodyId(), packet.dx(), packet.dy(), packet.dz(),
                        packet.stateId());
            }
        });
    }
}
