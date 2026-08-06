package com.mss.polymech.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * 传送带物品包（纯数据，无实体）。
 * <p>
 * 一个物品包 = 同种物品栈 + 带内进度（0=入口边中点，1=出口边中点）。
 * 双端各持一份：服务端权威，客户端确定性模拟，仅在结构变化时校正。
 * </p>
 * <ul>
 *   <li>{@code progress}：本 tick 结束时的进度</li>
 *   <li>{@code prevProgress}：上一 tick 的进度，供渲染 partialTick 插值</li>
 *   <li>{@code entryDir}：转弯入场的来源方向（3D data value），渲染平滑转向用；
 *       {@link #NO_ENTRY_TURN} 表示直线入场</li>
 * </ul>
 */
public class BeltItem {

    /** entryDir 的特殊值：直线入场（无转弯） */
    public static final byte NO_ENTRY_TURN = -1;

    private ItemStack stack;
    private double progress;
    private double prevProgress;
    private byte entryDir = NO_ENTRY_TURN;

    public BeltItem(ItemStack stack, double progress) {
        this.stack = stack;
        this.progress = progress;
        this.prevProgress = progress;
    }

    public ItemStack getStack() {
        return stack;
    }

    public int getCount() {
        return stack.getCount();
    }

    public boolean isEmpty() {
        return stack.isEmpty();
    }

    public void shrink(int amount) {
        stack.shrink(amount);
    }

    public double getProgress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = progress;
    }

    public double getPrevProgress() {
        return prevProgress;
    }

    public void setPrevProgress(double prevProgress) {
        this.prevProgress = prevProgress;
    }

    public byte getEntryDir() {
        return entryDir;
    }

    public void setEntryDir(byte entryDir) {
        this.entryDir = entryDir;
    }

    /**
     * 是否可以合并进另一个同种物品。
     */
    public boolean canMerge(ItemStack other, int limit) {
        return stack.getCount() < limit
                && ItemStack.isSameItemSameComponents(stack, other);
    }

    /**
     * 把 other 合并进本包（不超过 limit）。
     *
     * @return 合并后 other 的剩余数量
     */
    public int merge(ItemStack other, int limit) {
        int space = limit - stack.getCount();
        int take = Math.min(space, other.getCount());
        if (take > 0) {
            stack.grow(take);
        }
        return other.getCount() - take;
    }

    // ========== NBT ==========

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Stack", stack.saveOptional(registries));
        tag.putDouble("Progress", progress);
        tag.putByte("EntryDir", entryDir);
        return tag;
    }

    public static BeltItem load(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack stack = ItemStack.parseOptional(registries, tag.getCompound("Stack"));
        BeltItem item = new BeltItem(stack, tag.getDouble("Progress"));
        if (tag.contains("EntryDir")) {
            item.entryDir = tag.getByte("EntryDir");
        }
        return item;
    }
}
