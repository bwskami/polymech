package com.mss.polymech.mps.network.packet;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.physical.manger.ProjectionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * 物理体交互动作（客户端 → 服务端）—— <b>与
 * {@code org.polaris2023.mps.network.packet.PhysicalBodyInteractionPacket} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §20）。
 *
 * <h2>职责</h2>
 * 把"我要对<b>这个物理体的这一格</b>做攻击/使用/停手"发给服务端。
 *
 * <h2>为什么只发 uuid + 动作，不发坐标</h2>
 * 服务端<b>自己重新打一次射线</b>（{@code ProjectionManager#handleInteraction} 里
 * 用玩家的眼位与视线在投影维度里重算）。这是<b>反作弊与权威性</b>的要求：
 * 客户端报的命中点可以被伪造，而"玩家此刻看向哪里"服务端本来就有。
 * 客户端那一侧的射线只用来决定"要不要吞掉原版事件、画不画瞄准框"。
 *
 * <h2>三个动作字节</h2>
 * {@code 0=ATTACK}、{@code 1=ATTACK_STOP}、{@code 2=USE}
 * （常量在 {@link ProjectionManager} 上）。<b>{@code ATTACK_STOP} 是本协议的精髓</b>：
 * 原版没有"松手"包，服务端若不被告知，裂纹与挖掘进度会一直留着。
 *
 * <p>手（主/副）编成<b>一个 boolean</b>而不是写枚举序号：只有两只手，
 * 而且这样协议里不会出现"枚举加了一项导致旧客户端解错"的风险。</p>
 */
public record PhysicalBodyInteractionPacket(UUID physicalBodyId, byte action, InteractionHand hand)
        implements CustomPacketPayload {

    public static final Type<PhysicalBodyInteractionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physical_body_interaction"));
    public static final StreamCodec<FriendlyByteBuf, PhysicalBodyInteractionPacket> STREAM_CODEC =
            StreamCodec.ofMember(PhysicalBodyInteractionPacket::encode, PhysicalBodyInteractionPacket::decode);

    private static void encode(PhysicalBodyInteractionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.physicalBodyId);
        buffer.writeByte(packet.action);
        buffer.writeBoolean(packet.hand == InteractionHand.OFF_HAND);
    }

    private static PhysicalBodyInteractionPacket decode(FriendlyByteBuf buffer) {
        return new PhysicalBodyInteractionPacket(buffer.readUUID(), buffer.readByte(),
                buffer.readBoolean() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
    }

    public static void handle(PhysicalBodyInteractionPacket packet, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer) {
            context.enqueueWork(() -> ProjectionManager.handleInteraction(
                    serverPlayer, packet.physicalBodyId, packet.action, packet.hand));
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
