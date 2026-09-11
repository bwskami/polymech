package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;

/**
 * 太空朝向同步包（双向）：同步 facing/left 双向量。
 * yaw/pitch 由 vanilla 实体同步处理，这里同步完整 6DOF 朝向所需的向量。
 */
public record SpaceRotationPayload(
        int entityId,
        float fx, float fy, float fz,
        float lx, float ly, float lz
) implements CustomPacketPayload {

    public static final Type<SpaceRotationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "space_rotation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpaceRotationPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SpaceRotationPayload decode(RegistryFriendlyByteBuf buf) {
                    return new SpaceRotationPayload(buf.readInt(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat());
                }
                @Override
                public void encode(RegistryFriendlyByteBuf buf, SpaceRotationPayload p) {
                    buf.writeInt(p.entityId);
                    buf.writeFloat(p.fx); buf.writeFloat(p.fy); buf.writeFloat(p.fz);
                    buf.writeFloat(p.lx); buf.writeFloat(p.ly); buf.writeFloat(p.lz);
                }
            };

    public static SpaceRotationPayload clientToServer(Vector3d facing, Vector3d left) {
        return new SpaceRotationPayload(-1,
                (float) facing.x, (float) facing.y, (float) facing.z,
                (float) left.x, (float) left.y, (float) left.z);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(SpaceRotationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var level = context.player().level();
            if (!level.isClientSide()) {
                ServerPlayer sender = context.player() instanceof ServerPlayer sp ? sp : null;
                if (sender == null) return;
                apply(SpacePlayerData.get(sender), payload);
                var fwd = new SpaceRotationPayload(sender.getId(),
                        payload.fx, payload.fy, payload.fz, payload.lx, payload.ly, payload.lz);
                for (ServerPlayer other : sender.server.getPlayerList().getPlayers()) {
                    if (other != sender) other.connection.send(fwd);
                }
            } else {
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level == null) return;
                var entity = mc.level.getEntity(payload.entityId);
                if (entity == null) return;
                apply(SpacePlayerData.get(entity), payload);
            }
        });
    }

    private static void apply(SpacePlayerData data, SpaceRotationPayload p) {
        data.facing().set(p.fx, p.fy, p.fz);
        data.left().set(p.lx, p.ly, p.lz);
        data.orthonormalize();
    }
}
