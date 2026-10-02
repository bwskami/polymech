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
 * 牵引枪<b>离散动作</b>（客户端 → 服务端）—— 照参考的 {@code PhysicsStaffActionPacket}。
 *
 * <h2>为什么需要它（右键从"长按"改成"开关"）</h2>
 * 原来右键是<b>长按</b>：原版 {@code use()} 开始、{@code onUseTick} 每 tick 施力、
 * {@code releaseUsing()} 松手 —— 施力挂在原版"使用中"这个状态上。
 * 参考不是这样：它的 {@code onUse} 在 <b>PRESS</b> 触发 {@code START_DRAG}，已经拖着时再按一次就
 * {@code stopDragging()}（<b>开关</b>），而且它在<b>客户端鼠标层就把这次点击 cancel 掉</b>
 * （{@code PhysicsStaffMouseHandler} + {@code MouseHandlerMixin}），原版<b>从不进入</b>使用状态。
 * 所以施力必须离开 {@code onUseTick}，改由服务端自己按 {@code GRABS} 表推进
 * （见 {@link PhysgunItem#serverTick}）—— 这个包就是那两下"按"的传声筒。
 *
 * <h2>服务端校验</h2>
 * 客户端可以改包，所以 {@link PhysgunItem#applyAction} 里会重新查"这个玩家是否确实拿着牵引枪"
 * （照参考的 {@code validateWorthyness}）。
 *
 * <p>⚠️ <b>有意偏离</b>：参考校验失败时 {@code context.disconnect("Invalid packet")}（踢人）。
 * 我们不踢，只静默拒绝 —— 因为存在<b>合法竞态</b>：客户端按下右键后立刻切走快捷栏，
 * 包到达时手里已经不是牵引枪了，踢人会误伤。拒绝已经足够（服务端本来就不信任这个包里的任何数值）。</p>
 *
 * @param action 动作
 * @param bodyId 客户端<b>认为</b>自己在操作哪个体（0 = 没打中）。仅作参考/回声用途，
 *               服务端<b>不</b>信任它：抓谁由服务端射线决定（见 {@link PhysgunAction} 的类注释）。
 */
public record PhysgunActionPacket(PhysgunAction action, long bodyId) implements CustomPacketPayload {

    public static final Type<PhysgunActionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physgun_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysgunActionPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysgunActionPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysgunActionPacket(PhysgunAction.STREAM_CODEC.decode(buf), buf.readVarLong());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysgunActionPacket p) {
                    PhysgunAction.STREAM_CODEC.encode(buf, p.action);
                    buf.writeVarLong(p.bodyId);
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysgunActionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer sp) {
                PhysgunItem.applyAction(sp, packet);
            }
        });
    }
}
