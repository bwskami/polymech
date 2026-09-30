package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsClientHooks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 牵引枪光束广播（服务端 → 客户端附近的人）—— 照参考的
 * {@code PhysicsStaffBeamPacket(uuid, start, end)}。
 *
 * <h2>为什么必须有这个包（它不只是"给别人看"）</h2>
 * 第一版的实机症状是"抓住了、但是完全没有线"。根因在客户端：
 * <ul>
 *   <li>客户端在 {@code use()} 里<b>自己</b>用解析求交（{@code PhysgunTarget}）找目标，
 *       找不到就 {@code return success}（不 {@code startUsingItem}）⇒ 渲染器判据
 *       {@code player.isUsingItem()} 为假 ⇒ 一根线都不画；</li>
 *   <li>而服务端那一刻走的是 Rapier 射线（{@code PhysicalRaycast}），<b>它命中了</b>，
 *       于是聊天栏显示"已抓住"—— 两边对"抓没抓到"的判据根本不是同一套。</li>
 * </ul>
 * 所以抓取结果必须<b>由服务端权威地回给客户端</b>：客户端据此建光束，
 * 别人的枪也因此看得见。这同时消掉了"客户端镜子体与服务端不一致 ⇒ 光束指向另一个体"的一整类错。
 *
 * <h2>为什么发世界端点，而不是"体 id + 局部抓点"</h2>
 * 后者要点数少，但服务端的 {@code PhysicalBody} 用的是 UUID，客户端
 * {@code ClientPhysicsWorld} 用的是 {@code PhysicsBodyTracker} 的 long id ——
 * 中间还要过 {@code ProjectionManager} 的槽位。参考选择了直接发世界端点，
 * 这里跟它一致：<b>少一层 id 映射，就少一类"指错体"的错</b>。
 * 端点在服务端每 {@link #BROADCAST_INTERVAL_TICKS} tick 重算一次（抓点跟着刚体姿态走），
 * 客户端用上一帧→这一帧插值补平。
 */
public record PhysgunBeamPacket(UUID playerId,
                                double startX, double startY, double startZ,
                                double endX, double endY, double endZ,
                                double holdDistance, boolean released) implements CustomPacketPayload {

    /** 端点重算/广播的间隔（tick）：20Hz 的端点 + 客户端插值已经看不出阶梯。 */
    public static final int BROADCAST_INTERVAL_TICKS = 2;

    /** 广播半径（格）：比抓取距离 64 大一圈，让"远处看着别人拖船"也能看见光束。 */
    public static final double BROADCAST_RANGE = 128.0;

    public static final Type<PhysgunBeamPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physgun_beam"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysgunBeamPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysgunBeamPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysgunBeamPacket(buf.readUUID(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysgunBeamPacket p) {
                    buf.writeUUID(p.playerId);
                    buf.writeDouble(p.startX);
                    buf.writeDouble(p.startY);
                    buf.writeDouble(p.startZ);
                    buf.writeDouble(p.endX);
                    buf.writeDouble(p.endY);
                    buf.writeDouble(p.endZ);
                    buf.writeDouble(p.holdDistance);
                    buf.writeBoolean(p.released);
                }
            };

    /** 松手包：端点无意义，明确压成 0（读的人不用去猜上一包的端点还算不算数）。 */
    public static PhysgunBeamPacket released(UUID playerId) {
        return new PhysgunBeamPacket(playerId, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, true);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysgunBeamPacket packet, IPayloadContext context) {
        // 走 dist-safe 桥：真正的客户端写进 PhysgunClientState，服务端保持 no-op
        context.enqueueWork(() -> PhysicsClientHooks.acceptPhysgunBeam(packet));
    }
}
