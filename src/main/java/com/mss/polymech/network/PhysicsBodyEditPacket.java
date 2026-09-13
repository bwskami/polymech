package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsBodyInteraction;
import com.mss.polymech.physics.PhysicsBodyTracker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 物理体交互包（客户端 → 服务端）：<b>破坏 / 使用·放置</b>。
 *
 * <p>物理体的方块不在世界里，原版交互打不到它们，所以客户端自己做射线
 * （{@link com.mss.polymech.physics.PhysicsRaycast}）后发这个包。
 * 服务端校验距离，然后在<b>投影维度</b>里跑原版逻辑
 * （见 {@link PhysicsBodyInteraction}）—— 于是箱子能开、破坏有掉落物、放置按原版朝向。</p>
 *
 * <p>不再传方块状态：放置用哪个方块由服务端读玩家手上的物品决定，更可信。</p>
 */
public record PhysicsBodyEditPacket(
        Action action,
        long bodyId,
        int dx,
        int dy,
        int dz,
        int faceX,
        int faceY,
        int faceZ,
        int hand,
        boolean sneaking
) implements CustomPacketPayload {

    public enum Action {
        BREAK,
        USE
    }

    /** 允许的交互距离（格），比原版方块交互距离略宽松，防止绕角作弊。 */
    private static final double MAX_REACH = 8.0;

    public static final Type<PhysicsBodyEditPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_edit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodyEditPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodyEditPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysicsBodyEditPacket(
                            Action.values()[buf.readByte()],
                            buf.readLong(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                            buf.readByte(), buf.readByte(), buf.readByte(),
                            buf.readByte(),
                            buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodyEditPacket p) {
                    buf.writeByte(p.action.ordinal());
                    buf.writeLong(p.bodyId);
                    buf.writeVarInt(p.dx);
                    buf.writeVarInt(p.dy);
                    buf.writeVarInt(p.dz);
                    buf.writeByte(p.faceX);
                    buf.writeByte(p.faceY);
                    buf.writeByte(p.faceZ);
                    buf.writeByte(p.hand);
                    buf.writeBoolean(p.sneaking);
                }
            };

    public static PhysicsBodyEditPacket breakBlock(long bodyId, int dx, int dy, int dz) {
        return new PhysicsBodyEditPacket(Action.BREAK, bodyId, dx, dy, dz, 0, 0, 0, 0, false);
    }

    /** 使用 / 放置：面方向用于原版 {@code useOn} 的落点与朝向计算，hand 0=主手 1=副手。 */
    public static PhysicsBodyEditPacket use(long bodyId, int dx, int dy, int dz,
                                            int faceX, int faceY, int faceZ, int hand, boolean sneaking) {
        return new PhysicsBodyEditPacket(Action.USE, bodyId, dx, dy, dz, faceX, faceY, faceZ, hand, sneaking);
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
                case BREAK -> PhysicsBodyInteraction.breakBlock(player, packet.bodyId(),
                        packet.dx(), packet.dy(), packet.dz());
                case USE -> PhysicsBodyInteraction.useOrPlace(player, packet.bodyId(),
                        packet.dx(), packet.dy(), packet.dz(),
                        packet.faceX(), packet.faceY(), packet.faceZ(),
                        packet.hand(), packet.sneaking());
            }
        });
    }
}
