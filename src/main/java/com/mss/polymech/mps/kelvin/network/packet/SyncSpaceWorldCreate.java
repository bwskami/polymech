package com.mss.polymech.mps.kelvin.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.SpaceWorld;
import com.mss.polymech.mps.space.network.packet.SyncCreateAck;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 太空世界创建同步 —— <b>与
 * {@code org.cn_grass_block.kelvin.network.packet.SyncSpaceWorldCreate} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 告诉客户端"你现在这个维度是一个太空世界"，并给出它的 id 与天空盒贴图。
 *
 * <h2>为什么一次发<b>两个</b>世界（照 space 0.1.3）</h2>
 * 客户端保留两个 {@link ClientSpaceWorld}：{@code spaceWorld}（对外可见/渲染）
 * 与 {@code bufferSpaceWorld}（网络缓冲区）。它们<b>必须同时建</b>：
 * 后续的 {@code SyncCelestialBodyCreate} 会分别往两个世界里各塞一份天体，
 * 而 {@code SyncCelestialBodyMoveBatch} 只写缓冲区。
 * 只发一个的话，客户端要么没有缓冲区可写、要么显示世界没天体可插值。
 * 所以反序列化时用<b>同样的 id/贴图各 new 一个</b>（不是同一个实例）。
 *
 * <h2>两种"空"的含义不同</h2>
 * {@code spaceWorld == null} 表示"这个维度不是太空"——客户端应当
 * {@link ClientSpaceWorld#init()} <b>清掉</b>旧世界，而不是留着上一局的影子世界。
 * 这就是"退出太空后行星还在天上飘"的根因所在，所以这个分支不能省。
 *
 * <p>回执照 {@code ReliableCreateSender} 的约定：写入客户端状态<b>之后</b>才发
 * {@link SyncCreateAck}（见该包注释）。</p>
 */
public class SyncSpaceWorldCreate implements CustomPacketPayload {

    private int id;
    public SpaceWorld spaceWorld;
    public SpaceWorld bufferSpaceWorld;
    public static final Type<SyncSpaceWorldCreate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_space_world_create"));
    public static final StreamCodec<FriendlyByteBuf, SyncSpaceWorldCreate> STREAM_CODEC =
            StreamCodec.ofMember(SyncSpaceWorldCreate::encode, SyncSpaceWorldCreate::decode);

    public SyncSpaceWorldCreate(SpaceWorld spaceWorld) {
        this(0, spaceWorld);
    }

    public SyncSpaceWorldCreate(int id, SpaceWorld spaceWorld) {
        this.id = id;
        this.spaceWorld = spaceWorld;
    }

    public SyncSpaceWorldCreate(SpaceWorld spaceWorld, SpaceWorld bufferSpaceWorld) {
        this.spaceWorld = spaceWorld;
        this.bufferSpaceWorld = bufferSpaceWorld;
    }

    public SyncSpaceWorldCreate(int id, SpaceWorld spaceWorld, SpaceWorld bufferSpaceWorld) {
        this.id = id;
        this.spaceWorld = spaceWorld;
        this.bufferSpaceWorld = bufferSpaceWorld;
    }

    public static void encode(SyncSpaceWorldCreate packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.id);
        SpaceWorld spaceWorld = packet.spaceWorld;
        if (spaceWorld == null) {
            buffer.writeBoolean(false);
        } else {
            buffer.writeBoolean(true);
            buffer.writeResourceLocation(spaceWorld.WorldID);
            buffer.writeResourceLocation(spaceWorld.SkyBoxTexture);
        }
    }

    public static SyncSpaceWorldCreate decode(FriendlyByteBuf buffer) {
        int id = buffer.readInt();
        if (buffer.readBoolean()) {
            ResourceLocation worldId = buffer.readResourceLocation();
            ResourceLocation skyBoxTexture = buffer.readResourceLocation();
            // 两个独立实例（见类注释）：显示世界与缓冲世界不能共享对象
            return new SyncSpaceWorldCreate(id,
                    new ClientSpaceWorld(worldId, skyBoxTexture),
                    new ClientSpaceWorld(worldId, skyBoxTexture));
        } else {
            return new SyncSpaceWorldCreate(id, null);
        }
    }

    public static void handle(SyncSpaceWorldCreate packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            PacketDistributor.sendToServer(new SyncCreateAck(packet.id));
            if (packet.spaceWorld == null) {
                ClientSpaceWorld.init();
            } else {
                ClientSpaceWorld.setSpaceWorld((ClientSpaceWorld) packet.spaceWorld);
                ClientSpaceWorld.setBufferSpaceWorld((ClientSpaceWorld) packet.bufferSpaceWorld);
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
