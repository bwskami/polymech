package com.mss.polymech.machine.production;

import com.mss.polymech.powergrid.VoltageTier;
import com.mss.polymech.powergrid.WorldPowerGrid;
import com.mss.polymech.recipe.MachineRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 涡轮发电机基类（蒸汽/燃气）。
 * <p>
 * 参考 GTM 的 GENERATOR 配方类型：配方声明流体燃料输入、
 * {@code power_per_tick} 为每 tick 发电量、{@code duration} 为一个燃料周期的长度。
 * 机器按周期消耗燃料并向电网输出电力。
 * </p>
 */
public abstract class AbstractTurbineGeneratorBlockEntity extends AbstractProcessingBlockEntity {

    /** 当前燃料周期剩余 tick */
    private int cycleTicksRemaining = 0;
    /** 当前周期每 tick 发电量 */
    private int currentOutput = 0;

    protected AbstractTurbineGeneratorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                                  java.util.function.Supplier<net.minecraft.world.item.crafting.RecipeType<MachineRecipe>> recipeType,
                                                  int defaultMaxProgress) {
        super(type, pos, state, recipeType, defaultMaxProgress);
    }

    @Override
    protected boolean usesRecipeCrafting() {
        return false;
    }

    // ==================== 电网：注册为发电机 ====================

    /** 发电机默认输出电压等级（子类可覆盖） */
    protected int getGeneratorVoltage() {
        return VoltageTier.LV.getMaxVoltage();
    }

    @Override
    protected void registerPowerMemberships(ServerLevel world) {
        powerNode = getGridNode();
        if (powerNode != null) {
            WorldPowerGrid.get(world).registerGenerator(powerNode, this::getCurrentGeneration, getGeneratorVoltage());
        }
    }

    @Override
    protected void unregisterPowerMemberships(ServerLevel world) {
        if (powerNode != null) {
            WorldPowerGrid.get(world).unregisterGenerator(powerNode);
            powerNode = null;
        }
    }

    /** 电网每 tick 读取的发电量 */
    public int getCurrentGeneration() {
        return enable && cycleTicksRemaining > 0 ? currentOutput : 0;
    }

    // ==================== 发电循环 ====================

    @Override
    protected void onTick(Level world) {
        if (!enable) {
            cycleTicksRemaining = 0;
            currentOutput = 0;
            if (isWorking) {
                isWorking = false;
                world.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
            return;
        }
        if (cycleTicksRemaining > 0) {
            cycleTicksRemaining--;
            if (cycleTicksRemaining == 0) currentOutput = 0;
            return;
        }
        // 尝试启动新的燃料周期
        MachineRecipe recipe = findRecipe(world);
        if (recipe == null || !recipe.isGenerator()
                || !recipe.canFitOutputs(itemStackHandler, getOutputSlots(), getCombinedTank())) {
            if (isWorking) {
                isWorking = false;
                world.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
            currentOutput = 0;
            return;
        }
        recipe.consume(buildInput());
        recipe.produce(itemStackHandler, getOutputSlots(), getCombinedTank());
        currentOutput = recipe.getPowerPerTick();
        cycleTicksRemaining = recipe.getDuration();
        if (!isWorking) {
            isWorking = true;
            world.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
        setChanged();
    }

    // ==================== 数据持久化 ====================

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        cycleTicksRemaining = tag.getInt("cycleTicks");
        currentOutput = tag.getInt("currentOutput");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("cycleTicks", cycleTicksRemaining);
        tag.putInt("currentOutput", currentOutput);
    }

    public int getCycleTicksRemaining() { return cycleTicksRemaining; }
}
