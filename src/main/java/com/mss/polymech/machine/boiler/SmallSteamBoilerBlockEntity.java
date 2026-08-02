package com.mss.polymech.machine.boiler;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.fluid.ModFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 小型蒸汽锅炉（单方块机器，1 倍并行）。
 * <p>
 * 3 个槽位：水输入(0)、燃料(1)、蒸汽输出(2)。
 * 无桶转换、无灰烬输出。
 * </p>
 */
public class SmallSteamBoilerBlockEntity extends AbstractSteamBoilerBlockEntity {

    private static final int INPUT_WATER_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_STEAM_SLOT = 2;

    public SmallSteamBoilerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SMALL_STEAM_BOILER.get(), pos, state, 100, 4000, 4000);
    }

    // ==================== 抽象方法实现 ====================

    @Override protected int getParallel() { return 1; }
    @Override protected int getFuelSlot() { return FUEL_SLOT; }
    @Override protected int getInvSize() { return 3; }
    @Override protected int getOutputSlotIndex() { return OUTPUT_STEAM_SLOT; }
    @Override protected int getPowerCostPerTick() { return 5; }

    @Override
    protected ContainerData createPropertyDelegate() {
        return new ContainerData() {
            @Override public int get(int index) {
                return switch (index) {
                    case 0 -> SmallSteamBoilerBlockEntity.this.progress;
                    case 1 -> SmallSteamBoilerBlockEntity.this.maxProgress;
                    case 2 -> SmallSteamBoilerBlockEntity.this.enable ? 1 : 0;
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) {
                switch (index) {
                    case 0 -> SmallSteamBoilerBlockEntity.this.progress = value;
                    case 1 -> SmallSteamBoilerBlockEntity.this.maxProgress = value;
                    case 2 -> SmallSteamBoilerBlockEntity.this.enable = value == 1;
                }
            }
            @Override public int getCount() { return 3; }
        };
    }

    @Override
    protected IItemHandler getInput() { return new InputHandler(itemStackHandler); }

    @Override
    protected IItemHandler getOutput() { return new OutputHandler(itemStackHandler); }

    // ==================== 槽位验证 ====================

    @Override
    protected boolean isItemValidForSlot(int slot, @NotNull ItemStack stack) {
        return switch (slot) {
            case INPUT_WATER_SLOT -> stack.getItem() == Items.WATER_BUCKET;
            case FUEL_SLOT -> isFuel(stack);
            case OUTPUT_STEAM_SLOT -> false;
            default -> false;
        };
    }

    // ==================== 配方 ====================

    @Override
    protected Optional<RecipeHolder<?>> getMatchRecipe(Level world) { return Optional.empty(); }

    @Override
    protected void craftItem(Level world) {
        // 燃料消耗由基类 onTick 处理
    }

    @Override
    protected boolean hasCorrectRecipe(Level world) {
        ItemStack fuelStack = itemStackHandler.getStackInSlot(FUEL_SLOT);
        if (fuelStack.isEmpty() || !isFuel(fuelStack)) return false;

        boolean hasWater = waterTank.getFluidAmount() >= 1;
        if (!hasWater) {
            ItemStack waterStack = itemStackHandler.getStackInSlot(INPUT_WATER_SLOT);
            if (waterStack.isEmpty() || waterStack.getItem() != Items.WATER_BUCKET) {
                return false;
            }
        }

        return steamTank.getFluidAmount() < steamCapacity;
    }

    // ==================== MenuProvider ====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.poly_mech.small_steam_boiler");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }

    // ==================== 内部类：输入/输出处理器 ====================

    private record InputHandler(ItemStackHandler parent) implements IItemHandler {
        @Override public int getSlots() { return 2; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot == 0) return parent.getStackInSlot(INPUT_WATER_SLOT);
            if (slot == 1) return parent.getStackInSlot(FUEL_SLOT);
            return ItemStack.EMPTY;
        }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (slot == 0) {
                if (stack.getItem() == Items.WATER_BUCKET) {
                    return parent.insertItem(INPUT_WATER_SLOT, stack, simulate);
                }
            } else if (slot == 1) {
                if (isFuel(stack)) {
                    return parent.insertItem(FUEL_SLOT, stack, simulate);
                }
            }
            return stack;
        }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot == 0) return stack.getItem() == Items.WATER_BUCKET;
            if (slot == 1) return isFuel(stack);
            return false;
        }
    }

    private record OutputHandler(ItemStackHandler parent) implements IItemHandler {
        @Override public int getSlots() { return 1; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot == 0) return parent.getStackInSlot(OUTPUT_STEAM_SLOT);
            return ItemStack.EMPTY;
        }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack;
        }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0) return parent.extractItem(OUTPUT_STEAM_SLOT, amount, simulate);
            return ItemStack.EMPTY;
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return false; }
    }
}
