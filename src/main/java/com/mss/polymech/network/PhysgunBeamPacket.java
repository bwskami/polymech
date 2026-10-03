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
 *   <li>客户端<b>自己</b>用解析求交（{@code PhysgunTarget}）找目标，找不到就什么都不做
 *       ⇒ 客户端这边一根线都不画；</li>
 *   <li>而服务端那一刻走的是自己的射线，<b>它命中了</b>，
 *       于是聊天栏显示"已抓住"—— 两边对"抓没抓到"的判据根本不是同一套。</li>
 * </ul>
 * 所以抓取结果必须<b>由服务端权威地回给客户端</b>：客户端据此建光束，
 * 别人的枪也因此看得见。这同时消掉了"客户端镜子体与服务端不一致 ⇒ 光束指向另一个体"的一整类错。
 *
 * <p>⚠️ 这段注释原来还提到 {@code startUsingItem} / {@code isUsingItem()} —— 那是右键还是
 * <b>长按</b>（挂原版"使用中"状态）时的旧设计。现在照参考改成了<b>开关</b>，原版使用状态
 * 根本不会进入（客户端在鼠标层就把右键 cancel 了），渲染器判据也早已换成
 * {@code PhysgunClientState}（见 {@code PhysgunBeamRenderer}）。</p>
 *
 * <h2>★ 为什么发的是「体局部抓点」，而不是世界端点（2026-10-03 改）</h2>
 * 原来这里发的是 {@code startX/Y/Z + endX/Y/Z}（世界坐标），客户端再插值 —— 那是
 * <b>把派生量当成一等公民</b>，代价是必然的两个：服务端按 tick 采样体位姿 ⇒ 20Hz 的世界点，
 * 而物体被拖过地面时接触解算抖动是 100Hz 级 ⇒ <b>混叠</b>（玩家一动就抽、不动就好）；
 * 客户端为消相位跳变又必须付 ≥2 拍的渲染延迟。
 *
 * <p>参考实现存的是 {@code dragLocalAnchor}（局部抓点），渲染时用<b>客户端自己的</b>
 * {@code subLevel.renderPose(pt)} 变换到世界（{@code PhysicsStaffClientHandler:134/239/374-384}）。
 * 现在照它做：本包只带 {@link BodyLocalAnchor}（概念），世界坐标由客户端每帧现算
 * —— 混叠与延迟一起消失。见 {@link BodyLocalAnchor} 的类注释。</p>
 *
 * <p><b>起点仍发世界坐标</b>：那是"枪口"，属于<b>玩家实体</b>的位姿（客户端本来就有、按帧插值），
 * 频率低、不随物体运动，不构成混叠；把它也改成派生是另一件事（要和"第一人称枪口"那套对齐）。</p>
 */
public record PhysgunBeamPacket(UUID playerId,
                                double startX, double startY, double startZ,
                                long bodyId, double localX, double localY, double localZ,
                                double holdDistance, boolean released) implements CustomPacketPayload {

    /**
     * 端点重算/广播的间隔（tick）——<b>1 = 每 tick（20Hz）</b>。
     *
     * <p>原来是 2（10Hz）。改成 1 有两个原因，都与"抽抽"直接相关：</p>
     * <ol>
     *   <li><b>采样越稀，每拍跳变越大</b>：时间戳插值消掉的是"相位错位造成的跳变"，
     *       但样本之间仍然是折线；采样间隔越长，折线的拐点越明显；</li>
     *   <li><b>它决定渲染延迟</b>：延迟必须 ≥ 2 × 发包间隔（见
     *       {@code PhysgunClientState.INTERP_DELAY_SECONDS}，那里就是从本常量推出来的）。
     *       间隔 0.1s ⇒ 延迟 0.2s，光束末端会比物体明显拖后；间隔 0.05s ⇒ 延迟 0.1s，
     *       与天空那条线（{@code CelestialBody.INTERP_DELAY_SECONDS = 0.10}）取的是同一个数。</li>
     * </ol>
     */
    public static final int BROADCAST_INTERVAL_TICKS = 1;

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
                            buf.readVarLong(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readBoolean());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysgunBeamPacket p) {
                    buf.writeUUID(p.playerId);
                    buf.writeDouble(p.startX);
                    buf.writeDouble(p.startY);
                    buf.writeDouble(p.startZ);
                    buf.writeVarLong(p.bodyId);
                    buf.writeDouble(p.localX);
                    buf.writeDouble(p.localY);
                    buf.writeDouble(p.localZ);
                    buf.writeDouble(p.holdDistance);
                    buf.writeBoolean(p.released);
                }
            };

    /** 松手包：抓点无意义，明确压成 0（读的人不用去猜上一包的抓点还算不算数）。 */
    public static PhysgunBeamPacket released(UUID playerId) {
        return new PhysgunBeamPacket(playerId, 0.0, 0.0, 0.0, 0L, 0.0, 0.0, 0.0, 0.0, true);
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
