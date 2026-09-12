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
 * 太空朝向同步包（双向）：同步<b>身体</b>基底（bodyFacing/bodyLeft）+ 头部相对身体的偏角。
 *
 * <p>身体基底是刚体姿态（渲染身体模型、物理碰撞箱都用它）；头部偏角只有本地相机和
 * 头部零件渲染需要，但它很便宜，一起带上后远端玩家也能看到"身体跟着头转"的效果。</p>
 */
public record SpaceRotationPayload(
        int entityId,
        float fx, float fy, float fz,
        float lx, float ly, float lz,
        float headYaw, float headPitch
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
                            buf.readFloat(), buf.readFloat());
                }
                @Override
                public void encode(RegistryFriendlyByteBuf buf, SpaceRotationPayload p) {
                    buf.writeInt(p.entityId);
                    buf.writeFloat(p.fx); buf.writeFloat(p.fy); buf.writeFloat(p.fz);
                    buf.writeFloat(p.lx); buf.writeFloat(p.ly); buf.writeFloat(p.lz);
                    buf.writeFloat(p.headYaw); buf.writeFloat(p.headPitch);
                }
            };

    public static SpaceRotationPayload clientToServer(Vector3d bodyFacing, Vector3d bodyLeft,
                                                     float headYaw, float headPitch) {
        return new SpaceRotationPayload(-1,
                (float) bodyFacing.x, (float) bodyFacing.y, (float) bodyFacing.z,
                (float) bodyLeft.x, (float) bodyLeft.y, (float) bodyLeft.z,
                headYaw, headPitch);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    /** 补发/转播时把 entityId 换成被观察者（clientToServer 造出来的包 entityId = -1）。 */
    public SpaceRotationPayload withEntityId(int id) {
        return new SpaceRotationPayload(id, fx, fy, fz, lx, ly, lz, headYaw, headPitch);
    }

    public static void handle(SpaceRotationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            var level = context.player().level();
            if (!level.isClientSide()) {
                ServerPlayer sender = context.player() instanceof ServerPlayer sp ? sp : null;
                if (sender == null) return;
                apply(SpacePlayerData.get(sender), payload);
                var fwd = new SpaceRotationPayload(sender.getId(),
                        payload.fx, payload.fy, payload.fz,
                        payload.lx, payload.ly, payload.lz,
                        payload.headYaw, payload.headPitch);
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
        data.applyRemote(p.fx, p.fy, p.fz, p.lx, p.ly, p.lz, p.headYaw, p.headPitch);
    }
}
