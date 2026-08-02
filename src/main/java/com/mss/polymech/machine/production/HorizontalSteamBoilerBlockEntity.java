package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.fluid.ModFluids;
import com.mss.polymech.machine.boiler.AbstractSteamBoilerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
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
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
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

/**
 * 卧式蒸汽锅炉（大型机器，16 倍并行）。
 * <p>
 * 继承 {@link AbstractSteamBoilerBlockEntity} 的共享热力学逻辑，
 * 额外实现大锅炉特有的桶转换和灰烬输出系统。
 * </p>
 */
public class HorizontalSteamBoilerBlockEntity extends AbstractSteamBoilerBlockEntity implements GeoBlockEntity {

    // ==================== 槽位常量 ====================

    private static final int INPUT_WATER_SLOT = 0;        // 输入水桶
    private static final int OUTPUT_EMPTY_SLOT = 1;       // 输出空桶
    private static final int FUEL_SLOT = 2;               // 输入燃料
    private static final int INPUT_EMPTY_BUCKET_SLOT = 3; // 输入空桶/蒸汽桶
    private static final int OUTPUT_STEAM_SLOT = 4;       // 输出蒸汽桶
    private static final int OUTPUT_ASH_SLOT = 5;         // 输出灰烬

    /** 大锅炉并行倍率 */
    private static final int PARALLEL = 16;
    /** 每次水桶转换的水量（mB） */
    private static final int WATER_PER_CRAFT = 1000;

    // ==================== GeckoLib 动画 ====================

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

    // ==================== 构造函数 ====================

    public HorizontalSteamBoilerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HORIZONTAL_STEAM_BOILER.get(), pos, state, 100, 16000, 16000);
    }

    // ==================== 抽象方法实现 ====================

    @Override protected int getParallel() { return PARALLEL; }
    @Override protected int getFuelSlot() { return FUEL_SLOT; }
    @Override protected int getAshSlot() { return OUTPUT_ASH_SLOT; }
    @Override protected int getInvSize() { return 6; }
    @Override protected int getOutputSlotIndex() { return OUTPUT_STEAM_SLOT; }
    @Override protected int getPowerCostPerTick() { return 5; }

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

    // ==================== 槽位验证 ====================

    /**
     * GUI 槽位验证规则：
     * 槽位 0（水输入）：接受水桶
     * 槽位 1（空桶输出）：GUI 不允许放入，只能由机器产出
     * 槽位 2（燃料输入）：接受燃料物品
     * 槽位 3（桶输入）：接受空桶和蒸汽桶
     * 槽位 4（蒸汽桶输出）：GUI 不允许放入，只能由机器产出
     * 槽位 5（灰烬输出）：GUI 不允许放入，只能由机器产出
     */
    @Override
    protected boolean isItemValidForSlot(int slot, @NotNull ItemStack stack) {
        return switch (slot) {
            case INPUT_WATER_SLOT -> stack.getItem() == Items.WATER_BUCKET;
            case OUTPUT_EMPTY_SLOT, OUTPUT_STEAM_SLOT, OUTPUT_ASH_SLOT -> false;
            case FUEL_SLOT -> isFuel(stack);
            case INPUT_EMPTY_BUCKET_SLOT -> stack.getItem() == Items.BUCKET || stack.getItem() == ModFluids.STEAM_BUCKET.get();
            default -> false;
        };
    }

    // ==================== 大锅炉特有：自动桶转换 ====================

    @Override
    protected void onPreSteamTick(Level world) {
        // === 自动水桶→水转换 ===
        if (waterTank.getFluidAmount() < waterCapacity) {
            ItemStack waterStack = itemStackHandler.getStackInSlot(INPUT_WATER_SLOT);
            if (!waterStack.isEmpty() && waterStack.getItem() == Items.WATER_BUCKET) {
                int filled = waterTank.fill(new FluidStack(Fluids.WATER, WATER_PER_CRAFT),
                        IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    itemStackHandler.extractItem(INPUT_WATER_SLOT, 1, false);
                    ItemStack emptyStack = itemStackHandler.getStackInSlot(OUTPUT_EMPTY_SLOT);
                    if (emptyStack.isEmpty()) {
                        itemStackHandler.setStackInSlot(OUTPUT_EMPTY_SLOT, new ItemStack(Items.BUCKET));
                    } else if (emptyStack.getCount() < emptyStack.getMaxStackSize()) {
                        emptyStack.grow(1);
                    }
                }
            }
        }

        // === 自动桶↔蒸汽转换（空桶→蒸汽桶 / 蒸汽桶→排入储罐） ===
        ItemStack bucketStack = itemStackHandler.getStackInSlot(INPUT_EMPTY_BUCKET_SLOT);
        if (!bucketStack.isEmpty()) {
            if (bucketStack.getItem() == Items.BUCKET) {
                // 空桶→装蒸汽：消耗 1000 mB 蒸汽，产出蒸汽桶到 OUTPUT_STEAM_SLOT
                if (steamTank.getFluidAmount() >= 1000) {
                    ItemStack steamBucketOutput = itemStackHandler.getStackInSlot(OUTPUT_STEAM_SLOT);
                    boolean canOutput = steamBucketOutput.isEmpty()
                            || (steamBucketOutput.getItem() == ModFluids.STEAM_BUCKET.get() && steamBucketOutput.getCount() < steamBucketOutput.getMaxStackSize());
                    if (canOutput) {
                        steamTank.drain(1000, IFluidHandler.FluidAction.EXECUTE);
                        itemStackHandler.extractItem(INPUT_EMPTY_BUCKET_SLOT, 1, false);
                        if (steamBucketOutput.isEmpty()) {
                            itemStackHandler.setStackInSlot(OUTPUT_STEAM_SLOT, new ItemStack(ModFluids.STEAM_BUCKET.get()));
                        } else {
                            steamBucketOutput.grow(1);
                        }
                    }
                }
            } else if (bucketStack.getItem() == ModFluids.STEAM_BUCKET.get()) {
                // 蒸汽桶→排入储罐：消耗蒸汽桶，产出空桶到 OUTPUT_EMPTY_SLOT
                if (steamTank.getFluidAmount() + 1000 <= steamCapacity) {
                    ItemStack emptyBucketOutput = itemStackHandler.getStackInSlot(OUTPUT_EMPTY_SLOT);
                    boolean canOutput = emptyBucketOutput.isEmpty()
                            || (emptyBucketOutput.getItem() == Items.BUCKET && emptyBucketOutput.getCount() < emptyBucketOutput.getMaxStackSize());
                    if (canOutput) {
                        steamTank.fill(new FluidStack(ModFluids.STEAM_SOURCE.get(), 1000), IFluidHandler.FluidAction.EXECUTE);
                        itemStackHandler.extractItem(INPUT_EMPTY_BUCKET_SLOT, 1, false);
                        if (emptyBucketOutput.isEmpty()) {
                            itemStackHandler.setStackInSlot(OUTPUT_EMPTY_SLOT, new ItemStack(Items.BUCKET));
                        } else {
                            emptyBucketOutput.grow(1);
                        }
                    }
                }
            }
        }
    }

    // ==================== 配方（锅炉由燃料驱动，无需外部配方） ====================

    @Override
    protected Optional<RecipeHolder<?>> getMatchRecipe(Level world) { return Optional.empty(); }

    @Override
    protected void craftItem(Level world) {
        // 燃料消耗由 onTick 中的燃烧计时器处理
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

    // ==================== GeckoLib 动画 ====================

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0,
                this::animationController));
    }

    private PlayState animationController(final AnimationState<HorizontalSteamBoilerBlockEntity> state) {
        if (isGhostPreview) {
            return state.setAndContinue(WORKING_ANIM);
        }
        if (!buildingAnimPlayed) {
            if (state.isCurrentAnimationStage("working")) {
                buildingAnimPlayed = true;
                setChanged();
            } else {
                return state.setAndContinue(BUILDING_ANIM);
            }
        }
        return state.setAndContinue(WORKING_ANIM);
    }

    /** 服务端 tick 中调用：累计放置时间，2秒后标记 buildingAnimPlayed=true。 */
    public void tickServerSide() {
        if (!buildingAnimPlayed && level != null && !level.isClientSide()) {
            placeTicks++;
            if (placeTicks >= 40) {
                buildingAnimPlayed = true;
                setChanged();
            }
        }
    }

    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    // ==================== MenuProvider ====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.poly_mech.horizontal_steam_boiler");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInv, Player player) {
        return null;
    }

    // ==================== NBT（动画状态 + 基类数据） ====================

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

    // ==================== 内部类：输入/输出处理器 ====================

    private record InputHandler(ItemStackHandler parent) implements IItemHandler {
        @Override public int getSlots() { return 4; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot == 0) return parent.getStackInSlot(INPUT_WATER_SLOT);
            if (slot == 1) return parent.getStackInSlot(OUTPUT_EMPTY_SLOT);
            if (slot == 2) return parent.getStackInSlot(FUEL_SLOT);
            if (slot == 3) return parent.getStackInSlot(INPUT_EMPTY_BUCKET_SLOT);
            return ItemStack.EMPTY;
        }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (slot == 0) {
                if (stack.getItem() == Items.WATER_BUCKET) {
                    return parent.insertItem(INPUT_WATER_SLOT, stack, simulate);
                }
            } else if (slot == 1) {
                return stack;
            } else if (slot == 2) {
                if (isFuel(stack)) {
                    return parent.insertItem(FUEL_SLOT, stack, simulate);
                }
            } else if (slot == 3) {
                if (stack.getItem() == Items.BUCKET || stack.getItem() == ModFluids.STEAM_BUCKET.get()) {
                    return parent.insertItem(INPUT_EMPTY_BUCKET_SLOT, stack, simulate);
                }
            }
            return stack;
        }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 1) return parent.extractItem(OUTPUT_EMPTY_SLOT, amount, simulate);
            return ItemStack.EMPTY;
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if (slot == 0) return stack.getItem() == Items.WATER_BUCKET;
            if (slot == 1) return false;
            if (slot == 2) return isFuel(stack);
            if (slot == 3) return stack.getItem() == Items.BUCKET || stack.getItem() == ModFluids.STEAM_BUCKET.get();
            return false;
        }
    }

    private record OutputHandler(ItemStackHandler parent) implements IItemHandler {
        @Override public int getSlots() { return 2; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot == 0) return parent.getStackInSlot(OUTPUT_STEAM_SLOT);
            if (slot == 1) return parent.getStackInSlot(OUTPUT_ASH_SLOT);
            return ItemStack.EMPTY;
        }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack;
        }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot == 0) return parent.extractItem(OUTPUT_STEAM_SLOT, amount, simulate);
            if (slot == 1) return parent.extractItem(OUTPUT_ASH_SLOT, amount, simulate);
            return ItemStack.EMPTY;
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return false; }
    }

    // ==================== 侧面方块位置映射 ====================

    /**
     * 侧面方块位置 → 物品处理器映射。
     * <p>
     * 本地坐标（未旋转，facing=NORTH 时）：
     * <ul>
     *   <li>(1,0,0) 右侧：物品输入（燃料）</li>
     *   <li>(-1,0,0) 左侧：物品输出（灰烬）</li>
     * </ul>
     * </p>
     */
    @Override
    @Nullable
    public IItemHandler getItemHandlerFor(Vec3i relativeOffset) {
        if (relativeOffset.equals(new Vec3i(1, 0, 0))) return getInput();
        if (relativeOffset.equals(new Vec3i(-1, 0, 0))) return getOutput();
        return null;
    }

    /**
     * 侧面方块位置 → 流体处理器映射。
     * <p>
     * 本地坐标（未旋转，facing=NORTH 时）：
     * <ul>
     *   <li>(0,0,1) 前方：水输入</li>
     *   <li>(0,0,-1) 后方：蒸汽输出</li>
     * </ul>
     * </p>
     */
    @Override
    @Nullable
    public IFluidHandler getFluidHandlerFor(Vec3i relativeOffset) {
        if (relativeOffset.equals(new Vec3i(0, 0, 1))) return getWaterInputHandler();
        if (relativeOffset.equals(new Vec3i(0, 0, -1))) return getSteamOutputHandler();
        return null;
    }
}
