package com.mss.polymech.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 牵引枪的<b>离散动作</b> —— 照参考的 {@code PhysicsStaffAction}。
 *
 * <h2>为什么拖拽之外还要"动作"</h2>
 * 参考把牵引枪的输入拆成两类包：
 * <ul>
 *   <li>{@code PhysicsStaffDragPacket}：<b>连续量</b>（目标点 / 抓点 / 朝向），抓着的时候每 tick 发，
 *       服务端收到就更新会话（首次到达即<b>建</b>会话）；</li>
 *   <li>{@code PhysicsStaffActionPacket}：<b>离散事件</b>（{@code LOCK} / {@code STOP_DRAG}），
 *       按一下发一次，服务端据此切换状态。</li>
 * </ul>
 * 我们照这个分法：本枚举是后者。
 *
 * <h2>为什么我们比参考多一个 {@code START_DRAG}</h2>
 * 参考的"开始拖"是<b>隐式</b>的 —— 客户端自己射线选中了子级，直接把带目标 UUID 的拖拽包发上来，
 * 服务端的 {@code drag(...)} 首次收到就建会话（它<b>不</b>自己射线，信任客户端给的 UUID）。
 * <p>我们<b>不能</b>照这个来：本项目的服务端射线是<b>权威</b>的（客户端与服务端的体注册表
 * 曾经不一致，修法就是"服务端自己射线 + 两端同一套数学"，见 {@code PhysgunItem.raycast}），
 * 而且客户端镜像没同步上时会"打不中"—— 那条教训写在原来 {@code use()} 的注释里：
 * <b>打不中也要让服务端去抓</b>。所以"开始拖"必须是一个显式事件，由服务端射线决定抓谁。</p>
 *
 * <p>这是<b>既有</b>偏离（不是本次新引入的）：我们一直在服务端射线。</p>
 */
public enum PhysgunAction {

    /** 右键按下且当前没抓着 ⇒ 请服务端射线并开始拖。 */
    START_DRAG,

    /** 右键再按一次（或不再持枪）⇒ 松手。 */
    STOP_DRAG;

    /**
     * 编解码。
     *
     * <p>越界的 id（改包 / 版本错配）<b>回退成 {@link #STOP_DRAG}</b> —— 失败方向选"松手"：
     * 松手是无害的（最多把抓着的东西放下），而误当成 {@code START_DRAG} 会让服务端凭空去抓东西。</p>
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, PhysgunAction> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysgunAction decode(RegistryFriendlyByteBuf buf) {
                    int id = buf.readVarInt();
                    PhysgunAction[] all = values();
                    return id >= 0 && id < all.length ? all[id] : STOP_DRAG;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysgunAction action) {
                    buf.writeVarInt(action.ordinal());
                }
            };
}
