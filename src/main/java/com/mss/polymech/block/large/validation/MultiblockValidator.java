package com.mss.polymech.block.large.validation;

import com.mss.polymech.block.large.MultiblockStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 多方块机器验证器
 * 用于验证多方块结构的有效性和完整性
 */
public class MultiblockValidator {
    
    /**
     * 验证多方块结构是否完整
     */
    public static boolean validateStructure(Level level, BlockPos corePos, MultiblockStructure structure) {
        for (BlockPos offset : structure.getOccupiedPositions()) {
            BlockPos worldPos = corePos.offset(offset);
            
            if (!level.hasChunkAt(worldPos)) {
                // 如果区块未加载，则认为无效
                return false;
            }
            
            BlockState state = level.getBlockState(worldPos);
            
            if (offset.equals(BlockPos.ZERO)) {
                // 核心位置必须是正确的多方块机器
                if (!structure.getCoreBlockPredicate().test(state)) {
                    return false;
                }
            } else {
                // 占位位置必须是正确的占位方块
                if (!structure.getPlaceholderBlockPredicate().test(state)) {
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * 检查结构是否有损坏（某些部分缺失）
     */
    public static boolean isStructureIntact(Level level, BlockPos corePos, MultiblockStructure structure) {
        // 首先检查基本结构
        if (!validateStructure(level, corePos, structure)) {
            return false;
        }
        
        // 可以添加更复杂的验证逻辑，如能量连接、流体连接等
        return true;
    }
    
    /**
     * 修复结构（尝试重建损坏的部分）
     */
    public static boolean attemptRepair(Level level, BlockPos corePos, MultiblockStructure structure) {
        boolean repaired = false;
        
        for (BlockPos offset : structure.getOccupiedPositions()) {
            BlockPos worldPos = corePos.offset(offset);
            BlockState currentState = level.getBlockState(worldPos);
            
            if (offset.equals(BlockPos.ZERO)) {
                // 核心位置应该存在
                if (!structure.getCoreBlockPredicate().test(currentState)) {
                    // 尝试恢复核心方块 - 这通常不可能，因为这会导致无限递归
                    return false;
                }
            } else {
                // 占位位置缺失，需要重新放置
                if (!structure.getPlaceholderBlockPredicate().test(currentState)) {
                    level.setBlock(worldPos, structure.getPlaceholderState(), 3);
                    repaired = true;
                }
            }
        }
        
        return repaired;
    }
    
    /**
     * 获取结构中缺失的方块位置
     */
    public static java.util.List<BlockPos> getMissingParts(Level level, BlockPos corePos, MultiblockStructure structure) {
        java.util.List<BlockPos> missingParts = new java.util.ArrayList<>();
        
        for (BlockPos offset : structure.getOccupiedPositions()) {
            BlockPos worldPos = corePos.offset(offset);
            BlockState state = level.getBlockState(worldPos);
            
            if (offset.equals(BlockPos.ZERO)) {
                if (!structure.getCoreBlockPredicate().test(state)) {
                    missingParts.add(worldPos);
                }
            } else {
                if (!structure.getPlaceholderBlockPredicate().test(state)) {
                    missingParts.add(worldPos);
                }
            }
        }
        
        return missingParts;
    }
}