package com.mss.polymech.mps.kelvin.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_body.variant.Planet;
import com.mss.polymech.mps.kelvin.physical.celestial_world.CelestialWorld;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import com.mss.polymech.mps.space.network.packet.SyncCreateAck;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.joml.Vector2d;

/**
 * 天体世界（行星地表）创建同步 —— <b>与
 * {@code org.cn_grass_block.kelvin.network.packet.SyncCelestialWorldCreate} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 告诉客户端"你现在这颗行星的地表维度是哪一个，以及它的一组换算参数"：
 * 贴图投影（{@code center/rotate/longitudeLength}）、重力 {@code G}、
 * 高度归一上界 {@code height}、地表下界 {@code minY}。
 *
 * <h2>为什么这些参数必须发（不能客户端自己算）</h2>
 * 它们全部来自数据包的 {@code world/*.json}，也就是<b>服务端的存档/数据包状态</b>；
 * 客户端既没有这份数据，也无法从维度本身推出来。少了任意一个，
 * {@link CelestialWorld#getSpacePosFromWorldPos} 与
 * {@code getWorldPosFromSpacePos} 的换算就是错的 ——
 * 表现是"从地表起飞后在太空里的位置偏了"或"从太空落回地表时落点错"。
 *
 * <h2>两个"空"分支的语义（照 space 0.1.3）</h2>
 * <ul>
 *   <li>{@code bodyName = null}（用 {@code new SyncCelestialWorldCreate()} /
 *       {@code (int)} 构造）表示"<b>这个维度不是行星地表</b>"——客户端必须
 *       {@link ClientCelestialWorld#init()} 清掉缓存，否则走出地表后
 *       还留着上一颗行星的参数，重力与坐标换算全用错的那套。</li>
 *   <li>{@code bodyName != null} 时<b>要在太空世界里按名字找那颗行星</b>
 *       （{@link Planet} 才有半径/大气/卡门线），而不是自己 new 一个：
 *       客户端拿到的行星对象必须与渲染用的是同一个实例，否则参数会分叉。</li>
 * </ul>
 *
 * <p><b>一处有意的加固</b>：space 写的是
 * {@code ClientSpaceWorld.getSpaceWorld().getCelestialBody(...)}，
 * 当"地表世界存在、而它的太空世界没登记上"（数据包只写了 {@code world/*.json}、
 * 太空世界注册失败）时，{@code getSpaceWorld()} 为 null 会<b>直接 NPE 崩客户端</b>。
 * 这里加了一个 null 判断走"不是行星地表"分支 —— 语义正确且不崩。
 * 正常路径（太空世界已同步）逐行同形。</p>
 */
public class SyncCelestialWorldCreate implements CustomPacketPayload {

    private int id;
    private String bodyName;
    private ResourceLocation worldId;
    private ResourceLocation spaceWorldId;
    private Vector2d center;
    private double rotate;
    private double longitudeLength;
    private double G;
    private double height;
    private double minY;
    public static final Type<SyncCelestialWorldCreate> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_celestial_world_create"));
    public static final StreamCodec<FriendlyByteBuf, SyncCelestialWorldCreate> STREAM_CODEC =
            StreamCodec.ofMember(SyncCelestialWorldCreate::encode, SyncCelestialWorldCreate::decode);

    public SyncCelestialWorldCreate(String bodyName, ResourceLocation worldId, ResourceLocation spaceWorldId,
                                    Vector2d center, double rotate, double longitudeLength,
                                    double G, double height, double minY) {
        this(0, bodyName, worldId, spaceWorldId, center, rotate, longitudeLength, G, height, minY);
    }

    public SyncCelestialWorldCreate(int id, String bodyName, ResourceLocation worldId, ResourceLocation spaceWorldId,
                                    Vector2d center, double rotate, double longitudeLength,
                                    double G, double height, double minY) {
        this.id = id;
        this.bodyName = bodyName;
        this.worldId = worldId;
        this.spaceWorldId = spaceWorldId;
        this.center = center;
        this.rotate = rotate;
        this.longitudeLength = longitudeLength;
        this.G = G;
        this.height = height;
        this.minY = minY;
    }

    /** "这个维度不是行星地表"（只带 id 以便回执）。 */
    public SyncCelestialWorldCreate() {
    }

    public SyncCelestialWorldCreate(int id) {
        this.id = id;
    }

    public static void encode(SyncCelestialWorldCreate packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.id);
        if (packet.bodyName == null) {
            buffer.writeBoolean(false);
        } else {
            buffer.writeBoolean(true);
            buffer.writeUtf(packet.bodyName);
            buffer.writeResourceLocation(packet.worldId);
            buffer.writeResourceLocation(packet.spaceWorldId);
            buffer.writeDouble(packet.center.x);
            buffer.writeDouble(packet.center.y);
            buffer.writeDouble(packet.rotate);
            buffer.writeDouble(packet.longitudeLength);
            buffer.writeDouble(packet.G);
            buffer.writeDouble(packet.height);
            buffer.writeDouble(packet.minY);
        }
    }

    public static SyncCelestialWorldCreate decode(FriendlyByteBuf buffer) {
        int id = buffer.readInt();
        if (buffer.readBoolean()) {
            String bodyName = buffer.readUtf();
            ResourceLocation worldId = buffer.readResourceLocation();
            ResourceLocation spaceWorldId = buffer.readResourceLocation();
            Vector2d center = new Vector2d(buffer.readDouble(), buffer.readDouble());
            double rotate = buffer.readDouble();
            double longitudeLength = buffer.readDouble();
            double G = buffer.readDouble();
            double height = buffer.readDouble();
            double minY = buffer.readDouble();
            return new SyncCelestialWorldCreate(id, bodyName, worldId, spaceWorldId, center, rotate,
                    longitudeLength, G, height, minY);
        } else {
            return new SyncCelestialWorldCreate(id);
        }
    }

    public static void handle(SyncCelestialWorldCreate packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            PacketDistributor.sendToServer(new SyncCreateAck(packet.id));
            ClientSpaceWorld spaceWorld = ClientSpaceWorld.getSpaceWorld();
            // 加固点见类注释：太空世界缺失时按"不是行星地表"处理，而不是 NPE
            if (packet.bodyName != null && spaceWorld != null
                    && spaceWorld.getCelestialBody(packet.bodyName) instanceof Planet planet) {
                ClientCelestialWorld cw = new ClientCelestialWorld(planet, packet.worldId, packet.spaceWorldId);
                cw.setPosShadowData(new CelestialWorld.posShadowData(packet.center, packet.rotate, packet.longitudeLength));
                cw.G = packet.G;
                cw.Height = packet.height;
                cw.MinY = packet.minY;
                ClientCelestialWorld.setCelestialWorld(cw);
            } else {
                ClientCelestialWorld.init();
            }
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
