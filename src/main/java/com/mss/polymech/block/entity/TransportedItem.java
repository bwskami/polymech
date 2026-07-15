package com.mss.polymech.block.entity;

import net.minecraft.world.item.ItemStack;

/**
 * 传送带上的运输物品数据。
 * <p>
 * 封装了物品堆栈和其在传送带上的进度位置。
 * 采用不可变设计，外部只能通过 getter 访问数据副本。
 * </p>
 */
public class TransportedItem {
    private final ItemStack stack;
    private double progress;

    public TransportedItem(ItemStack stack, double startProgress) {
        this.stack = stack.copy();
        this.progress = startProgress;
    }

    /**
     * @return 物品堆栈的副本（防止外部修改）
     */
    public ItemStack getStack() {
        return stack.copy();
    }

    /**
     * @return 当前进度 0.0 ~ 1.0
     */
    public double getProgress() {
        return progress;
    }

    /**
     * 设置进度值，自动钳制在 [0.0, 1.0] 范围内。
     */
    public void setProgress(double progress) {
        this.progress = Math.clamp(progress, 0.0, 1.0);
    }

    /**
     * @return 物品是否已到达传送带末端
     */
    public boolean isFinished() {
        return progress >= 1.0;
    }

    /**
     * @return 内部物品堆栈的引用（仅限包内使用，用于传输操作）
     */
    ItemStack internalStack() {
        return stack;
    }
}