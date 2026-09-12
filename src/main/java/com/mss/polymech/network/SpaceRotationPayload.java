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
 * 太空朝向同步包（双向）：<b>身体基底 + 视线基底</b>各两个向量。
 *
 * <p>为什么两个都要发：身体基底与视线基底各有用处（身体给模型/碰撞箱，视线给移动方向/头部朝向），
 * 从一方反推另一方在俯仰 ±90° 附近会退化。两个都发，两端状态完全一致。</p>
 */
public record SpaceRotationPayload(
        int entityId,
        float bodyFx, float bodyFy, float bodyFz,
        float bodyLx, float bodyLy, float bodyLz,
        float viewFx, float viewFy, float viewFz,
        float viewLx, float viewLy, float viewLz
) implements CustomPacketPayload {

    public static final Type<SpaceRotationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "space_rotation"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SpaceRotationPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public SpaceRotationPayload decode(RegistryFriendlyByteBuf buf) {
                    return new SpaceRotationPayload(buf.readInt(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat());
                }
                @Override
                public void encode(RegistryFriendlyByteBuf buf, SpaceRotationPayload p) {
                    buf.writeInt(p.entityId);
                    buf.writeFloat(p.bodyFx); buf.writeFloat(p.bodyFy); buf.writeFloat(p.bodyFz);
                    buf.writeFloat(p.bodyLx); buf.writeFloat(p.bodyLy); buf.writeFloat(p.bodyLz);
                    buf.writeFloat(p.viewFx); buf.writeFloat(p.viewFy); buf.writeFloat(p.viewFz);
                    buf.writeFloat(p.viewLx); buf.writeFloat(p.viewLy); buf.writeFloat(p.viewLz);
                }
            };

    public static SpaceRotationPayload clientToServer(Vector3d bodyFacing, Vector3d bodyLeft,
                                                      Vector3d viewFacing, Vector3d viewLeft) {
        return new SpaceRotationPayload(-1,
                (float) bodyFacing.x, (float) bodyFacing.y, (float) bodyFacing.z,
                (float) bodyLeft.x, (float) bodyLeft.y, (float) bodyLeft.z,
                (float) viewFacing.x, (float) viewFacing.y, (float) viewFacing.z,
                (float) viewLeft.x, (float) viewLeft.y, (float) viewLeft.z);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** 补发/转播时把 entityId 换成被观察者（clientToServer 造出来的包 entityId = -1）。 */
    public SpaceRotationPayload withEntityId(int id) {
        return new SpaceRotationPayload(id, bodyFx, bodyFy, bodyFz, bodyLx, bodyLy, bodyLz,
                viewFx, viewFy, viewFz, viewLx, viewLy, viewLz);
    }

    public static void handle(SpaceRotationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var level = context.player().level();
            if (!level.isClientSide()) {
                ServerPlayer sender = context.player() instanceof ServerPlayer sp ? sp : null;
                if (sender == null) return;
                apply(SpacePlayerData.get(sender), payload);
                var fwd = payload.withEntityId(sender.getId());
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
        data.applyRemote(p.bodyFx, p.bodyFy, p.bodyFz, p.bodyLx, p.bodyLy, p.bodyLz,
                p.viewFx, p.viewFy, p.viewFz, p.viewLx, p.viewLy, p.viewLz);
    }
}
