package com.mss.polymech.mps.kelvin.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Aircraft;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.BlackHole;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Meteoroid;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Star;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import com.mss.polymech.mps.space.network.packet.SyncCreateAck;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;

/**
 * 天体创建同步 —— <b>与
 * {@code org.cn_grass_block.kelvin.network.packet.SyncCelestialBodyCreate} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 把一个天体的<b>完整身份</b>发给客户端，并<b>各造两份</b>分别放进显示世界与缓冲世界。
 *
 * <h2>为什么要"解码两次"（照 space 0.1.3，这是本包最关键的设计）</h2>
 * 客户端有两个太空世界（显示 / 缓冲，见 {@code SyncSpaceWorldCreate}）。
 * 每个世界里必须有<b>各自的</b>天体对象实例：
 * <pre>
 *   bufferWorld  ← 每 tick 被 SyncCelestialBodyMoveBatch 写（moveTo 入队）
 *        ↓ syncMoveData() 每帧把位姿整块拷过去
 *   spaceWorld   ← 渲染读它
 * </pre>
 * 如果两个世界共享同一个实例，{@code moveToDirect} 拷贝时就会<b>自己拷自己</b>
 * （位姿永远停在原地、世界不再更新），而且"显示态"与"缓冲态"这两层语义直接消失。
 * <p>实现手法很巧妙：{@code buffer.copy()} 造一个<b>共享底层数组、但读写指针独立</b>
 * 的视图，两边各自从同一段字节解出<b>独立的对象</b>。所以只需要发一份数据，
 * 客户端就能得到两份互不干扰的天体 —— 既省带宽又不会共享可变状态。</p>
 *
 * <h2>为什么要类型判别字节</h2>
 * {@link CelestialBody#encode} 只写"身份"字段（名字/位姿/半径/维度），子类再各自追加
 * （{@code Planet} 追加质量+大气+贴图等 32 个 double）。解码方必须先知道
 * <b>该按哪个子类的 decode 读</b>，否则会按父类字段长度截断、把后续字节全读错位。
 * 所以前面先写一个 {@code byte}：
 * {@code 1=Star 2=Planet 3=BlackHole 4=Meteoroid 5=Aircraft 0=基类}。
 *
 * <p><b>注意 {@code Meteoroid} 与 {@code BlackHole} 没有自己的 {@code decode}</b>
 * —— 它们用的是继承来的 {@link CelestialBody#decode}，即解出来是<b>基类实例</b>。
 * 这是 space 的原样行为（静态方法可经子类名调用，但返回声明类型是父类的）。
 * 别"顺手"给它们补一个 decode：那会让服务端/客户端的天体类型不一致。</p>
 *
 * <p>回执照 {@code ReliableCreateSender} 约定，在写入两个世界<b>之后</b>发。</p>
 */
public class SyncCelestialBodyCreate implements CustomPacketPayload {

    /** 类型判别字节的取值（与 {@code encode} 的 switch 一一对应）。 */
    private static final byte TYPE_BASE = 0;
    private static final byte TYPE_STAR = 1;
    private static final byte TYPE_PLANET = 2;
    private static final byte TYPE_BLACK_HOLE = 3;
    private static final byte TYPE_METEOROID = 4;
    private static final byte TYPE_AIRCRAFT = 5;

    private int id;
    private CelestialBody celestialBody;
    private CelestialBody bufferCelestialBody;
    public static final Type<SyncCelestialBodyCreate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_celestial_body_create"));
    public static final StreamCodec<FriendlyByteBuf, SyncCelestialBodyCreate> STREAM_CODEC =
            StreamCodec.ofMember(SyncCelestialBodyCreate::encode, SyncCelestialBodyCreate::decode);

    public SyncCelestialBodyCreate(CelestialBody celestialBody) {
        this.celestialBody = celestialBody;
    }

    public SyncCelestialBodyCreate(int id, CelestialBody celestialBody) {
        this.id = id;
        this.celestialBody = celestialBody;
    }

    public SyncCelestialBodyCreate(CelestialBody celestialBody, CelestialBody bufferCelestialBody) {
        this.celestialBody = celestialBody;
        this.bufferCelestialBody = bufferCelestialBody;
    }

    public SyncCelestialBodyCreate(int id, CelestialBody celestialBody, CelestialBody bufferCelestialBody) {
        this.id = id;
        this.celestialBody = celestialBody;
        this.bufferCelestialBody = bufferCelestialBody;
    }

    public static void encode(SyncCelestialBodyCreate packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.id);
        CelestialBody body = Objects.requireNonNull(packet.celestialBody);
        byte type = switch (body) {
            case Star ignored -> TYPE_STAR;
            case Planet ignored -> TYPE_PLANET;
            case BlackHole ignored -> TYPE_BLACK_HOLE;
            case Meteoroid ignored -> TYPE_METEOROID;
            case Aircraft ignored -> TYPE_AIRCRAFT;
            default -> TYPE_BASE;
        };
        buffer.writeByte(type);
        body.encode(buffer);
    }

    public static SyncCelestialBodyCreate decode(FriendlyByteBuf buffer) {
        int id = buffer.readInt();
        // 共享底层数组、读写指针独立的副本：两边各自解出独立对象（见类注释）
        FriendlyByteBuf copyBuffer = new FriendlyByteBuf(buffer.copy());
        byte type = buffer.readByte();
        copyBuffer.readByte(); // 跳过副本里的同一个判别字节，让两边指针对齐
        CelestialBody celestialBody;
        CelestialBody bufferCelestialBody;
        switch (type) {
            case TYPE_BASE -> {
                celestialBody = CelestialBody.decode(buffer);
                bufferCelestialBody = CelestialBody.decode(copyBuffer);
            }
            case TYPE_STAR -> {
                celestialBody = Star.decode(buffer);
                bufferCelestialBody = Star.decode(copyBuffer);
            }
            case TYPE_PLANET -> {
                celestialBody = Planet.decode(buffer);
                bufferCelestialBody = Planet.decode(copyBuffer);
            }
            case TYPE_BLACK_HOLE -> {
                celestialBody = BlackHole.decode(buffer);
                bufferCelestialBody = BlackHole.decode(copyBuffer);
            }
            case TYPE_METEOROID -> {
                celestialBody = Meteoroid.decode(buffer);
                bufferCelestialBody = Meteoroid.decode(copyBuffer);
            }
            case TYPE_AIRCRAFT -> {
                celestialBody = Aircraft.decode(buffer);
                bufferCelestialBody = Aircraft.decode(copyBuffer);
            }
            default -> throw new IllegalArgumentException("Unknown CelestialBody type: " + type);
        }
        return new SyncCelestialBodyCreate(id, celestialBody, bufferCelestialBody);
    }

    public static void handle(SyncCelestialBodyCreate packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            PacketDistributor.sendToServer(new SyncCreateAck(packet.id));
            if (packet.celestialBody != null) {
                ClientSpaceWorld.getSpaceWorld().putCelestialBody(packet.celestialBody);
            }
            if (packet.bufferCelestialBody != null) {
                ClientSpaceWorld.getBufferSpaceWorld().putCelestialBody(packet.bufferCelestialBody);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
