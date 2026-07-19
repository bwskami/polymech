package com.mss.polymech.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 多方块机器的BlockEntity - 管理整个多方块结构
 */
public class MultiblockMachineBlockEntity extends BlockEntity {
    private List<BlockPos> occupiedPositions;
    private Direction facing;
    private MultiblockMachineBlock multiblockBlock;

    public MultiblockMachineBlockEntity(BlockPos pos, MultiblockMachineBlock multiblockBlock, List<BlockPos> occupiedPositions, Direction facing) {
        super(multiblockBlock.getEntityType(), pos, multiblockBlock.defaultBlockState());
        this.multiblockBlock = multiblockBlock;
        this.occupiedPositions = occupiedPositions;
        this.facing = facing;
    }

    /**
     * 当多方块机器被破坏时调用
     */
    public void onDestroyed() {
        // 清理相关资源
        this.occupiedPositions = null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        // 保存占用位置和朝向信息
        CompoundTag positionsTag = new CompoundTag();
        positionsTag.putInt("count", occupiedPositions.size());

        for (int i = 0; i < occupiedPositions.size(); i++) {
            BlockPos pos = occupiedPositions.get(i);
            positionsTag.putInt("x_" + i, pos.getX());
            positionsTag.putInt("y_" + i, pos.getY());
            positionsTag.putInt("z_" + i, pos.getZ());
        }

        tag.put("occupiedPositions", positionsTag);
        tag.putInt("facing", facing.get3DDataValue());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        // 加载占用位置信息
        CompoundTag positionsTag = tag.getCompound("occupiedPositions");
        int count = positionsTag.getInt("count");
        this.occupiedPositions = new java.util.ArrayList<>();

        for (int i = 0; i < count; i++) {
            int x = positionsTag.getInt("x_" + i);
            int y = positionsTag.getInt("y_" + i);
            int z = positionsTag.getInt("z_" + i);
            this.occupiedPositions.add(new BlockPos(x, y, z));
        }

        this.facing = Direction.from3DDataValue(tag.getInt("facing"));
    }

    public List<BlockPos> getOccupiedPositions() {
        return occupiedPositions;
    }

    public Direction getFacing() {
        return facing;
    }

    public MultiblockMachineBlock getMultiblockBlock() {
        return multiblockBlock;
    }

    /**
     * 检查指定位置是否是此多方块机器的一部分
     */
    public boolean isPartOfStructure(BlockPos pos) {
        return occupiedPositions.contains(pos);
    }
}
