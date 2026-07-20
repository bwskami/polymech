package com.mss.polymech.block.large;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;

/**
 * 多方块机器注册表
 * 用于管理和查找不同的多方块机器类型
 */
public class MultiblockMachineRegistry {
    private static final Map<ResourceLocation, Class<? extends AbstractMultiblockMachine>> MULTIBLOCK_REGISTRY = new HashMap<>();
    
    /**
     * 注册多方块机器类型
     */
    public static void register(ResourceLocation id, Class<? extends AbstractMultiblockMachine> machineClass) {
        MULTIBLOCK_REGISTRY.put(id, machineClass);
    }
    
    /**
     * 根据ID获取多方块机器类型
     */
    public static Class<? extends AbstractMultiblockMachine> getMachineClass(ResourceLocation id) {
        return MULTIBLOCK_REGISTRY.get(id);
    }
    
    /**
     * 检查某个方块是否为多方块机器
     */
    public static boolean isMultiblockMachine(Block block) {
        return block instanceof AbstractMultiblockMachine;
    }
    
    /**
     * 检查某个方块是否为占位方块
     */
    public static boolean isPlaceholderBlock(Block block) {
        return block instanceof MultiblockPlaceholder;
    }
    
    /**
     * 获取所有注册的多方块机器类型
     */
    public static Map<ResourceLocation, Class<? extends AbstractMultiblockMachine>> getAllRegisteredMachines() {
        return new HashMap<>(MULTIBLOCK_REGISTRY);
    }
}