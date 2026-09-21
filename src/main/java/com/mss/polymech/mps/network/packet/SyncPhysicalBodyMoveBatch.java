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
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 物理体运动的**批量**同步 —— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalBodyMoveBatch} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 每 tick 把该维度**所有**物理体的位姿 + 线速度 + 角速度打进一个包发给客户端。
 *
 * <h2>为什么是这个形态（照 space 0.1.3，逐条都有理由）</h2>
 * <ul>
 *   <li><b>批量而不是每体一包</b>：一艘船一包的话，十条船就是每 tick 十包。
 *       space 0.1.3 把旧的逐体 {@code SyncPhysicalBodyMove} 删掉换成这个，正是这个原因。</li>
 *   <li><b>只发位姿与速度，不发方块</b>：方块走
 *       {@link SyncPhysicalBodyBlockUpdate}（改动时才有），运动走本包（每 tick 都有）——
 *       两者频率与体量差几个数量级，混在一起会让方块同步被运动淹没。</li>
 *   <li><b>速度也要发（不只是位姿）</b>：客户端镜像体是运动学体，位姿靠
 *       {@code setNextKinematicPosition} 插值推进；速度是给上层（渲染插值、被带走的判定、
 *       天体/飞机的力同步）用的。少了它，客户端只能靠位姿差反推，噪声大。</li>
 *   <li><b>在 {@code ctx.enqueueWork} 里改世界</b>：网络线程不能碰世界状态。</li>
 * </ul>
 *
 * <p><b>与 MPS 的差异</b>：命名空间用本项目的 {@code poly_mech}；补了空世界保护
 *（MPS 在 {@code getPhysicalWorld()} 为 null 时会 NPE）。</p>
 *
 * <p><b>本项目已有的同类件</b>：{@code PhysicsBodyMoveBatchPacket}（现网物理层用）。
 * 本包属于高仿克隆层，两者在 S6 切换时二选一，不并行使用 —— 理由见文档第十一节。</p>
 */
public class SyncPhysicalBodyMoveBatch implements CustomPacketPayload {

    private final List<Entry> moves;

    public static final Type<SyncPhysicalBodyMoveBatch> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_body_move_batch"));

    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalBodyMoveBatch> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalBodyMoveBatch::encode, SyncPhysicalBodyMoveBatch::decode);

    public SyncPhysicalBodyMoveBatch(List<Entry> moves) {
        this.moves = moves;
    }

    public List<Entry> moves() {
        return this.moves;
    }

    private static void encode(SyncPhysicalBodyMoveBatch packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.moves.size());
        for (Entry entry : packet.moves) {
            buffer.writeUUID(entry.uuid());
            buffer.writeDouble(entry.pos().x());
            buffer.writeDouble(entry.pos().y());
            buffer.writeDouble(entry.pos().z());
            buffer.writeDouble(entry.rotate().x());
            buffer.writeDouble(entry.rotate().y());
            buffer.writeDouble(entry.rotate().z());
            buffer.writeDouble(entry.rotate().w());
            buffer.writeDouble(entry.linvel().x());
            buffer.writeDouble(entry.linvel().y());
            buffer.writeDouble(entry.linvel().z());
            buffer.writeDouble(entry.angvel().x());
            buffer.writeDouble(entry.angvel().y());
            buffer.writeDouble(entry.angvel().z());
        }
    }

    private static SyncPhysicalBodyMoveBatch decode(FriendlyByteBuf buffer) {
        int count = buffer.readInt();
        List<Entry> moves = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID uuid = buffer.readUUID();
            Vector3d pos = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            Quaterniond rotate = new Quaterniond(buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble());
            Vector3d linvel = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            Vector3d angvel = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            moves.add(new Entry(uuid, pos, rotate, linvel, angvel));
        }
        return new SyncPhysicalBodyMoveBatch(moves);
    }

    public static void handle(SyncPhysicalBodyMoveBatch packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ClientPhysicalWorld world = ClientPhysicalWorld.getPhysicalWorld();
            if (world == null) {
                return; // 包比世界先到：丢弃，下一次批量同步会补上
            }
            for (Entry entry : packet.moves) {
                PhysicalBody physicalBody = world.getPhysicalBody(entry.uuid());
                if (physicalBody == null) {
                    continue; // 该体还没被创建（Create 包在途）：等下一次
                }
                // onMoveSync 是客户端镜像体的插值入口（kinematic 目标由它推进），
                // 不是简单的 setPos —— 直接 setPos 会让运动的船一顿一顿。
                physicalBody.onMoveSync(entry.pos());
                physicalBody.setRotation(entry.rotate());
                physicalBody.setLinSpeed(entry.linvel());
                physicalBody.setAngSpeed(entry.angvel());
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 一次批量里的一条：位姿 + 线速度 + 角速度。 */
    public record Entry(UUID uuid, Vector3d pos, Quaterniond rotate, Vector3d linvel, Vector3d angvel) {
    }
}
