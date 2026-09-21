package com.mss.polymech.mps.kelvin.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 天体运动批量同步 —— <b>与
 * {@code org.cn_grass_block.kelvin.network.packet.SyncCelestialBodyMoveBatch} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 每 tick 把服务器上所有天体的位姿打包发给客户端，写进
 * {@link ClientSpaceWorld#getBufferSpaceWorld()} 的<b>缓冲区</b>。
 *
 * <h2>三个设计要点（照 space 0.1.3）</h2>
 * <ol>
 *   <li><b>只发名字 + 位姿，不发天体本身</b>：天体是创建时一次性发过的
 *       （{@code SyncCelestialBodyCreate}），之后每 tick 变的只有位置和姿态。
 *       逐 tick 全量重发会把带宽吃光（20 个天体 ≈ 1.2 KB/tick/玩家）。</li>
 *   <li><b>客户端按名字查而不是按索引</b>：天体集合在服务端可能增删
 *       （流星会不断生成），索引会错位；名字是稳定键
 *       （{@link CelestialBody#equals} 也只比名字）。</li>
 *   <li><b>写缓冲区、且走 {@code moveTo}/{@code rotateTo} 入队</b>：
 *       缓冲区是网络接收方，写入必须排队（{@code *Direct} 是物理线程专用，
 *       见 {@code CelestialBody} 类注释）；再由渲染路径的
 *       {@code ClientSpaceWorld.syncMoveData()} 在 flush 后整块拷进显示世界，
 *       突变只发生在同步点，同步点之间交给 {@code getSmoothPos/SmoothRotate} 插值。</li>
 * </ol>
 * <b>本包不参与回执/重发</b>：它是"每 tick 都有下一份"的流式数据，
 * 丢一份下一 tick 就补上了 —— 可靠传输在这里只会浪费带宽、放大延迟。
 * 这也是它和 {@code Sync*Create} 系列最本质的区别。
 *
 * <p>缓冲世界为 null 时<b>直接跳过</b>（不是异常）：包可能在创建包之前到达
 * （两条通路没有顺序保证），此时丢掉这一批是正确行为。</p>
 */
public class SyncCelestialBodyMoveBatch implements CustomPacketPayload {

    private final List<Entry> moves;
    public static final Type<SyncCelestialBodyMoveBatch> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_celestial_body_move_batch"));
    public static final StreamCodec<FriendlyByteBuf, SyncCelestialBodyMoveBatch> STREAM_CODEC =
            StreamCodec.ofMember(SyncCelestialBodyMoveBatch::encode, SyncCelestialBodyMoveBatch::decode);

    public SyncCelestialBodyMoveBatch(List<Entry> moves) {
        this.moves = moves;
    }

    private static void encode(SyncCelestialBodyMoveBatch packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.moves.size());
        for (Entry entry : packet.moves) {
            buffer.writeUtf(entry.name());
            buffer.writeDouble(entry.pos().x());
            buffer.writeDouble(entry.pos().y());
            buffer.writeDouble(entry.pos().z());
            buffer.writeDouble(entry.rotate().x());
            buffer.writeDouble(entry.rotate().y());
            buffer.writeDouble(entry.rotate().z());
            buffer.writeDouble(entry.rotate().w());
        }
    }

    private static SyncCelestialBodyMoveBatch decode(FriendlyByteBuf buffer) {
        int count = buffer.readInt();
        List<Entry> moves = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = buffer.readUtf();
            Vector3d pos = new Vector3d(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
            Quaterniond rotate = new Quaterniond(buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble());
            moves.add(new Entry(name, pos, rotate));
        }
        return new SyncCelestialBodyMoveBatch(moves);
    }

    public static void handle(SyncCelestialBodyMoveBatch packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientSpaceWorld bufferWorld = ClientSpaceWorld.getBufferSpaceWorld();
            if (bufferWorld != null) {
                for (Entry entry : packet.moves) {
                    CelestialBody body = bufferWorld.getCelestialBody(entry.name());
                    if (body != null) {
                        body.moveTo(entry.pos());
                        body.rotateTo(entry.rotate());
                    }
                }
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 一条位姿更新：名字是稳定键（见类注释）。 */
    public record Entry(String name, Vector3d pos, Quaterniond rotate) {
    }
}
