package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsBodyTracker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 玩家推动物理体（客户端 → 服务端）。
 *
 * <p>玩家与物理体的碰撞是在客户端解算的（见 {@code PlayerPhysicsCollision}），
 * 所以"推得动船"必须回报给服务端：客户端在检测到与某刚体碰撞且自己在移动时发这个包，
 * 服务端把推力施加到刚体上。</p>
 *
 * <p><b>为什么不是"固定冲量"</b>：原来固定 24 N·s，而体素碰撞体的质量在 Rapier 里是
 * "一格 1 kg"，于是 24 N·s 打在几格的平台上 = <b>瞬加 24 m/s</b>；太空又没有摩擦，
 * 速度只累加不衰减，客户端每 4 tick 发一次 → 推两秒就几十 m/s。
 * 这就是"一碰就飞、根本没法测"的直接原因。</p>
 *
 * <p><b>现在改成速度目标型</b>：把刚体<b>沿推动方向</b>的速度补到不超过
 * {@link #MAX_PUSH_SPEED}（走路量级），所以无论客户端发得多快、多密，
 * 都不可能把目标推得比人走得还快 —— 自限幅，不依赖发包频率。
 * 再叠一个 {@link #IMPULSE_CEILING}，让"大船几乎推不动"这条设计意图继续成立。</p>
 */
public record PhysicsBodyPushPacket(long bodyId, float dirX, float dirZ, float strength)
        implements CustomPacketPayload {

    /** 推动能达到的最大速度（m/s）：走路量级，推不出"炮弹"。 */
    private static final double MAX_PUSH_SPEED = 4.0;
    /**
     * 单包最多补多少速度（m/s）。
     *
     * <p>客户端每 {@code PUSH_INTERVAL = 4} tick 发一包（5 包/秒），所以 0.8 m/s 的步长
     * 意味着"按住不放约 1 秒到走路速度" —— 有起步过程，而不是第一帧就窜到 4 m/s。</p>
     */
    private static final double MAX_PUSH_STEP = 0.8;
    /**
     * 单次冲量上限（N·s）：让"大船几乎推不动"继续成立。
     * 没有它的话，质量越大冲量越大，一条万吨船推久了也会被推走。
     */
    private static final double IMPULSE_CEILING = 3000.0;
    /** 服务端接受的最大距离（格）。 */
    private static final double MAX_RANGE = 6.0;

    public static final Type<PhysicsBodyPushPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_push"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodyPushPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodyPushPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysicsBodyPushPacket(buf.readLong(), buf.readFloat(), buf.readFloat(), buf.readFloat());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodyPushPacket p) {
                    buf.writeLong(p.bodyId);
                    buf.writeFloat(p.dirX);
                    buf.writeFloat(p.dirZ);
                    buf.writeFloat(p.strength);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsBodyPushPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!PhysicsBodyTracker.isNear(packet.bodyId(), player, MAX_RANGE)) {
                return;
            }
            double len = Math.sqrt(packet.dirX * packet.dirX + packet.dirZ * packet.dirZ);
            if (len < 1.0e-4) {
                return;
            }
            double strength = Math.max(0.0, Math.min(1.0, packet.strength));
            double mass = PhysicsBodyTracker.massOf(packet.bodyId());
            if (mass <= 0.0) {
                // 原生层过旧（拿不到质量）时按质量下限估：绝不退回"固定 24 N·s"
                mass = com.mss.polymech.physics.PhysicsBodyMass.MIN_BODY_MASS;
            }
            // 沿推动方向的当前速度（只取正向：目标已经在往这个方向走就不推了；
            // 往反方向冲过来的不在这里处理，否则会变成"用手挡住炮弹"的超人推力）
            double along = 0.0;
            double[] motion = PhysicsBodyTracker.motionOf(packet.bodyId());
            if (motion != null) {
                along = Math.max(0.0, (motion[0] * packet.dirX + motion[2] * packet.dirZ) / len);
            }
            double target = MAX_PUSH_SPEED * strength;
            double deltaV = Math.max(0.0, Math.min(target - along, MAX_PUSH_STEP));
            if (deltaV <= 1.0e-6) {
                return; // 已经推到目标速度：不再加冲量（这就是自限幅）
            }
            double impulse = Math.min(deltaV * mass, IMPULSE_CEILING);
            double scale = impulse / len;
            PhysicsBodyTracker.applyImpulse(packet.bodyId(),
                    packet.dirX * scale, 0.0, packet.dirZ * scale);
        });
    }
}
