package com.mss.polymech.block.large;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * 多方块机器部件跟踪器
 * 用于跟踪多方块机器的所有组成部分，以便于管理和同步
 */
public class MultiblockPartTracker {
    private final Set<BlockPos> parts = new HashSet<>();
    private BlockPos controllerPos;
    
    public MultiblockPartTracker(BlockPos controllerPos) {
        this.controllerPos = controllerPos;
        this.parts.add(controllerPos);
    }
    
    /**
     * 添加部件
     */
    public void addPart(BlockPos pos) {
        parts.add(pos);
    }
    
    /**
     * 移除部件
     */
    public void removePart(BlockPos pos) {
        parts.remove(pos);
        if (pos.equals(controllerPos)) {
            // 控制器被移除，需要重新选择控制器
            selectNewController();
        }
    }
    
    /**
     * 获取所有部件位置
     */
    public Set<BlockPos> getAllParts() {
        return new HashSet<>(parts);
    }
    
    /**
     * 获取控制器位置
     */
    public BlockPos getControllerPos() {
        return controllerPos;
    }
    
    /**
     * 设置控制器位置
     */
    public void setControllerPos(BlockPos pos) {
        controllerPos = pos;
    }
    
    /**
     * 选择新的控制器（当原控制器被破坏时）
     */
    private void selectNewController() {
        if (parts.isEmpty()) {
            controllerPos = null;
            return;
        }
        
        // 选择第一个可用的部件作为新的控制器
        controllerPos = parts.iterator().next();
    }
    
    /**
     * 检查指定位置是否为此多方块机器的一部分
     */
    public boolean isPartOfMultiblock(BlockPos pos) {
        return parts.contains(pos);
    }
    
    /**
     * 从NBT加载数据
     */
    public void load(CompoundTag tag) {
        parts.clear();
        
        if (tag.contains("controller_pos")) {
            int[] posArray = tag.getIntArray("controller_pos");
            if (posArray.length == 3) {
                controllerPos = new BlockPos(posArray[0], posArray[1], posArray[2]);
            }
        }
        
        if (tag.contains("parts")) {
            int[][] partsArray = new int[tag.getInt("parts_count")][];
            for (int i = 0; i < partsArray.length; i++) {
                partsArray[i] = tag.getIntArray("part_" + i);
                if (partsArray[i].length == 3) {
                    parts.add(new BlockPos(partsArray[i][0], partsArray[i][1], partsArray[i][2]));
                }
            }
        }
    }
    
    /**
     * 保存到NBT
     */
    public void save(CompoundTag tag) {
        if (controllerPos != null) {
            tag.putIntArray("controller_pos", new int[]{
                controllerPos.getX(), controllerPos.getY(), controllerPos.getZ()
            });
        }
        
        tag.putInt("parts_count", parts.size());
        int index = 0;
        for (BlockPos part : parts) {
            tag.putIntArray("part_" + index, new int[]{part.getX(), part.getY(), part.getZ()});
            index++;
        }
    }
    
    /**
     * 更新多方块结构
     */
    public void updateStructure(MultiblockStructure structure, BlockPos corePos) {
        parts.clear();
        parts.add(corePos); // 添加控制器位置
        
        // 添加所有占位方块位置
        for (BlockPos offset : structure.getOccupiedPositions()) {
            if (!offset.equals(BlockPos.ZERO)) { // 跳过控制器位置，因为它已经被添加
                parts.add(corePos.offset(offset));
            }
        }
        
        controllerPos = corePos;
    }
}