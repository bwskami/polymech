package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.powergrid.VoltageTier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 创造模式蓄电池方块实体。
 * <p>
 * 固定输出 100,000 FE/t，储能无限，豁免过压熔断。
 * 始终处于放电模式，无需充电。
 * </p>
 */
public class CreativeBatteryBlockEntity extends BatteryBlockEntity {

    public CreativeBatteryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CREATIVE_BATTERY.get(), pos, state);
        // 创造电池参数覆盖
        this.maxEnergy = Integer.MAX_VALUE;
        this.energyStored = Integer.MAX_VALUE;
        this.maxDischargeRate = 100_000;
        this.maxChargeRate = 0;
        this.ratedVoltage = VoltageTier.UHV.getMaxVoltage();
        this.overVoltageImmune = true;
        this.enabled = true;   // 始终启用
    }

    @Override
    protected boolean forceGridOutput() {
        return true; // 创造电池始终放电，不受面配置限制
    }

    @Override
    public void toggleEnabled() {
        // 创造电池不可关闭
    }

    @Override
    protected int getDischargeOutput() {
        return maxDischargeRate; // 无限能源，始终输出最大值
    }

    @Override
    public void tick(net.minecraft.world.level.Level world) {
        // 创造电池无需消耗内部储能
        if (world.isClientSide()) return;
        if (needsInit && world instanceof net.minecraft.server.level.ServerLevel serverWorld) {
            needsInit = false;
            registerPowerMemberships(serverWorld);
        }
        // 面配置驱动的直接能量传输
        transferEnergyDirect(world);
        // UI 显示电网真实传输量（无限能源，不扣储能）
        lastOutputRate = lastGridOutput;
        setChanged();
        if (world.getGameTime() % 20 == 0) {
            world.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.poly_mech.creative_battery");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        // 创造电池不需要额外持久化（参数固定）
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 强制覆盖为创造参数
        this.energyStored = Integer.MAX_VALUE;
        this.maxDischargeRate = 100_000;
        this.ratedVoltage = VoltageTier.UHV.getMaxVoltage();
        this.overVoltageImmune = true;
        this.enabled = true;
    }
}
