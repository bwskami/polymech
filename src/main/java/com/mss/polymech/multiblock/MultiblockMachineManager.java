package com.mss.polymech.multiblock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.*;

/**
 * 多方块机器管理器
 * 负责管理所有的多方块机器实例
 */
public class MultiblockMachineManager {
    private static MultiblockMachineManager instance;

    private final Map<Level, Map<BlockPos, MultiblockMachineBlockEntity>> levelMachines;

    private MultiblockMachineManager() {
        this.levelMachines = new HashMap<>();
    }

    public static MultiblockMachineManager getInstance() {
        if (instance == null) {
            instance = new MultiblockMachineManager();
        }
        return instance;
    }

    /**
     * 注册一个多方位机器
     */
    public void registerMachine(Level level, MultiblockMachineBlockEntity machine) {
        levelMachines.computeIfAbsent(level, k -> new HashMap<>())
                   .put(machine.getBlockPos(), machine);
    }

    /**
     * 移除一个多方块机器
     */
    public void unregisterMachine(Level level, BlockPos masterPos) {
        Map<BlockPos, MultiblockMachineBlockEntity> machines = levelMachines.get(level);
        if (machines != null) {
            machines.remove(masterPos);
            if (machines.isEmpty()) {
                levelMachines.remove(level);
            }
        }
    }

    /**
     * 获取指定位置的多方块机器
     */
    public MultiblockMachineBlockEntity getMachineAt(Level level, BlockPos pos) {
        // 遍历该级别中的所有多方块机器，找到包含指定位置的机器
        Map<BlockPos, MultiblockMachineBlockEntity> machines = levelMachines.get(level);
        if (machines != null) {
            for (MultiblockMachineBlockEntity machine : machines.values()) {
                if (machine.isPartOfStructure(pos)) {
                    return machine;
                }
            }
        }
        return null;
    }

    /**
     * 检查指定位置是否属于某个多方块机器
     */
    public boolean isPartOfAnyMachine(Level level, BlockPos pos) {
        return getMachineAt(level, pos) != null;
    }

    /**
     * 当多方块机器中的一个方块被破坏时，触发整个机器的分解
     */
    public void onPartBroken(Level level, BlockPos brokenPos) {
        MultiblockMachineBlockEntity machine = getMachineAt(level, brokenPos);
        if (machine != null) {
            // 获取对应的多方块机器Block并执行破坏逻辑
            MultiblockMachineBlock block = machine.getMultiblockBlock();
            block.onPartBroken(level, machine.getBlockPos(), machine.getFacing());

            // 从管理器中移除
            unregisterMachine(level, machine.getBlockPos());
        }
    }
}
