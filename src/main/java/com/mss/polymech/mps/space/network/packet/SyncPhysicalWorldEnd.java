package com.mss.polymech.mps.space.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 物理世界同步结束标记 —— <b>与
 * {@code org.deep_space_studio.space.network.packet.SyncPhysicalWorldEnd} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 一串物理体创建包发完后，服务端把"<b>我这边应该是什么样</b>"摘要发过来
 * （哪个维度有物理世界、里面几个体），客户端对账，不一致就报错。
 *
 * <h2>为什么这个包值得存在（照 space 0.1.3）</h2>
 * 与 kelvin 的 {@code SyncCreateEnd} 同理：逐个塞数据的包<b>漏了不会报错</b>，
 * 客户端只是少一个体，表现是"某艘船看不见"或"上去就穿模"，
 * 极难定位到网络层。这个包把预期值带过来，把<b>静默不一致变成可见错误</b>。
 * 它是<b>诊断</b>不是修复：不补数据、不重连，只写日志并在 actionbar 提示。
 *
 * <p>{@code worldId == null} 表示"<b>这个维度本来就没有物理世界</b>"——
 * 这是一种合法预期，不是错误；只有"客户端却有"才算不一致。</p>
 */
public record SyncPhysicalWorldEnd(ResourceLocation worldId, int physicalBodyCount)
        implements CustomPacketPayload {

    public static final Type<SyncPhysicalWorldEnd> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_world_end"));
    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalWorldEnd> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalWorldEnd::encode, SyncPhysicalWorldEnd::decode);

    private static void encode(SyncPhysicalWorldEnd packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.worldId != null);
        if (packet.worldId != null) {
            buffer.writeResourceLocation(packet.worldId);
        }
        buffer.writeInt(packet.physicalBodyCount);
    }

    private static SyncPhysicalWorldEnd decode(FriendlyByteBuf buffer) {
        ResourceLocation worldId = buffer.readBoolean() ? buffer.readResourceLocation() : null;
        int count = buffer.readInt();
        return new SyncPhysicalWorldEnd(worldId, count);
    }

    public static void handle(SyncPhysicalWorldEnd packet, IPayloadContext ctx) {
        // 再套一层 execute：对账要读客户端世界状态，必须回到主线程
        ctx.enqueueWork(() -> Minecraft.getInstance().execute(() -> verify(packet)));
    }

    /** 两项对账：世界有无/是否同一个、体的数量。 */
    private static void verify(SyncPhysicalWorldEnd packet) {
        Minecraft mc = Minecraft.getInstance();
        ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
        boolean ok = true;
        if (packet.worldId == null) {
            if (physicalWorld != null) {
                Polymech.LOGGER.error("[SyncPhysicalWorldEnd] 物理世界应不存在，实际存在 {}", physicalWorld.getLevel());
                ok = false;
            }
        } else if (physicalWorld == null || !packet.worldId.equals(physicalWorld.getLevel())) {
            Polymech.LOGGER.error("[SyncPhysicalWorldEnd] 物理世界不匹配：期望 {}，实际 {}",
                    packet.worldId, physicalWorld == null ? null : physicalWorld.getLevel());
            ok = false;
        } else if (packet.physicalBodyCount != physicalWorld.getAllPhysicalBody().size()) {
            Polymech.LOGGER.error("[SyncPhysicalWorldEnd] 物理体数量不匹配：期望 {}，实际 {}",
                    packet.physicalBodyCount, physicalWorld.getAllPhysicalBody().size());
            ok = false;
        }

        if (!ok && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§c[Polymech] 物理世界同步不完整，详见日志"), true);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
