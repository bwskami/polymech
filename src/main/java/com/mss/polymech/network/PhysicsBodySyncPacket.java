package com.mss.polymech.network;

import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PhysicsClientHooks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理刚体同步包（服务端 → 客户端）：让客户端知道"有哪些物理体、在哪、由哪些方块组成"，
 * 从而能画出来。
 *
 * <p>三种动作：</p>
 * <ul>
 *   <li>{@link Action#CREATE}：携带方块快照（局部坐标 + 方块状态），客户端据此建网格；</li>
 *   <li>{@link Action#UPDATE}：只带位置与旋转（每 tick 发，客户端做插值）；</li>
 *   <li>{@link Action#REMOVE}：销毁。</li>
 * </ul>
 *
 * <p>本类不含任何客户端类引用（通过 {@link PhysicsClientHooks} 转发），
 * 保证专用服务端不会因加载客户端类而崩溃。</p>
 */
public record PhysicsBodySyncPacket(
        Action action,
        long bodyId,
        double x, double y, double z,
        float qx, float qy, float qz, float qw,
        List<BlockEntry> blocks,
        double vx, double vy, double vz,
        double avx, double avy, double avz
) implements CustomPacketPayload {

    public enum Action {
        CREATE,
        UPDATE,
        REMOVE
    }

    /** 方块条目：体素局部坐标 + 方块状态 id。 */
    public record BlockEntry(short dx, short dy, short dz, int stateId) {
    }

    public static final Type<PhysicsBodySyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "physics_body_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PhysicsBodySyncPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public PhysicsBodySyncPacket decode(RegistryFriendlyByteBuf buf) {
                    Action action = Action.values()[buf.readByte()];
                    long bodyId = buf.readLong();
                    double x = buf.readDouble();
                    double y = buf.readDouble();
                    double z = buf.readDouble();
                    float qx = buf.readFloat();
                    float qy = buf.readFloat();
                    float qz = buf.readFloat();
                    float qw = buf.readFloat();
                    List<BlockEntry> blocks = List.of();
                    if (action == Action.CREATE) {
                        int count = buf.readVarInt();
                        blocks = new ArrayList<>(count);
                        for (int i = 0; i < count; i++) {
                            blocks.add(new BlockEntry(buf.readShort(), buf.readShort(), buf.readShort(), buf.readVarInt()));
                        }
                    }
                    return new PhysicsBodySyncPacket(action, bodyId, x, y, z, qx, qy, qz, qw, blocks,
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, PhysicsBodySyncPacket p) {
                    buf.writeByte(p.action.ordinal());
                    buf.writeLong(p.bodyId);
                    buf.writeDouble(p.x);
                    buf.writeDouble(p.y);
                    buf.writeDouble(p.z);
                    buf.writeFloat(p.qx);
                    buf.writeFloat(p.qy);
                    buf.writeFloat(p.qz);
                    buf.writeFloat(p.qw);
                    if (p.action == Action.CREATE) {
                        buf.writeVarInt(p.blocks.size());
                        for (BlockEntry entry : p.blocks) {
                            buf.writeShort(entry.dx());
                            buf.writeShort(entry.dy());
                            buf.writeShort(entry.dz());
                            buf.writeVarInt(entry.stateId());
                        }
                    }
                    // 线速度：照 MPS 的 SyncPhysicalBodyMove（setPos + setLinSpeed），
                    // 客户端刚体靠它才能在接触求解里"带着正确的速度"推玩家，
                    // 否则就是一堵瞬移的零速度墙 —— 撞上去的弹出方向会乱。
                    buf.writeDouble(p.vx);
                    buf.writeDouble(p.vy);
                    buf.writeDouble(p.vz);
                    // 角速度：MPS 的 SyncPhysicalBodyMove 里的 RotSpeed。
                    // 客户端 DYNAMIC 刚体靠它自己转，碰撞体姿态才会跟着船一起转。
                    buf.writeDouble(p.avx);
                    buf.writeDouble(p.avy);
                    buf.writeDouble(p.avz);
                }
            };

    public static PhysicsBodySyncPacket create(long bodyId, double x, double y, double z,
                                               float qx, float qy, float qz, float qw,
                                               List<BlockEntry> blocks) {
        return new PhysicsBodySyncPacket(Action.CREATE, bodyId, x, y, z, qx, qy, qz, qw, blocks,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    public static PhysicsBodySyncPacket update(long bodyId, double x, double y, double z,
                                               float qx, float qy, float qz, float qw) {
        return update(bodyId, x, y, z, qx, qy, qz, qw, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /** 带线速度的 UPDATE（服务端每 tick 发，对应 MPS 的 {@code SyncPhysicalBodyMove}）。 */
    public static PhysicsBodySyncPacket update(long bodyId, double x, double y, double z,
                                               float qx, float qy, float qz, float qw,
                                               double vx, double vy, double vz) {
        return update(bodyId, x, y, z, qx, qy, qz, qw, vx, vy, vz, 0.0, 0.0, 0.0);
    }

    /** 线速度 + 角速度都带的 UPDATE（对齐 MPS：Pos + LivSpeed + RotSpeed）。 */
    public static PhysicsBodySyncPacket update(long bodyId, double x, double y, double z,
                                               float qx, float qy, float qz, float qw,
                                               double vx, double vy, double vz,
                                               double avx, double avy, double avz) {
        return new PhysicsBodySyncPacket(Action.UPDATE, bodyId, x, y, z, qx, qy, qz, qw, List.of(),
                vx, vy, vz, avx, avy, avz);
    }

    public static PhysicsBodySyncPacket remove(long bodyId) {
        return new PhysicsBodySyncPacket(Action.REMOVE, bodyId, 0, 0, 0, 0, 0, 0, 1, List.of(),
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    /** 把方块状态转成网络可传的 id（客户端用 {@link Block#stateById(int)} 还原）。 */
    public static int stateId(net.minecraft.world.level.block.state.BlockState state) {
        return Block.getId(state);
    }

    public static net.minecraft.world.level.block.state.BlockState stateFrom(int id) {
        return Block.stateById(id);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PhysicsBodySyncPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> PhysicsClientHooks.acceptBodySync(packet));
    }
}
