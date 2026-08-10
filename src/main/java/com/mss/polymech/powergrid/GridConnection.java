package com.mss.polymech.powergrid;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 电网连接（一段电线）。
 * <p>
 * 记录两个节点之间的电线连接及其电气属性。
 * 无向连接：{@code node1} 与 {@code node2} 的先后顺序不具方向性。
 * </p>
 *
 * @param node1    端点节点1
 * @param node2    端点节点2
 * @param wireType 电线类型
 * @param length   电线长度（格，直线距离近似）
 */
public record GridConnection(GridNode node1, GridNode node2, GridWireType wireType, double length) {

    /** 网络流编解码器（客户端同步用） */
    public static final StreamCodec<RegistryFriendlyByteBuf, GridConnection> STREAM_CODEC = StreamCodec.composite(
            GridNode.STREAM_CODEC, GridConnection::node1,
            GridNode.STREAM_CODEC, GridConnection::node2,
            GridWireType.STREAM_CODEC, GridConnection::wireType,
            net.minecraft.network.codec.ByteBufCodecs.DOUBLE, GridConnection::length,
            GridConnection::new
    );

    /** 写入NBT（WorldPowerGrid持久化用） */
    public CompoundTag write(CompoundTag tag) {
        tag.put("Node1", node1.write(new CompoundTag()));
        tag.put("Node2", node2.write(new CompoundTag()));
        tag.putString("WireType", wireType.name());
        tag.putDouble("Length", length);
        return tag;
    }

    /** 从NBT读取 */
    public static GridConnection read(CompoundTag tag) {
        GridNode n1 = GridNode.read(tag.getCompound("Node1"));
        GridNode n2 = GridNode.read(tag.getCompound("Node2"));
        GridWireType type = GridWireType.valueOf(tag.getString("WireType"));
        return new GridConnection(n1, n2, type, tag.getDouble("Length"));
    }

    /** 连接的总电阻（Ω），按长度线性计算 */
    public double getResistance() {
        return Math.max(0.00001, wireType.getResistance() * length);
    }

    /** 判断连接是否与给定方块位置相关 */
    public boolean touches(BlockPos pos) {
        return node1.sourcePos().equals(pos) || node2.sourcePos().equals(pos);
    }

    /** 判断连接是否与给定具体节点相关 */
    public boolean touches(GridNode node) {
        return node1.equals(node) || node2.equals(node);
    }

    @Override
    public String toString() {
        return "GridConnection[" + node1 + " <-> " + node2 + " (" + wireType + ", " + (int) length + "m)]";
    }
}
