package com.mss.polymech.mps.space.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld;
import com.mss.polymech.mps.kelvin.physical.space_world.ClientSpaceWorld;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;

/**
 * 同步结束标记 —— <b>与 {@code org.deep_space_studio.space.network.packet.SyncCreateEnd}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 一串创建包发完后，服务端把"<b>我这边应该是什么样</b>"摘要发过来；
 * 客户端拿它跟自己的实际状态对账，不一致就<b>大声报错</b>。
 *
 * <h2>为什么要有这个包（照 space 0.1.3）</h2>
 * 前面那些创建包都是"逐个塞数据"，塞漏了<b>不会报错</b>：客户端只是少一个天体，
 * 表现是"某颗行星不显示"或"落地算错位置"，这类症状极难定位到网络层。
 * 本包把预期值（有没有太空世界/几个天体/哪个地表世界/有没有物理世界）
 * 一次性带过来，让客户端能主动发现"我收到的和它要给我的不一致"。
 * <p>它是<b>诊断</b>而不是修复：不补数据、不重连，只在日志里写清楚差在哪，
 * 并在玩家 actionbar 上提示。这正是它存在的意义 —— 把静默不一致变成可见错误。</p>
 *
 * <p>四个字段都是"摘要"而不是"数据"：数量/名字/有无，足以判定不一致，
 * 又不会把包变大。注意它<b>不参与</b> {@code ReliableCreateSender} 之外的重试
 * （发它的一方走同一套重发），客户端也不回执。</p>
 */
public record SyncCreateEnd(boolean hasSpaceWorld, int celestialBodyCount, String celestialWorldBodyName,
                            boolean hasPhysicalWorld) implements CustomPacketPayload {

    public static final Type<SyncCreateEnd> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_create_end"));
    public static final StreamCodec<FriendlyByteBuf, SyncCreateEnd> STREAM_CODEC =
            StreamCodec.ofMember(SyncCreateEnd::encode, SyncCreateEnd::decode);

    private static void encode(SyncCreateEnd packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.hasSpaceWorld);
        buffer.writeInt(packet.celestialBodyCount);
        buffer.writeBoolean(packet.celestialWorldBodyName != null);
        if (packet.celestialWorldBodyName != null) {
            buffer.writeUtf(packet.celestialWorldBodyName);
        }
        buffer.writeBoolean(packet.hasPhysicalWorld);
    }

    private static SyncCreateEnd decode(FriendlyByteBuf buffer) {
        boolean hasSpaceWorld = buffer.readBoolean();
        int count = buffer.readInt();
        String name = buffer.readBoolean() ? buffer.readUtf() : null;
        boolean hasPhysicalWorld = buffer.readBoolean();
        return new SyncCreateEnd(hasSpaceWorld, count, name, hasPhysicalWorld);
    }

    public static void handle(SyncCreateEnd packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> Minecraft.getInstance().execute(() -> verify(packet)));
    }

    /** 四项对账；任何一项不符都写 error 日志（不吞、不猜）。 */
    private static void verify(SyncCreateEnd packet) {
        Minecraft mc = Minecraft.getInstance();
        ClientSpaceWorld spaceWorld = ClientSpaceWorld.getSpaceWorld();
        boolean ok = true;
        if (packet.hasSpaceWorld != (spaceWorld != null)) {
            Polymech.LOGGER.error("[SyncCreateEnd] 太空世界不匹配：期望 {}，实际 {}",
                    packet.hasSpaceWorld, spaceWorld != null);
            ok = false;
        }

        if (spaceWorld != null && packet.celestialBodyCount != spaceWorld.getAllCelestialBody().size()) {
            Polymech.LOGGER.error("[SyncCreateEnd] 天体数量不匹配：期望 {}，实际 {}",
                    packet.celestialBodyCount, spaceWorld.getAllCelestialBody().size());
            ok = false;
        }

        ClientCelestialWorld celestialWorld = ClientCelestialWorld.getCelestialWorld();
        String actualName = celestialWorld == null ? null : celestialWorld.celestialBody.getName();
        if (!Objects.equals(packet.celestialWorldBodyName, actualName)) {
            Polymech.LOGGER.error("[SyncCreateEnd] 天体世界不匹配：期望 {}，实际 {}",
                    packet.celestialWorldBodyName, actualName);
            ok = false;
        }

        ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
        if (packet.hasPhysicalWorld != (physicalWorld != null)) {
            Polymech.LOGGER.error("[SyncCreateEnd] 物理世界不匹配：期望 {}，实际 {}",
                    packet.hasPhysicalWorld, physicalWorld != null);
            ok = false;
        }

        if (!ok && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c[Polymech] 切世界数据同步不完整，详见日志"), true);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
