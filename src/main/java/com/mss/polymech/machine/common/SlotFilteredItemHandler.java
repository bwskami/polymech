package com.mss.polymech.machine.common;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 槽位过滤 IItemHandler 视图：仅暴露父库存中指定的内部槽位。
 * <p>
 * 用于侧面方块能力代理——Block 定义每个位置暴露的槽位列表，
 * 本类将主方块 BE 的 ItemStackHandler 包装为外部索引 0..n-1
 * 映射到内部 slots[i] 的视图。槽位验证（isItemValid）直接委托
 * 给父 handler，因此 BE 中定义的 GUI 验证规则自动生效。
 * </p>
 *
 * @param parent 主方块 BE 的内部 ItemStackHandler
 * @param slots  暴露的内部槽位索引数组
 */
public record SlotFilteredItemHandler(ItemStackHandler parent, int[] slots) implements IItemHandler {

    @Override
    public int getSlots() {
        return slots.length;
    }

    private int internal(int slot) {
        if (slot < 0 || slot >= slots.length) {
            throw new IndexOutOfBoundsException("Slot " + slot + " out of range [0, " + slots.length + ")");
        }
        return slots[slot];
    }

    @Override
    public @NotNull ItemStack getStackInSlot(int slot) {
        return parent.getStackInSlot(internal(slot));
    }

    @Override
    public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        return parent.insertItem(internal(slot), stack, simulate);
    }

    @Override
    public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
        return parent.extractItem(internal(slot), amount, simulate);
    }

    @Override
    public int getSlotLimit(int slot) {
        return parent.getSlotLimit(internal(slot));
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        return parent.isItemValid(internal(slot), stack);
    }
}
