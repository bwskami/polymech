package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.fluid.ModFluids;
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
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
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

    private static final int INPUT_WATER_SLOT = 0;   // 输入水桶（兼容旧版桶输入）
    private static final int OUTPUT_EMPTY_SLOT = 1;  // 输出空桶
    private static final int FUEL_SLOT = 2;          // 输入燃料
    private static final int INPUT_EMPTY_BUCKET_SLOT = 3; // 输入空桶/蒸汽桶（用于装蒸汽或排入蒸汽）
    private static final int OUTPUT_STEAM_SLOT = 4;  // 输出蒸汽桶
    private static final int OUTPUT_ASH_SLOT = 5;    // 输出灰烬
    private static final int POWER_PER_TICK = 5;

    /** 水容量（mB） */
    public static final int WATER_CAPACITY = 16000;
    /** 蒸汽容量（mB） */
    public static final int STEAM_CAPACITY = 16000;
    /** 每次水桶转换的水量（mB） */
    private static final int WATER_PER_CRAFT = 1000;

    /** 最大温度（开氏度，500°C = 773K） */
    private static final int MAX_TEMPERATURE = 773;
    /** 每 N tick 升温 1 度（工作时，低压锅炉较慢） */
    private static final int HEAT_INTERVAL = 24;
    /** 停机后延迟多少 tick 才开始冷却（余温保持时间） */
    private static final int COOLDOWN_DELAY = 45;
    /** 每 N tick 降温 1 度（冷却速率） */
    private static final int COOL_DOWN_RATE = 1;
    /** 产蒸汽所需的最低温度（开氏度，100°C = 373K） */
    private static final int MIN_STEAM_TEMP = 373;
    /** 环境温度（开氏度，20°C = 293K） */
    private static final int AMBIENT_TEMPERATURE = 293;

    // ==================== 独立温度系统（参考 GTM） ====================
    /** 当前温度（开氏度，独立字段，不依赖进度条） */
    private int currentTemperature = 293;
    /** 停机后距离开始冷却的剩余 tick 数（余温延迟） */
    private int timeBeforeCoolingDown = 0;
    /** 燃料剩余燃烧 tick 数 */
    private int fuelBurnTimeRemaining = 0;
    /** 冷却计时器（独立于 tickNum，确保停机时也能正确降温） */
    private int coolTimer = 0;

    /** 燃料燃烧值映射（使用原版熔炉燃烧时间） */
    private static final java.util.Map<net.minecraft.world.item.Item, Integer> FUEL_BURN_TIMES = java.util.Map.ofEntries(
            java.util.Map.entry(Items.COAL, 1600),
            java.util.Map.entry(Items.CHARCOAL, 1600),
            java.util.Map.entry(Items.COAL_BLOCK, 16000),
            java.util.Map.entry(Items.OAK_LOG, 300),
            java.util.Map.entry(Items.BIRCH_LOG, 300),
            java.util.Map.entry(Items.SPRUCE_LOG, 300),
            java.util.Map.entry(Items.ACACIA_LOG, 300),
            java.util.Map.entry(Items.DARK_OAK_LOG, 300),
            java.util.Map.entry(Items.JUNGLE_LOG, 300),
            java.util.Map.entry(Items.MANGROVE_LOG, 300),
            java.util.Map.entry(Items.CHERRY_LOG, 300),
            java.util.Map.entry(Items.OAK_PLANKS, 300),
            java.util.Map.entry(Items.BIRCH_PLANKS, 300),
            java.util.Map.entry(Items.SPRUCE_PLANKS, 300),
            java.util.Map.entry(Items.ACACIA_PLANKS, 300),
            java.util.Map.entry(Items.DARK_OAK_PLANKS, 300),
            java.util.Map.entry(Items.JUNGLE_PLANKS, 300),
            java.util.Map.entry(Items.MANGROVE_PLANKS, 300),
            java.util.Map.entry(Items.CHERRY_PLANKS, 300),
            java.util.Map.entry(Items.STICK, 100),
            java.util.Map.entry(Items.BLAZE_ROD, 2400),
            java.util.Map.entry(Items.LAVA_BUCKET, 20000)
    );

    /** 输入水罐 */
    private final FluidTank waterTank = new FluidTank(WATER_CAPACITY,
            stack -> stack.getFluid() == Fluids.WATER) {
        @Override
        protected void onContentsChanged() {
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    };

    /** 输出蒸汽罐 */
    private final FluidTank steamTank = new FluidTank(STEAM_CAPACITY,
            stack -> stack.getFluid() == ModFluids.STEAM_SOURCE.get()) {
        @Override
        protected void onContentsChanged() {
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    };

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

    @Override protected int getInvSize() { return 6; }
    @Override protected int getOutputSlotIndex() { return OUTPUT_STEAM_SLOT; }

    /**
     * GUI 槽位验证规则：
     * 槽位 0（水输入）：接受水桶
     * 槽位 1（空桶输出）：GUI 不允许放入，只能由机器产出
     * 槽位 2（燃料输入）：接受燃料物品
     * 槽位 3（桶输入）：接受空桶和蒸汽桶，空桶装蒸汽、蒸汽桶排入储罐
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

    /**
     * 蒸汽锅炉以燃料为动力源，不依赖外部电力。
     * 只要正在燃烧或燃料槽有燃料就视为有动力。
     */
    @Override
    protected boolean hasFuelPower() {
        return fuelBurnTimeRemaining > 0 || !itemStackHandler.getStackInSlot(FUEL_SLOT).isEmpty();
    }

    /**
     * 锅炉核心 tick 逻辑（参考 GTM SteamBoilerMachine）：
     * 1. 自动水桶→水转换
     * 2. 燃料燃烧计时器（使用原版熔炉燃烧值）
     * 3. 独立温度系统：缓慢升温、余温缓慢冷却
     * 4. 持续产蒸汽：温度>=100时每 STEAM_INTERVAL tick 产一次
     */
    @Override
    protected void onTick(Level world) {
        // === 1. 自动水桶→水转换 ===
        if (waterTank.getFluidAmount() < WATER_CAPACITY) {
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

        // === 1b. 自动桶↔蒸汽转换（空桶→蒸汽桶 / 蒸汽桶→排入储罐） ===
        {
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
                    if (steamTank.getFluidAmount() + 1000 <= STEAM_CAPACITY) {
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

        // === 2. 燃料燃烧计时器 ===
        if (enable) {
            if (fuelBurnTimeRemaining > 0) {
                fuelBurnTimeRemaining--;
            } else {
                // 当前燃料烧完，尝试消耗下一块燃料
                consumeNextFuel();
            }
        }

        boolean isBurning = fuelBurnTimeRemaining > 0;

        // === 3. 温度管理 ===
        if (enable && isBurning) {
            // 燃烧中：缓慢升温（每 HEAT_INTERVAL tick 升 1 度）
            if (tickNum % HEAT_INTERVAL == 0 && currentTemperature < MAX_TEMPERATURE) {
                currentTemperature++;
            }
            // 持续重置冷却计时（保持余温）
            timeBeforeCoolingDown = COOLDOWN_DELAY;
            coolTimer = 0;
        } else {
            // 停机/无燃料：先等余温延迟，然后用独立计时器缓慢降温
            if (timeBeforeCoolingDown > 0) {
                timeBeforeCoolingDown--;
            } else {
                coolTimer++;
                if (coolTimer >= COOL_DOWN_RATE) {
                    coolTimer = 0;
                    if (currentTemperature > AMBIENT_TEMPERATURE) {
                        currentTemperature--;
                    }
                }
            }
        }

        // === 4. 持续产蒸汽（373K起产，(温度K-273)/10 mB/t，每tick执行） ===
        if (currentTemperature >= MIN_STEAM_TEMP) {
            int steamAmount = (currentTemperature - 273) / 10;
            if (steamAmount > 0 && steamTank.getFluidAmount() + steamAmount <= STEAM_CAPACITY) {
                FluidStack waterInTank = waterTank.getFluid();
                if (!waterInTank.isEmpty() && waterInTank.getAmount() >= 1) {
                    waterTank.drain(1, IFluidHandler.FluidAction.EXECUTE);
                    steamTank.fill(
                            new FluidStack(ModFluids.STEAM_SOURCE.get(), steamAmount),
                            IFluidHandler.FluidAction.EXECUTE);
                }
            }
        }

        setChanged();
    }

    /**
     * 消耗下一块燃料，重置燃烧计时器。
     * 使用原版熔炉燃烧值，如煤炭 = 1600 tick（80秒）。
     */
    private void consumeNextFuel() {
        ItemStack fuelStack = itemStackHandler.getStackInSlot(FUEL_SLOT);
        if (fuelStack.isEmpty()) return;

        int burnTime = getFuelBurnTime(fuelStack);
        if (burnTime <= 0) return;

        itemStackHandler.extractItem(FUEL_SLOT, 1, false);
        fuelBurnTimeRemaining = burnTime;

        // 产出灰烬
        ItemStack ashStack = itemStackHandler.getStackInSlot(OUTPUT_ASH_SLOT);
        if (ashStack.isEmpty()) {
            itemStackHandler.setStackInSlot(OUTPUT_ASH_SLOT, new ItemStack(Items.STICK));
        } else if (ashStack.getCount() < ashStack.getMaxStackSize()) {
            ashStack.grow(1);
        }
    }

    /** 获取燃料的燃烧 tick 数（使用原版熔炉值） */
    private static int getFuelBurnTime(ItemStack stack) {
        return FUEL_BURN_TIMES.getOrDefault(stack.getItem(), 0);
    }

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
        // 燃料消耗由 onTick 中的燃烧计时器处理，此处无需操作
    }

    @Override
    protected boolean hasCorrectRecipe(Level world) {
        // 燃料槽有燃料（燃烧计时器会自行处理消耗）
        ItemStack fuelStack = itemStackHandler.getStackInSlot(FUEL_SLOT);
        if (fuelStack.isEmpty() || !isFuel(fuelStack)) return false;

        // 需要有一些水
        boolean hasWater = waterTank.getFluidAmount() >= 1;
        if (!hasWater) {
            ItemStack waterStack = itemStackHandler.getStackInSlot(INPUT_WATER_SLOT);
            if (waterStack.isEmpty() || waterStack.getItem() != Items.WATER_BUCKET) {
                return false;
            }
        }

        // 蒸汽罐有空间
        return steamTank.getFluidAmount() < STEAM_CAPACITY;
    }

    private static boolean isFuel(ItemStack stack) {
        return FUEL_ITEMS.contains(stack.getItem());
    }

    // ==================== GUI 数据访问器 ====================

    public int getProgress() { return progress; }
    public int getMaxProgress() { return maxProgress; }
    public boolean isEnable() { return enable; }

    /** 当前水量（mB，用于 GUI tooltip） */
    public int getWaterAmount() { return waterTank.getFluidAmount(); }

    /** 当前蒸汽量（mB，用于 GUI tooltip） */
    public int getSteamAmount() { return steamTank.getFluidAmount(); }

    /** 当前产汽速率（mB/t，(温度K-273)/10） */
    public int getTotalSteamOutput() {
        if (currentTemperature < MIN_STEAM_TEMP) return 0;
        return (currentTemperature - 273) / 10;
    }

    /** 当前温度（开氏度K，用于 GUI tooltip） */
    public int getTemperature() {
        return currentTemperature;
    }

    /** 温度百分比（0~100，用于 GUI 进度条，基于环境温度到最大温度范围） */
    public int getTemperaturePercent() {
        return (int) ((float) (currentTemperature - AMBIENT_TEMPERATURE) / (MAX_TEMPERATURE - AMBIENT_TEMPERATURE) * 100);
    }

    /** 当前效率（基于温度） */
    public int getEfficiency() {
        return (int) ((float) currentTemperature / MAX_TEMPERATURE * 100);
    }

    /** 水位（0~100，基于流体罐） */
    public int getWaterLevel() {
        if (WATER_CAPACITY <= 0) return 0;
        return (int) ((float) waterTank.getFluidAmount() / WATER_CAPACITY * 100);
    }

    /** 蒸汽量（0~100，基于蒸汽罐） */
    public int getSteamLevel() {
        if (STEAM_CAPACITY <= 0) return 0;
        return (int) ((float) steamTank.getFluidAmount() / STEAM_CAPACITY * 100);
    }

    // ==================== 流体处理器 ====================

    /** 获取输入水罐处理器 */
    public IFluidHandler getWaterInputHandler() {
        return waterTank;
    }

    /** 获取输出蒸汽罐处理器 */
    public IFluidHandler getSteamOutputHandler() {
        return steamTank;
    }

    /** 获取蒸汽罐中的流体堆 */
    public FluidStack getSteamFluidStack() {
        return steamTank.getFluid();
    }

    // ==================== NBT：放置动画状态持久化 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putBoolean("buildingAnimPlayed", buildingAnimPlayed);
        tag.put("waterTank", waterTank.writeToNBT(registries, new CompoundTag()));
        tag.put("steamTank", steamTank.writeToNBT(registries, new CompoundTag()));
        tag.putInt("currentTemperature", currentTemperature);
        tag.putInt("timeBeforeCoolingDown", timeBeforeCoolingDown);
        tag.putInt("fuelBurnTimeRemaining", fuelBurnTimeRemaining);
        tag.putInt("coolTimer", coolTimer);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        buildingAnimPlayed = tag.getBoolean("buildingAnimPlayed");
        if (tag.contains("waterTank")) {
            waterTank.readFromNBT(registries, tag.getCompound("waterTank"));
        }
        if (tag.contains("steamTank")) {
            steamTank.readFromNBT(registries, tag.getCompound("steamTank"));
        }
        currentTemperature = tag.getInt("currentTemperature");
        if (currentTemperature < AMBIENT_TEMPERATURE) currentTemperature = AMBIENT_TEMPERATURE;
        timeBeforeCoolingDown = tag.getInt("timeBeforeCoolingDown");
        fuelBurnTimeRemaining = tag.getInt("fuelBurnTimeRemaining");
        coolTimer = tag.getInt("coolTimer");
    }

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
                return stack; // 输出槽不接受输入
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
}
