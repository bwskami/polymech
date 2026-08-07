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
 *   <li>{@code lastDrivenTick}：创建 tick 印记——跨线交接新建的包在创建当 tick
 *       不移动（下一 tick 起步），双端节奏确定、与 BE tick 顺序无关</li>
 * </ul>
 * <p>
 * 传送带上物品包<b>永不合并</b>：每批独立通过，保护特地设计的物流分批；
 * 合并需求由专门的设备承担。
 * </p>
 */
public class BeltItem {

    /** entryDir 的特殊值：直线入场（无转弯） */
    public static final byte NO_ENTRY_TURN = -1;

    private ItemStack stack;
    private double progress;
    private double prevProgress;
    private byte entryDir = NO_ENTRY_TURN;
    private long lastDrivenTick = -1L;

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

    public long getLastDrivenTick() {
        return lastDrivenTick;
    }

    public void setLastDrivenTick(long lastDrivenTick) {
        this.lastDrivenTick = lastDrivenTick;
    }

    // ========== NBT ==========

    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Stack", stack.saveOptional(registries));
        tag.putDouble("Progress", progress);
        // PrevPos 一并序列化（Create 同款）：客户端收到快照后直接用服务端的
        // prev→progress 继续插值，不会因 prev==progress 冻结一帧产生顿挫
        tag.putDouble("PrevPos", prevProgress);
        tag.putByte("EntryDir", entryDir);
        tag.putLong("LastDrivenTick", lastDrivenTick);
        return tag;
    }

    public static BeltItem load(CompoundTag tag, HolderLookup.Provider registries) {
        ItemStack stack = ItemStack.parseOptional(registries, tag.getCompound("Stack"));
        BeltItem item = new BeltItem(stack, tag.getDouble("Progress"));
        if (tag.contains("PrevPos")) {
            item.prevProgress = tag.getDouble("PrevPos");
        }
        if (tag.contains("EntryDir")) {
            item.entryDir = tag.getByte("EntryDir");
        }
        if (tag.contains("LastDrivenTick")) {
            item.lastDrivenTick = tag.getLong("LastDrivenTick");
        }
        return item;
    }
}
