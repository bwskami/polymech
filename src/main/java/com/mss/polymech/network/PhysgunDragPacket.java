package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.PhysgunItem;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 牵引枪<b>拖拽指令</b>（客户端 → 服务端）—— 照参考的 {@code PhysicsStaffDragPacket}。
 *
 * <h2>为什么必须有这个包</h2>
 * 抓取本身是服务端权威的（服务端射线决定抓谁），而<b>旋转</b>的输入只在客户端
 * （鼠标移动）。所以客户端必须把"我想让这个体转到哪个朝向"和"持握距离改成多少"
 * 发给服务端，由服务端每 tick 用角伺服去实现 —— 参考也是这么做的：
 * <pre>
 *   // PhysicsStaffDragPacket.handle → PhysicsStaffServerHandler.drag(...)
 *   session.orientation.set(orientation);      // 下一个物理 tick 就用它建约束的马达
 * </pre>
 *
 * <h2>为什么发<b>绝对朝向</b>而不是增量</h2>
 * 参考发的是绝对朝向（{@code Quaterniondc orientation}）。绝对量对丢包是自愈的
 * （丢一包下一包仍然正确），增量则会累积误差。代价是客户端必须知道体的当前朝向 ——
 * 这一点客户端有（{@code ClientPhysics.liveTransform} 给镜子体的实时位姿），
 * 所以"第一次旋转从当前朝向起步"也能做对（否则物体第一帧会啪地跳到单位朝向）。
 *
 * <h2>为什么带 {@code holdDistance}</h2>
 * 参考的滚轮改的是持握距离（{@code dragSession.setDistance(...)}），
 * 而距离在服务端的 {@code Grab} 里。不发过来的话滚轮就只能改客户端的光束、改不动物理。
 *
 * <h2>服务端校验</h2>
 * 客户端可能被改包，所以 {@link PhysgunItem#applyDrag} 里会校验
 * "这个玩家确实抓着这个体"，并把距离钳在 {@code [2, RANGE]}、把四元数归一化。
 */
public record PhysgunDragPacket(long bodyId, double holdDistance,
                                float qx, float qy, float qz, float qw) implements CustomPacketPayload {

    public static final Type<PhysgunDragPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physgun_drag"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysgunDragPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysgunDragPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysgunDragPacket(buf.readVarLong(), buf.readDouble(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysgunDragPacket p) {
                    buf.writeVarLong(p.bodyId);
                    buf.writeDouble(p.holdDistance);
                    buf.writeFloat(p.qx);
                    buf.writeFloat(p.qy);
                    buf.writeFloat(p.qz);
                    buf.writeFloat(p.qw);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysgunDragPacket packet, IPayloadContext context) {
        // 服务端权威：真正的写入口在 PhysgunItem（GRABS 是服务端状态）。
        // 这里不用 dist-safe 桥 —— 与 PhysgunBeamPacket 相反，这个包的方向是 客户端→服务端，
        // 处理逻辑本身就在通用代码里，两端都能加载。
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                PhysgunItem.applyDrag(sp, packet);
            }
        });
    }
}
