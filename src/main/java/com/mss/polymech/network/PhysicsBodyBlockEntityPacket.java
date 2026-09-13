package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsClientHooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理体的<b>方块实体</b>同步包（服务端 → 客户端）—— 对应 space/MPS 的
 * {@code SyncPhysicalBodyBlockEntity}。
 *
 * <p><b>为什么必须有这个包</b>：箱子、熔炉、告示牌这类方块的渲染形状是
 * {@code RenderShape.ENTITYBLOCK_ANIMATED} —— 它们的方块模型是 {@code builtin/entity}（空的），
 * 客户端只能靠 {@code BlockEntityRenderer} 画出来。而物理体的普通渲染通道只烘方块模型，
 * 于是这些方块会<b>整个透明</b>（看不见但碰撞还在）。把方块实体 NBT 发过来，
 * 客户端就能在物理体的姿态下用原版 BE 渲染器画出来。</p>
 *
 * <p>只发 {@code (局部坐标, NBT)}：方块状态客户端已经有（CREATE 里的方块快照），
 * 用它 + NBT 就能 {@code BlockEntity.loadStatic} 还原出一个可渲染的方块实体。</p>
 *
 * <p>本类不引用任何客户端类（通过 {@link PhysicsClientHooks} 转发），专用服务端安全。</p>
 */
public record PhysicsBodyBlockEntityPacket(long bodyId, List<Entry> entries) implements CustomPacketPayload {

    /** 一个方块实体：体素局部坐标 + 完整 NBT。 */
    public record Entry(short dx, short dy, short dz, CompoundTag tag) {
    }

    public static final Type<PhysicsBodyBlockEntityPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_block_entity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodyBlockEntityPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodyBlockEntityPacket decode(RegistryFriendlyByteBuf buf) {
                    long bodyId = buf.readLong();
                    int count = buf.readVarInt();
                    List<Entry> entries = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        short dx = buf.readShort();
                        short dy = buf.readShort();
                        short dz = buf.readShort();
                        CompoundTag tag = buf.readNbt();
                        entries.add(new Entry(dx, dy, dz, tag == null ? new CompoundTag() : tag));
                    }
                    return new PhysicsBodyBlockEntityPacket(bodyId, entries);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodyBlockEntityPacket p) {
                    buf.writeLong(p.bodyId);
                    buf.writeVarInt(p.entries.size());
                    for (Entry e : p.entries) {
                        buf.writeShort(e.dx());
                        buf.writeShort(e.dy());
                        buf.writeShort(e.dz());
                        buf.writeNbt(e.tag());
                    }
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsBodyBlockEntityPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> PhysicsClientHooks.acceptBodyBlockEntities(packet));
    }
}
