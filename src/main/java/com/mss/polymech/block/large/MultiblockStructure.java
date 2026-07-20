package com.mss.polymech.block.large;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 多方块结构定义
 * 定义了多方块机器的占用位置、碰撞箱和交互行为
 */
public class MultiblockStructure {
    private final Set<BlockPos> occupiedPositions;
    private final Direction facing;
    
    // 用于动态调整碰撞箱的边界
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    
    // 用于验证结构的谓词
    private final Predicate<BlockState> coreBlockPredicate;
    private final Predicate<BlockState> placeholderBlockPredicate;

    public MultiblockStructure(Set<BlockPos> occupiedPositions, Direction facing) {
        this(occupiedPositions, facing, 
             state -> state.getBlock() instanceof AbstractMultiblockMachine,
             state -> state.getBlock() instanceof MultiblockPlaceholder);
    }
    
    public MultiblockStructure(Set<BlockPos> occupiedPositions, Direction facing, 
                              Predicate<BlockState> coreBlockPredicate, 
                              Predicate<BlockState> placeholderBlockPredicate) {
        this.occupiedPositions = Collections.unmodifiableSet(new HashSet<>(occupiedPositions));
        this.facing = facing;
        this.coreBlockPredicate = coreBlockPredicate;
        this.placeholderBlockPredicate = placeholderBlockPredicate;
        
        // 计算边界
        if (occupiedPositions.isEmpty()) {
            minX = minY = minZ = maxX = maxY = maxZ = 0;
        } else {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            
            for (BlockPos pos : occupiedPositions) {
                minX = Math.min(minX, pos.getX());
                minY = Math.min(minY, pos.getY());
                minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX());
                maxY = Math.max(maxY, pos.getY());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    /**
     * 获取结构占用的所有位置（相对于核心位置）
     */
    public Set<BlockPos> getOccupiedPositions() {
        return occupiedPositions;
    }

    /**
     * 获取结构朝向
     */
    public Direction getFacing() {
        return facing;
    }

    /**
     * 获取结构的边界框
     */
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }

    /**
     * 检查给定位置是否属于此结构
     */
    public boolean contains(BlockPos relativePos) {
        return occupiedPositions.contains(relativePos);
    }

    /**
     * 验证结构在给定世界中是否有效
     */
    public boolean isValidStructure(Level level, BlockPos corePos) {
        for (BlockPos offset : occupiedPositions) {
            BlockPos worldPos = corePos.offset(offset);
            BlockState state = level.getBlockState(worldPos);

            if (offset.equals(BlockPos.ZERO)) {
                // 核心位置必须是主方块
                if (!getCoreBlockPredicate().test(state)) {
                    return false;
                }
            } else {
                // 占位位置必须是占位方块
                if (!getPlaceholderBlockPredicate().test(state)) {
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * 获取结构的大小（占用的方块数量）
     */
    public int getSize() {
        return occupiedPositions.size();
    }
    
    /**
     * 获取结构的体积（边界框体积）
     */
    public long getVolume() {
        return (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    /**
     * 获取核心方块的验证谓词
     */
    public Predicate<BlockState> getCoreBlockPredicate() {
        return coreBlockPredicate;
    }

    /**
     * 获取占位方块的验证谓词
     */
    public Predicate<BlockState> getPlaceholderBlockPredicate() {
        return placeholderBlockPredicate;
    }
    
    /**
     * 获取占位方块的状态（用于放置）
     */
    public BlockState getPlaceholderState() {
        // 返回默认的占位方块状态
        // 注意：这里需要实际获取占位方块的状态，可能需要通过ModBlocks访问
        return null; // 这个方法会在具体实现中被使用
    }
}