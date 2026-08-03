package com.mss.polymech.machine.production;

import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.common.MultiTankFluidHandler;
import com.mss.polymech.recipe.MachineRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 配方加工类大机器基类（参考 GTM 的 RecipeLogic + Trait 组合思路）。
 * <p>
 * 职责：
 * <ul>
 *   <li>按机器对应的 {@link RecipeType} 匹配 {@link MachineRecipe}，缓存上次配方</li>
 *   <li>物品槽位分为输入/输出两组，由子类声明布局</li>
 *   <li>流体储罐若干，配方匹配/消耗/产出统一走组合 handler</li>
 *   <li>NBT 持久化储罐</li>
 * </ul>
 * 动力约定：
 * <ul>
 *   <li>电力机器：配方 power_per_tick 为耗电，默认走基类储电流程</li>
 *   <li>蒸汽/燃料机器：覆盖 {@link #hasFuelPower()} 返回 true，
 *       蒸汽消耗通过配方的流体输入声明实现</li>
 * </ul>
 */
public abstract class AbstractProcessingBlockEntity extends BaseIOBlockEntity {

    protected final RecipeType<MachineRecipe> recipeType;
    @Nullable
    protected MachineRecipe lastRecipe;
    protected final FluidTank[] tanks;

    protected AbstractProcessingBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                            java.util.function.Supplier<RecipeType<MachineRecipe>> recipeType,
                                            int defaultMaxProgress) {
        super(type, pos, state, defaultMaxProgress);
        // BE 只在游戏运行时构造，此时配方类型已注册完成，可直接解析
        this.recipeType = recipeType.get();
        this.tanks = createTanks();
    }

    // ==================== 子类必须实现的布局声明 ====================

    /** 输入槽位索引 */
    public abstract int[] getInputSlots();

    /** 输出槽位索引 */
    public abstract int[] getOutputSlots();

    // ==================== 储罐布局（可覆盖） ====================

    protected int getTankCount() { return 0; }

    protected int getTankCapacity(int index) { return 8000; }

    protected FluidTank[] createTanks() {
        FluidTank[] result = new FluidTank[getTankCount()];
        for (int i = 0; i < result.length; i++) {
            result[i] = new FluidTank(getTankCapacity(i)) {
                @Override
                protected void onContentsChanged() {
                    setChanged();
                }
            };
        }
        return result;
    }

    public FluidTank[] getTanks() { return tanks; }

    @Override
    public @Nullable IFluidHandler getFluidTank(int tankIndex) {
        return tankIndex >= 0 && tankIndex < tanks.length ? tanks[tankIndex] : null;
    }

    /** 组合储罐视图（配方匹配/消耗/产出用） */
    protected @Nullable IFluidHandler getCombinedTank() {
        if (tanks.length == 0) return null;
        if (tanks.length == 1) return tanks[0];
        return new MultiTankFluidHandler(tanks);
    }

    // ==================== 配方匹配与执行 ====================

    protected MachineRecipe.MachineInput buildInput() {
        return new MachineRecipe.MachineInput(itemStackHandler, getInputSlots(), getCombinedTank());
    }

    /**
     * 查找可执行配方：优先重试上次配方，其次遍历注册表。
     * 子类可覆盖以支持合成配方（如反射炉代理原版熔炼）。
     */
    @Nullable
    protected MachineRecipe findRecipe(Level world) {
        if (world == null) return null;
        if (lastRecipe != null && lastRecipe.matches(buildInput(), world)) {
            return lastRecipe;
        }
        for (RecipeHolder<MachineRecipe> holder : world.getRecipeManager().getAllRecipesFor(recipeType)) {
            if (holder.value().matches(buildInput(), world)) {
                lastRecipe = holder.value();
                return lastRecipe;
            }
        }
        return null;
    }

    @Override
    protected Optional<RecipeHolder<?>> getMatchRecipe(Level world) {
        // 基类 tick 只经 hasCorrectRecipe/craftItem 使用配方，此处仅满足抽象约定
        MachineRecipe recipe = findRecipe(world);
        if (recipe == null) return Optional.empty();
        for (RecipeHolder<MachineRecipe> holder : world.getRecipeManager().getAllRecipesFor(recipeType)) {
            if (holder.value() == recipe) return Optional.of(holder);
        }
        return Optional.empty();
    }

    @Override
    protected boolean hasCorrectRecipe(Level world) {
        MachineRecipe recipe = findRecipe(world);
        if (recipe == null) return false;
        return recipe.canFitOutputs(itemStackHandler, getOutputSlots(), getCombinedTank());
    }

    @Override
    protected void craftItem(Level world) {
        MachineRecipe recipe = findRecipe(world);
        if (recipe == null) return;
        if (!recipe.canFitOutputs(itemStackHandler, getOutputSlots(), getCombinedTank())) return;
        recipe.consume(buildInput());
        recipe.produce(itemStackHandler, getOutputSlots(), getCombinedTank());
        setChanged();
    }

    /** 输出空间检查：按当前配方的全部输出模拟插入 */
    @Override
    protected boolean isOutputSlotAvailable() {
        if (level == null) return true;
        MachineRecipe recipe = findRecipe(level);
        if (recipe == null) return true;
        return recipe.canFitOutputs(itemStackHandler, getOutputSlots(), getCombinedTank());
    }

    @Override
    protected int getOutputSlotIndex() {
        int[] outputs = getOutputSlots();
        return outputs.length > 0 ? outputs[0] : 0;
    }

    // ==================== 动力 ====================

    /** 当前配方每 tick 耗电（无配方时返回默认值） */
    @Override
    protected int getPowerCostPerTick() {
        if (lastRecipe != null) return lastRecipe.getPowerPerTick();
        if (level != null) {
            MachineRecipe recipe = findRecipe(level);
            if (recipe != null) return recipe.getPowerPerTick();
        }
        return 0;
    }

    // ==================== 能力包装（输入只进、输出只出） ====================

    @Override
    protected IItemHandler getInput() {
        return new FilteredHandler(itemStackHandler, getInputSlots(), true, false);
    }

    @Override
    protected IItemHandler getOutput() {
        return new FilteredHandler(itemStackHandler, getOutputSlots(), false, true);
    }

    private record FilteredHandler(ItemStackHandler parent, int[] slots,
                                   boolean allowInsert, boolean allowExtract) implements IItemHandler {
        @Override public int getSlots() { return slots.length; }
        @Override public @NotNull ItemStack getStackInSlot(int slot) {
            return parent.getStackInSlot(slots[slot]);
        }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (!allowInsert || slot < 0 || slot >= slots.length) return stack;
            return parent.insertItem(slots[slot], stack, simulate);
        }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!allowExtract || slot < 0 || slot >= slots.length) return ItemStack.EMPTY;
            return parent.extractItem(slots[slot], amount, simulate);
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return allowInsert; }
    }

    // ==================== 数据持久化 ====================

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        for (int i = 0; i < tanks.length; i++) {
            tanks[i].readFromNBT(registries, tag.getCompound("tank_" + i));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        for (int i = 0; i < tanks.length; i++) {
            CompoundTag tankTag = new CompoundTag();
            tanks[i].writeToNBT(registries, tankTag);
            tag.put("tank_" + i, tankTag);
        }
    }

    // ==================== 属性委托（子类按需覆盖） ====================

    @Override
    protected ContainerData createPropertyDelegate() {
        return new ContainerData() {
            @Override public int get(int index) {
                return switch (index) {
                    case 0 -> AbstractProcessingBlockEntity.this.progress;
                    case 1 -> AbstractProcessingBlockEntity.this.maxProgress;
                    case 2 -> AbstractProcessingBlockEntity.this.enable ? 1 : 0;
                    default -> 0;
                };
            }
            @Override public void set(int index, int value) {
                switch (index) {
                    case 0 -> AbstractProcessingBlockEntity.this.progress = value;
                    case 1 -> AbstractProcessingBlockEntity.this.maxProgress = value;
                    case 2 -> AbstractProcessingBlockEntity.this.enable = value == 1;
                }
            }
            @Override public int getCount() { return 3; }
        };
    }
}
