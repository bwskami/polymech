package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 物理体移除同步 —— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalBodyRemove} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 服务端销毁一个物理体（被完全拆掉、或搬去了别的维度）时，通知客户端把它那份镜像体与碰撞体一并撤掉。
 *
 * <h2>为什么带 {@code level} 而不是只带 uuid（space 的设计）</h2>
 * 玩家可能同时"知道"多个维度的物理体（他刚穿过维度传送门、旧维度的包还在路上）。
 * 只按 uuid 找会误删别的维度里同名的体；带上维度先比对，命中才删。
 * 这是个便宜但必要的过滤 —— 少了它，跨维度瞬间会偶发"船凭空消失"。
 *
 * <p><b>与 MPS 的差异</b>：命名空间用本项目的 {@code poly_mech}。</p>
 */
public record SyncPhysicalBodyRemove(ResourceLocation level, UUID uuid) implements CustomPacketPayload {

    public static final Type<SyncPhysicalBodyRemove> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_body_remove"));

    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalBodyRemove> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalBodyRemove::encode, SyncPhysicalBodyRemove::decode);

    public SyncPhysicalBodyRemove(PhysicalBody physicalBody) {
        this(physicalBody.getLevel(), physicalBody.getUuid());
    }

    public static void encode(SyncPhysicalBodyRemove packet, FriendlyByteBuf buffer) {
        buffer.writeResourceLocation(packet.level);
        buffer.writeUUID(packet.uuid);
    }

    public static SyncPhysicalBodyRemove decode(FriendlyByteBuf buffer) {
        return new SyncPhysicalBodyRemove(buffer.readResourceLocation(), buffer.readUUID());
    }

    public static void handle(SyncPhysicalBodyRemove packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
            if (physicalWorld != null && physicalWorld.getLevel().equals(packet.level)) {
                PhysicalBody physicalBody = physicalWorld.getPhysicalBody(packet.uuid);
                if (physicalBody != null) {
                    physicalWorld.removePhysicalBody(physicalBody);
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
