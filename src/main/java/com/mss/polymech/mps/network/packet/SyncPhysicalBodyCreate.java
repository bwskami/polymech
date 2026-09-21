package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_body.ClientPhysicalBody;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.mps.space.network.packet.SyncCreateAck;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 物理体创建同步 —— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalBodyCreate} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把一个物理体的<b>身份 + 初始位姿</b>发给客户端，让客户端建出<b>运动学镜像</b>
 * （{@link ClientPhysicalBody}）。
 *
 * <h2>为什么发的是"位姿"而不是"方块"（照 space 0.1.3）</h2>
 * 方块由另外两个包发（{@code SyncPhysicalBodyBlockUpdate} 发状态调色板、
 * {@code SyncPhysicalBodyBlockEntity} 发方块实体）。本包只负责"<b>地球上有一个这样的体</b>"，
 * 客户端拿到后第一件事是建一个空壳并把位姿定下来 —— 之后位姿由每 tick 的运动批量包推进。
 * <p>三条数据的分工是<b>按变化频率</b>切的：创建一次、方块偶变、位姿每 tick。
 * 混在一起发会让每 tick 的包体带上几十 KB 的方块数据。</p>
 *
 * <h2>为什么必须回执（{@link SyncCreateAck}）</h2>
 * 客户端"建出这个体"是整条流水线的前提：少了它，后续的位姿包找不到目标体
 * （{@code getPhysicalBody(uuid)} 返回 null）会被<b>静默丢弃</b>，
 * 表现为"船在服务端能开、客户端看不见"。
 * <p>所以服务端把创建包交给 {@code ReliableCreateSender} 重发，
 * 客户端在<b>真的把体加进世界之后</b>才回执（见 handle 里的顺序）。</p>
 *
 * <p>客户端只在"这个包说的维度就是我当前的物理世界"时才建体
 * —— 否则会把别的维度的船建到当前维度里。</p>
 */
public record SyncPhysicalBodyCreate(int id, ResourceLocation level, Vector3d pos, Quaterniond rotation, UUID uuid)
        implements CustomPacketPayload {

    public static final Type<SyncPhysicalBodyCreate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_body_create"));
    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalBodyCreate> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalBodyCreate::encode, SyncPhysicalBodyCreate::decode);

    /** 服务端构造用：位姿取<b>副本</b>，避免把体的活对象引用带进包里（它会被物理线程改）。 */
    public SyncPhysicalBodyCreate(PhysicalBody physicalBody) {
        this(0, physicalBody.getLevel(), new Vector3d(physicalBody.getPos()),
                new Quaterniond(physicalBody.getRotation()), physicalBody.getUuid());
    }

    public static void encode(SyncPhysicalBodyCreate packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.id);
        buffer.writeResourceLocation(packet.level);
        buffer.writeDouble(packet.pos.x);
        buffer.writeDouble(packet.pos.y);
        buffer.writeDouble(packet.pos.z);
        buffer.writeDouble(packet.rotation.x);
        buffer.writeDouble(packet.rotation.y);
        buffer.writeDouble(packet.rotation.z);
        buffer.writeDouble(packet.rotation.w);
        buffer.writeUUID(packet.uuid);
    }

    public static SyncPhysicalBodyCreate decode(FriendlyByteBuf buffer) {
        int id = buffer.readInt();
        return new SyncPhysicalBodyCreate(
                id,
                buffer.readResourceLocation(),
                new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                new Quaterniond(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()),
                buffer.readUUID());
    }

    public static void handle(SyncPhysicalBodyCreate packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
            if (physicalWorld != null && physicalWorld.getLevel().equals(packet.level)) {
                physicalWorld.addPhysicalBody(
                        new ClientPhysicalBody(packet.level, packet.pos, packet.rotation, packet.uuid));
            }
            // 回执放在"真的建出来之后"（见类注释：早回执会让服务端停止重发而客户端永久缺体）
            PacketDistributor.sendToServer(new SyncCreateAck(packet.id));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
