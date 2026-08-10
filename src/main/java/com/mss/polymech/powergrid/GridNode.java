package com.mss.polymech.powergrid;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 电网节点标识。
 * <p>
 * 每个节点由「方块坐标 + 方块内节点ID」唯一定位。
 * 方块内的节点ID由实现 {@link GridNodeBlock} 的方块定义（默认单节点方块 ID=0）。
 * </p>
 *
 * @param nodeId     方块内节点ID（同方块多节点时区分，默认0）
 * @param sourcePos  承载节点的方块坐标
 */
public record GridNode(int nodeId, BlockPos sourcePos) {

    /** 持久化编解码器（SELECTED_NODE数据组件用） */
    public static final Codec<GridNode> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.fieldOf("node_id").forGetter(GridNode::nodeId),
            BlockPos.CODEC.fieldOf("source_pos").forGetter(GridNode::sourcePos)
    ).apply(inst, GridNode::new));

    /** 网络流编解码器（连接包同步用） */
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, GridNode> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, GridNode::nodeId,
                    BlockPos.STREAM_CODEC, GridNode::sourcePos,
                    GridNode::new
            );

    /**
     * 写入NBT。
     */
    public CompoundTag write(CompoundTag tag) {
        tag.putInt("NodeId", nodeId);
        tag.putInt("X", sourcePos.getX());
        tag.putInt("Y", sourcePos.getY());
        tag.putInt("Z", sourcePos.getZ());
        return tag;
    }

    /**
     * 从NBT读取。
     */
    public static GridNode read(CompoundTag tag) {
        return new GridNode(tag.getInt("NodeId"), new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")));
    }

    @Override
    public String toString() {
        return "N" + nodeId + "@" + sourcePos.toShortString();
    }
}
