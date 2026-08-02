package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.BaseIOBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
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
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Optional;
import java.util.Set;

public class HorizontalSteamBoilerBlockEntity extends BaseIOBlockEntity implements GeoBlockEntity {

    /** 放置动画：先播放一次 building，再循环 working */
    private static final RawAnimation BUILDING_ANIM = RawAnimation.begin()
            .thenPlay("building")
            .thenLoop("working");
    /** 已放置过：直接循环 working */
    private static final RawAnimation WORKING_ANIM = RawAnimation.begin()
            .thenLoop("working");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    /** 是否已经播放过放置动画（NBT持久化，区块重载不重播，重新放置会再次播放） */
    private boolean buildingAnimPlayed = false;
    /** 服务端放置后经过的 tick 数，用于在服务端标记动画已播完 */
    private int placeTicks = 0;
    /** 蓝图预览虚影标记：为 true 时跳过建造动画，直接显示 working 状态 */
    public boolean isGhostPreview = false;

    private static final int INPUT_LIQUID_SLOT = 0;
    private static final int FUEL_SLOT = 1;
    private static final int OUTPUT_LIQUID_SLOT = 2;
    private static final int OUTPUT_ASH_SLOT = 3;
    private static final int POWER_PER_TICK = 5;

    private static final Set<net.minecraft.world.item.Item> FUEL_ITEMS = Set.of(
            Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK,
            Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.JUNGLE_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
            Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS, Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.JUNGLE_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
            Items.STICK, Items.BLAZE_ROD, Items.LAVA_BUCKET
    );

    public HorizontalSteamBoilerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HORIZONTAL_STEAM_BOILER.get(), pos, state, 100);
    }

    @Override
    protected int getPowerCostPerTick() { return POWER_PER_TICK; }

    @Override
    protected ContainerData createPropertyDelegate() {
        return new ContainerData() {
            @Override public int get(int index) {
                return switch (index) {
                    case 0 -> HorizontalSteamBoilerBlockEntity.this.progress;
                    case 1 -> HorizontalSteamBoilerBlockEntity.this.maxProgress;
                    case 2 -> HorizontalSteamBoilerBlockEntity.this.enable ? 1 : 0;
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) {
                switch (index) {
                    case 0 -> HorizontalSteamBoilerBlockEntity.this.progress = value;
                    case 1 -> HorizontalSteamBoilerBlockEntity.this.maxProgress = value;
                    case 2 -> HorizontalSteamBoilerBlockEntity.this.enable = value == 1;
                }
            }
            @Override public int getCount() { return 3; }
        };
    }

    @Override
    protected IItemHandler getInput() { return new InputHandler(itemStackHandler); }

    @Override
    protected IItemHandler getOutput() { return new OutputHandler(itemStackHandler); }

    @Override protected int getInvSize() { return 4; }
    @Override protected int getOutputSlotIndex() { return OUTPUT_LIQUID_SLOT; }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0,
                this::animationController));
    }

    /**
     * 动画控制器状态回调（仅客户端执行）：
     * - 服务端已标记 buildingAnimPlayed=true 时（重新进游戏/区块重载），直接循环 working
     * - 否则播放 building 动画，客户端检测完成后标记
     */
    private PlayState animationController(final AnimationState<HorizontalSteamBoilerBlockEntity> state) {
        // 蓝图预览虚影：直接显示 working 状态，不播放建造动画
        if (isGhostPreview) {
            return state.setAndContinue(WORKING_ANIM);
        }
        if (!buildingAnimPlayed) {
            if (state.isCurrentAnimationStage("working")) {
                // 客户端检测：building 阶段已完成，标记并通知服务端保存
                buildingAnimPlayed = true;
                setChanged();
            } else {
                return state.setAndContinue(BUILDING_ANIM);
            }
        }
        return state.setAndContinue(WORKING_ANIM);
    }

    /**
     * 服务端 tick 中调用：累计放置时间，2秒后在服务端标记 buildingAnimPlayed=true。
     * 这样 NBT 会持久化该标志，重新进游戏时不会重播建造动画。
     */
    public void tickServerSide() {
        if (!buildingAnimPlayed && level != null && !level.isClientSide()) {
            placeTicks++;
            if (placeTicks >= 40) {
                buildingAnimPlayed = true;
                setChanged();
            }
        }
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.poly_mech.horizontal_steam_boiler");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }

    @Override
    protected Optional<RecipeHolder<?>> getMatchRecipe(Level world) {
        return Optional.empty();
    }

    @Override
    protected void craftItem(Level world) {
        if (!itemStackHandler.getStackInSlot(FUEL_SLOT).isEmpty()) {
            itemStackHandler.extractItem(FUEL_SLOT, 1, false);
            itemStackHandler.insertItem(OUTPUT_ASH_SLOT, new ItemStack(Items.STICK), false);
        }
    }

    @Override
    protected boolean hasCorrectRecipe(Level world) {
        ItemStack fuelStack = itemStackHandler.getStackInSlot(FUEL_SLOT);
        if (fuelStack.isEmpty()) return false;
        if (!isFuel(fuelStack)) return false;
        ItemStack ashStack = itemStackHandler.getStackInSlot(OUTPUT_ASH_SLOT);
        return ashStack.isEmpty() || ashStack.getCount() < ashStack.getMaxStackSize();
    }

    private static boolean isFuel(ItemStack stack) {
        return FUEL_ITEMS.contains(stack.getItem());
    }

    // ==================== GUI 数据访问器 ====================

    public int getProgress() { return progress; }
    public int getMaxProgress() { return maxProgress; }
    public boolean isEnable() { return enable; }

    /** 当前温度（基于进度值） */
    public int getTemperature() {
        int baseTemp = 20;
        int maxTemp = 1000;
        if (maxProgress <= 0) return baseTemp;
        return baseTemp + (int) ((float) progress / maxProgress * (maxTemp - baseTemp));
    }

    /** 当前效率（百分比） */
    public int getEfficiency() {
        if (maxProgress <= 0) return 0;
        return (int) ((float) progress / maxProgress * 100);
    }

    // ==================== NBT：放置动画状态持久化 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("buildingAnimPlayed", buildingAnimPlayed);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        buildingAnimPlayed = tag.getBoolean("buildingAnimPlayed");
    }

    private record InputHandler(ItemStackHandler parent) implements IItemHandler {
        @Override public int getSlots() { return 2; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot == 0) return parent.getStackInSlot(INPUT_LIQUID_SLOT);
            if (slot == 1) return parent.getStackInSlot(FUEL_SLOT);
            return ItemStack.EMPTY;
        }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (slot == 0) {
                return parent.insertItem(INPUT_LIQUID_SLOT, stack, simulate);
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
            if (slot == 0) return true;
            if (slot == 1) return isFuel(stack);
            return false;
        }
    }

    private record OutputHandler(ItemStackHandler parent) implements IItemHandler {
        @Override public int getSlots() { return 2; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot == 0) return parent.getStackInSlot(OUTPUT_LIQUID_SLOT);
            if (slot == 1) return parent.getStackInSlot(OUTPUT_ASH_SLOT);
            return ItemStack.EMPTY;
        }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack;
        }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0) return parent.extractItem(OUTPUT_LIQUID_SLOT, amount, simulate);
            if (slot == 1) return parent.extractItem(OUTPUT_ASH_SLOT, amount, simulate);
            return ItemStack.EMPTY;
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return false; }
    }
}
