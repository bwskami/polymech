package com.mss.polymech.machine.common;

import com.mss.polymech.fluid.ModFluids;
import com.mss.polymech.machine.BaseIOBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

import java.util.Set;

/**
 * 蒸汽锅炉抽象基类，封装所有锅炉共享的热力学逻辑。
 * <p>
 * 子类通过 {@link #getParallel()} 定义并行倍率（小锅炉=1，大锅炉=16），
 * 通过 {@link #getWaterCapacity()} / {@link #getSteamCapacity()} 定义储罐容量。
 * </p>
 * <p>
 * 产汽公式（每 tick 执行）：
 * <pre>
 *   steamAmount = (currentTemperature - 273) / 100 × parallel   (mB/t)
 * </pre>
 * 温度使用开氏度(K)，范围 293K(环境) ~ 773K(满温)。
 * </p>
 */
public abstract class AbstractSteamBoilerBlockEntity extends BaseIOBlockEntity {

    // ==================== 温度常量（所有锅炉共享） ====================

    /** 最大温度（开氏度，500°C = 773K） */
    protected static final int MAX_TEMPERATURE = 773;
    /** 每 N tick 升温 1 度（工作时，低压锅炉较慢） */
    protected static final int HEAT_INTERVAL = 24;
    /** 停机后延迟多少 tick 才开始冷却（余温保持时间） */
    protected static final int COOLDOWN_DELAY = 45;
    /** 每 N tick 降温 1 度（冷却速率） */
    protected static final int COOL_DOWN_RATE = 1;
    /** 产蒸汽所需的最低温度（开氏度，100°C = 373K） */
    protected static final int MIN_STEAM_TEMP = 373;
    /** 环境温度（开氏度，20°C = 293K） */
    protected static final int AMBIENT_TEMPERATURE = 293;

    // ==================== 燃料物品集合（所有锅炉共享） ====================

    protected static final Set<net.minecraft.world.item.Item> FUEL_ITEMS = Set.of(
            Items.COAL, Items.CHARCOAL, Items.COAL_BLOCK,
            Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG, Items.ACACIA_LOG, Items.DARK_OAK_LOG,
            Items.JUNGLE_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
            Items.OAK_PLANKS, Items.BIRCH_PLANKS, Items.SPRUCE_PLANKS, Items.ACACIA_PLANKS,
            Items.DARK_OAK_PLANKS, Items.JUNGLE_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
            Items.STICK, Items.BLAZE_ROD, Items.LAVA_BUCKET
    );

    /** 燃料燃烧值映射（使用原版熔炉燃烧时间） */
    protected static final java.util.Map<net.minecraft.world.item.Item, Integer> FUEL_BURN_TIMES = java.util.Map.ofEntries(
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

    // ==================== 子类可配置的容量 ====================

    /** 水罐容量（mB），由子类通过 getWaterCapacity() 提供 */
    protected final int waterCapacity;
    /** 蒸汽罐容量（mB），由子类通过 getSteamCapacity() 提供 */
    protected final int steamCapacity;

    // ==================== 独立温度系统 ====================

    /** 当前温度（开氏度，独立字段，不依赖进度条） */
    protected int currentTemperature = AMBIENT_TEMPERATURE;
    /** 停机后距离开始冷却的剩余 tick 数（余温延迟） */
    protected int timeBeforeCoolingDown = 0;
    /** 燃料剩余燃烧 tick 数 */
    protected int fuelBurnTimeRemaining = 0;
    /** 冷却计时器（独立于 tickNum，确保停机时也能正确降温） */
    protected int coolTimer = 0;

    // ==================== 流体储罐 ====================

    /** 输入水罐 */
    protected final FluidTank waterTank;
    /** 输出蒸汽罐 */
    protected final FluidTank steamTank;

    // ==================== 构造函数 ====================

    protected AbstractSteamBoilerBlockEntity(
            net.minecraft.world.level.block.entity.BlockEntityType<?> type,
            BlockPos pos, BlockState state, int maxProgress,
            int waterCapacity, int steamCapacity) {
        super(type, pos, state, maxProgress);
        this.waterCapacity = waterCapacity;
        this.steamCapacity = steamCapacity;

        this.waterTank = new FluidTank(waterCapacity,
                stack -> stack.getFluid() == Fluids.WATER) {
            @Override
            protected void onContentsChanged() {
                setChanged();
                if (level != null && !level.isClientSide()) {
                    level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
                }
            }
        };

        this.steamTank = new FluidTank(steamCapacity,
                stack -> stack.getFluid() == ModFluids.STEAM_SOURCE.get()) {
            @Override
            protected void onContentsChanged() {
                setChanged();
                if (level != null && !level.isClientSide()) {
                    level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
                }
            }
        };
    }

    // ==================== 抽象方法（子类必须实现） ====================

    /** 并行倍率：小锅炉=1，大锅炉=16 */
    protected abstract int getParallel();

    // ==================== 核心 tick 逻辑 ====================

    /**
     * 锅炉核心 tick 逻辑（每 tick 调用）：
     * 1. 燃料燃烧计时
     * 2. 温度管理（升温/冷却）
     * 3. 持续产蒸汽（温度>=373K时每tick产汽）
     * <p>
     * 子类可覆盖 {@link #onPreSteamTick(Level)} 在产汽前插入自定义逻辑（如自动水桶转换）。
     */
    @Override
    protected void onTick(Level world) {
        // 子类前置逻辑（如自动水桶→水转换）
        onPreSteamTick(world);

        // === 1. 燃料燃烧计时器 ===
        if (enable) {
            if (fuelBurnTimeRemaining > 0) {
                fuelBurnTimeRemaining--;
            } else {
                consumeNextFuel();
            }
        }

        boolean isBurning = fuelBurnTimeRemaining > 0;

        // === 2. 温度管理 ===
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

        // === 3. 持续产蒸汽（373K起产，每tick执行） ===
        // 公式：steamAmount = (temp - 273) / 100 × parallel
        if (currentTemperature >= MIN_STEAM_TEMP) {
            int steamAmount = (currentTemperature - 273) / 100 * getParallel();
            if (steamAmount > 0 && steamTank.getFluidAmount() + steamAmount <= steamCapacity) {
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
     * 产汽前的钩子方法，子类可覆盖以实现自动水桶转换等逻辑。
     * 在燃料计时和温度管理之前调用。
     */
    protected void onPreSteamTick(Level world) {}

    // ==================== 燃料系统 ====================

    /**
     * 消耗下一块燃料，重置燃烧计时器。
     * 使用原版熔炉燃烧值，如煤炭 = 1600 tick（80秒）。
     */
    protected void consumeNextFuel() {
        int fuelSlot = getFuelSlot();
        ItemStack fuelStack = itemStackHandler.getStackInSlot(fuelSlot);
        if (fuelStack.isEmpty()) return;

        int burnTime = getFuelBurnTime(fuelStack);
        if (burnTime <= 0) return;

        itemStackHandler.extractItem(fuelSlot, 1, false);
        fuelBurnTimeRemaining = burnTime;

        // 产出灰烬（如果有灰烬槽）
        int ashSlot = getAshSlot();
        if (ashSlot >= 0) {
            ItemStack ashStack = itemStackHandler.getStackInSlot(ashSlot);
            if (ashStack.isEmpty()) {
                itemStackHandler.setStackInSlot(ashSlot, new ItemStack(Items.STICK));
            } else if (ashStack.getCount() < ashStack.getMaxStackSize()) {
                ashStack.grow(1);
            }
        }
    }

    /** 获取燃料的燃烧 tick 数（使用原版熔炉值） */
    protected static int getFuelBurnTime(ItemStack stack) {
        return FUEL_BURN_TIMES.getOrDefault(stack.getItem(), 0);
    }

    /** 判断物品是否为燃料 */
    protected static boolean isFuel(ItemStack stack) {
        return FUEL_ITEMS.contains(stack.getItem());
    }

    /** 燃料槽位索引 */
    protected abstract int getFuelSlot();

    /** 灰烬槽位索引，-1 表示无灰烬槽 */
    protected int getAshSlot() { return -1; }

    // ==================== 动力判定 ====================

    /**
     * 锅炉以燃料为动力源，不依赖外部电力。
     * 只要正在燃烧或燃料槽有燃料就视为有动力。
     */
    @Override
    protected boolean hasFuelPower() {
        return fuelBurnTimeRemaining > 0 || !itemStackHandler.getStackInSlot(getFuelSlot()).isEmpty();
    }

    // ==================== GUI 数据访问器 ====================

    /** 机器是否启用（开关机状态） */
    public boolean isEnable() { return enable; }

    /** 物品栏总槽位数（用于 GUI 判断布局） */
    public int getInventorySize() { return getInvSize(); }

    /** 当前水量（mB，用于 GUI tooltip） */
    public int getWaterAmount() { return waterTank.getFluidAmount(); }

    /** 当前蒸汽量（mB，用于 GUI tooltip） */
    public int getSteamAmount() { return steamTank.getFluidAmount(); }

    /** 水罐容量（mB，用于 GUI tooltip） */
    public int getWaterCapacity() { return waterCapacity; }

    /** 蒸汽罐容量（mB，用于 GUI tooltip） */
    public int getSteamCapacity() { return steamCapacity; }

    /** 当前产汽速率（mB/t） */
    public int getTotalSteamOutput() {
        if (currentTemperature < MIN_STEAM_TEMP) return 0;
        return (currentTemperature - 273) / 100 * getParallel();
    }

    /** 当前温度（开氏度K，用于 GUI tooltip） */
    public int getTemperature() { return currentTemperature; }

    /** 温度百分比（0~100，基于环境温度到最大温度范围） */
    public int getTemperaturePercent() {
        return (int) ((float) (currentTemperature - AMBIENT_TEMPERATURE) / (MAX_TEMPERATURE - AMBIENT_TEMPERATURE) * 100);
    }

    /** 水位（0~100） */
    public int getWaterLevel() {
        if (waterCapacity <= 0) return 0;
        return (int) ((float) waterTank.getFluidAmount() / waterCapacity * 100);
    }

    /** 蒸汽量（0~100） */
    public int getSteamLevel() {
        if (steamCapacity <= 0) return 0;
        return (int) ((float) steamTank.getFluidAmount() / steamCapacity * 100);
    }

    // ==================== 流体处理器 ====================

    /** 获取输入水罐处理器 */
    public IFluidHandler getWaterInputHandler() { return waterTank; }

    /** 获取输出蒸汽罐处理器 */
    public IFluidHandler getSteamOutputHandler() { return steamTank; }

    /** 获取蒸汽罐中的流体堆 */
    public FluidStack getSteamFluidStack() { return steamTank.getFluid(); }

    // ==================== NBT 持久化 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
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
}
