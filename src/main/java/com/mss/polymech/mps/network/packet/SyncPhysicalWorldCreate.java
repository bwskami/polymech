package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.mps.space.network.packet.SyncCreateAck;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector3d;

/**
 * 物理世界创建同步 —— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalWorldCreate} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 告诉客户端"你现在这个维度有一个物理世界，它用这个重力向量"，或者
 * "<b>没有</b>物理世界"。
 *
 * <h2>为什么重力随世界一起发（照 space 0.1.3）</h2>
 * 客户端要跑同一套 {@code RapierWorld} 来推运动学镜像与本地预测，
 * 而重力是<b>求解器的输入</b>；两端重力不一致，客户端的镜像位置会系统性地偏离服务端。
 * 所以它不是"渲染参数"，而是<b>物理参数</b>，必须与世界的创建一起、原子地下发。
 *
 * <h2>两个字段可以同时为 null（这是合法状态，不是错误）</h2>
 * {@code WorldID == null && g == null} 表示"<b>这个维度没有物理世界</b>"，
 * 客户端必须 {@link ClientPhysicalWorld#init()} <b>清掉</b>旧的 ——
 * 否则从有物理世界的维度走到没有的维度后，客户端还在推一个根本不存在的世界，
 * 表现为"船在空气里继续动"。
 *
 * <p>回执（{@link SyncCreateAck}）在<b>世界建好/清空之后</b>发：这个包是后续所有
 * {@code SyncPhysicalBodyCreate} 的前提，早回执会让服务端停止重发、而客户端还在等世界。</p>
 */
public record SyncPhysicalWorldCreate(int id, ResourceLocation WorldID, Vector3d g)
        implements CustomPacketPayload {

    public static final Type<SyncPhysicalWorldCreate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_world_create"));
    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalWorldCreate> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalWorldCreate::encode, SyncPhysicalWorldCreate::decode);

    public SyncPhysicalWorldCreate(ResourceLocation WorldID, Vector3d g) {
        this(0, WorldID, g);
    }

    public static void encode(SyncPhysicalWorldCreate packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.id);
        if (packet.WorldID != null && packet.g != null) {
            buffer.writeBoolean(true);
            buffer.writeResourceLocation(packet.WorldID);
            buffer.writeDouble(packet.g.x);
            buffer.writeDouble(packet.g.y);
            buffer.writeDouble(packet.g.z);
        } else {
            // 一个 boolean 同时表达"没有世界"与"字段为 null"——两者本来就是同一件事
            buffer.writeBoolean(false);
        }
    }

    public static SyncPhysicalWorldCreate decode(FriendlyByteBuf buffer) {
        int id = buffer.readInt();
        return buffer.readBoolean()
                ? new SyncPhysicalWorldCreate(id, buffer.readResourceLocation(),
                new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()))
                : new SyncPhysicalWorldCreate(id, null, null);
    }

    public static void handle(SyncPhysicalWorldCreate packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (packet.WorldID != null && packet.g != null) {
                ClientPhysicalWorld.setPhysicalWorld(new ClientPhysicalWorld(packet.WorldID, packet.g));
            } else {
                ClientPhysicalWorld.init();
            }
            PacketDistributor.sendToServer(new SyncCreateAck(packet.id));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
