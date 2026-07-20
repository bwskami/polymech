package com.mss.polymech.block.large;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * 多方块机器控制器接口
 * 提供多方块机器的通用操作方法
 */
public interface IMultiblockController {
    
    /**
     * 验证多方块结构是否完整
     */
    boolean isStructureFormed();
    
    /**
     * 检查多方块结构是否有效
     */
    boolean validateStructure();
    
    /**
     * 形成多方块结构
     */
    boolean formStructure();
    
    /**
     * 拆解多方块结构
     */
    void dismantleStructure();
    
    /**
     * 获取多方块结构的中心位置
     */
    BlockPos getCentralPos();
    
    /**
     * 获取多方块机器所在的世界
     */
    Level getLevel();
    
    /**
     * 当多方块结构发生变化时调用
     */
    void onStructureChange();
}