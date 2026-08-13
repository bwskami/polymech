package com.mss.polymech.powergrid;

import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * NeoForge IEnergyStorage 适配器。
 * <p>
 * 将机器的内部能量缓冲（internalEnergy）包装为标准 FE 能力接口，
 * 允许外部 Mod 通过 NeoForge 能力系统与本机交互。
 * </p>
 */
public class MachineEnergyStorage implements IEnergyStorage {

    private final IntSupplier energyGetter;
    private final IntUnaryOperator energySetter;
    private final int capacity;
    private final int maxReceive;
    private final int maxExtract;

    /**
     * @param energyGetter  获取当前储能
     * @param energySetter  设置储能，返回实际设置值
     * @param capacity      最大容量
     * @param maxReceive    每 tick 最大接收量
     * @param maxExtract    每 tick 最大抽取量
     */
    public MachineEnergyStorage(IntSupplier energyGetter, IntUnaryOperator energySetter,
                                int capacity, int maxReceive, int maxExtract) {
        this.energyGetter = energyGetter;
        this.energySetter = energySetter;
        this.capacity = capacity;
        this.maxReceive = maxReceive;
        this.maxExtract = maxExtract;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (this.maxReceive <= 0) return 0;
        int accepted = Math.min(Math.min(maxReceive, this.maxReceive), capacity - energyGetter.getAsInt());
        if (accepted <= 0) return 0;
        if (!simulate) {
            energySetter.applyAsInt(energyGetter.getAsInt() + accepted);
        }
        return accepted;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (this.maxExtract <= 0) return 0;
        int extracted = Math.min(Math.min(maxExtract, this.maxExtract), energyGetter.getAsInt());
        if (extracted <= 0) return 0;
        if (!simulate) {
            energySetter.applyAsInt(energyGetter.getAsInt() - extracted);
        }
        return extracted;
    }

    @Override
    public int getEnergyStored() {
        return energyGetter.getAsInt();
    }

    @Override
    public int getMaxEnergyStored() {
        return capacity;
    }

    @Override
    public boolean canExtract() {
        return maxExtract > 0;
    }

    @Override
    public boolean canReceive() {
        return maxReceive > 0;
    }
}
