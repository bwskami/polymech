package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.space.network.packet.SyncCreateAck;
import com.mss.polymech.mps.thread.ClientCollisionPhysicalThread;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 启动客户端碰撞物理线程 —— <b>与
 * {@code org.polaris2023.mps.network.packet.SyncPhysicalThreadStart} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 只带一个回执 id 的"空包"，作用是<b>命令客户端把本地碰撞物理线程跑起来</b>。
 *
 * <h2>为什么用包来启动线程，而不是客户端自己启动（照 space 0.1.3）</h2>
 * 客户端的物理世界是<b>跟着服务端走的</b>：玩家可能连到一个根本没有物理世界的服
 * （或还没同步过来的服）。若客户端一进世界就自己开线程，那些服上就会白跑一个
 * 每 10ms 空转的线程。所以"要不要跑"由<b>服务端</b>决定，通过这个包下达。
 * <p>它走 {@code sendCritical}（重发 10 次 + 失败踢人）：这个包丢了，
 * 客户端的物理体会<b>永远停在原地</b>（没人推进运动学镜像），
 * 而服务端那边一切正常 —— 症状像"船在漂移但玩家站在上面滑走"，很难查。</p>
 */
public record SyncPhysicalThreadStart(int id) implements CustomPacketPayload {

    public static final Type<SyncPhysicalThreadStart> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "sync_physical_thread_start"));
    public static final StreamCodec<FriendlyByteBuf, SyncPhysicalThreadStart> STREAM_CODEC =
            StreamCodec.ofMember(SyncPhysicalThreadStart::encode, SyncPhysicalThreadStart::decode);

    public SyncPhysicalThreadStart() {
        this(0);
    }

    public static void encode(SyncPhysicalThreadStart packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.id);
    }

    public static SyncPhysicalThreadStart decode(FriendlyByteBuf buffer) {
        return new SyncPhysicalThreadStart(buffer.readInt());
    }

    public static void handle(SyncPhysicalThreadStart packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ClientCollisionPhysicalThread.startThread();
            // 线程已启动才算处理完（回执语义见 ReliableCreateSender）
            PacketDistributor.sendToServer(new SyncCreateAck(packet.id));
        });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
