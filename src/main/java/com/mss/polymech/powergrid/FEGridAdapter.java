package com.mss.polymech.powergrid;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.energy.IEnergyStorage;

import java.util.function.Supplier;

/**
 * FE 电网适配器：将外部 Mod 的 IEnergyStorage 设备桥接到本 Mod 电网系统。
 * <p>
 * 允许其他 Mod 的发电机/用电器通过电线接入本 Mod 电网，
 * 实现跨 Mod 能源互通。
 * </p>
 * <ul>
 *   <li><b>外部发电机</b>：每 tick 从外部 IEnergyStorage 提取 FE，作为发电量注入电网</li>
 *   <li><b>外部消费者</b>：每 tick 将电网分配的 FE 注入外部 IEnergyStorage</li>
 * </ul>
 */
public final class FEGridAdapter {

    private FEGridAdapter() {}

    /**
     * 将外部 IEnergyStorage 注册为电网发电机。
     * <p>
     * 每 tick 尝试从外部存储中抽取 FE（simulate=false），
     * 实际抽取量作为该节点的发电量。
     * </p>
     *
     * @param grid        电网实例
     * @param node        接入节点（必须是电网方块节点）
     * @param external    外部能量存储（如其他 Mod 的发电机缓冲）
     * @param maxExtract  每 tick 最大抽取量（FE/t）
     * @param voltage     输出电压（FE/t）
     */
    public static void registerExternalGenerator(WorldPowerGrid grid, GridNode node,
                                                  IEnergyStorage external, int maxExtract, int voltage) {
        Supplier<Integer> genSupplier = () -> {
            // 每 tick 从外部存储抽取 FE 注入电网
            int extracted = external.extractEnergy(maxExtract, false);
            return extracted;
        };
        grid.registerGenerator(node, genSupplier, voltage);
    }

    /**
     * 将外部 IEnergyStorage 注册为电网消费者。
     * <p>
     * 电网分配电力时，将 FE 注入外部存储（receiveEnergy）。
     * 需求始终为 maxDemand，实际注入量由外部存储接受能力决定。
     * </p>
     *
     * @param grid        电网实例
     * @param node        接入节点
     * @param external    外部能量存储（如其他 Mod 的用电器缓冲）
     * @param maxDemand   每 tick 最大需求（FE/t）
     * @param ratedVoltage 额定电压（FE/t）
     */
    public static void registerExternalConsumer(WorldPowerGrid grid, GridNode node,
                                                 IEnergyStorage external, int maxDemand, int ratedVoltage) {
        grid.registerConsumer(node, () -> maxDemand, amount -> {
            // 将电网分配的 FE 注入外部存储
            external.receiveEnergy(amount, false);
        }, ratedVoltage);
    }

    /**
     * 便捷方法：通过 ServerLevel 获取电网并注册外部发电机。
     */
    public static void registerExternalGenerator(ServerLevel level, GridNode node,
                                                  IEnergyStorage external, int maxExtract, int voltage) {
        registerExternalGenerator(WorldPowerGrid.get(level), node, external, maxExtract, voltage);
    }

    /**
     * 便捷方法：通过 ServerLevel 获取电网并注册外部消费者。
     */
    public static void registerExternalConsumer(ServerLevel level, GridNode node,
                                                 IEnergyStorage external, int maxDemand) {
        registerExternalConsumer(WorldPowerGrid.get(level), node, external, maxDemand, VoltageTier.LV.getMaxVoltage());
    }
}
