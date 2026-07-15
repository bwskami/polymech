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

    /**
     * 创建一个运输物品（永远只携带1个物品）。
     * <p>
     * 传入的堆叠如果是多个，只会取1个。这样传送带吞吐量只与速度挂钩，
     * 不受堆叠大小影响，也为未来不同等级传送带（不同速度）奠定了基础。
     * </p>
     *
     * @param stack         物品堆叠（只取1个）
     * @param startProgress 起始进度
     */
    public TransportedItem(ItemStack stack, double startProgress) {
        this.stack = stack.copyWithCount(1);
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