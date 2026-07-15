package com.mss.polymech.block;

/**
 * 传送带规格配置。
 *
 * @param slotCount    缓存容量（最多同时容纳的物品数）
 * @param ticksPerSlot 每个物品在一个槽位中停留的 tick 数
 */
public record ConveyorSpec(int slotCount, int ticksPerSlot) {
    /** 默认规格：3 槽位，每槽 8 tick（物品间距更大，适配大模型） */
    public static final ConveyorSpec DEFAULT = new ConveyorSpec(3, 8);

    public ConveyorSpec {
        if (slotCount < 1)     throw new IllegalArgumentException("slotCount must be >= 1");
        if (ticksPerSlot < 1)  throw new IllegalArgumentException("ticksPerSlot must be >= 1");
    }
}