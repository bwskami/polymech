package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsClientHooks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 挖掘进度同步包（服务端 → 客户端）。
 *
 * <p><b>为什么需要它</b>：物理体的方块不在世界里，原版那套挖掘进度
 * （{@code ServerPlayerGameMode} 的累积 + {@code ClientboundBlockDestructionPacket} 的裂纹）
 * 完全轮不到它们 —— 客户端只能自己算出"挖到几分了"，而那是**没有权威**的，
 * 别人也该看见你在挖哪一格。所以进度由服务端算（{@code state.getDestroyProgress(...)} 累加），
 * 再广播回来渲染裂纹。</p>
 *
 * <p>对应 space 0.1.3 的 {@code SyncPhysicalBlockBreakProgress}。</p>
 *
 * @param progress 0..1 的进度；<b>负数表示"停止挖掘 / 清除裂纹"</b>
 */
public record PhysicsBodyBreakProgressPacket(long bodyId, int dx, int dy, int dz, float progress)
        implements CustomPacketPayload {

    public static final Type<PhysicsBodyBreakProgressPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_break_progress"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodyBreakProgressPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodyBreakProgressPacket decode(RegistryFriendlyByteBuf buf) {
                    return new PhysicsBodyBreakProgressPacket(buf.readLong(),
                            buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodyBreakProgressPacket p) {
                    buf.writeLong(p.bodyId());
                    buf.writeVarInt(p.dx());
                    buf.writeVarInt(p.dy());
                    buf.writeVarInt(p.dz());
                    buf.writeFloat(p.progress());
                }
            };

    /** 服务端发来的"该清掉裂纹了"（progress < 0）。 */
    public boolean cleared() {
        return progress < 0.0F;
    }

    public static PhysicsBodyBreakProgressPacket clear(long bodyId, int dx, int dy, int dz) {
        return new PhysicsBodyBreakProgressPacket(bodyId, dx, dy, dz, -1.0F);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsBodyBreakProgressPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> PhysicsClientHooks.acceptBreakProgress(packet));
    }
}
