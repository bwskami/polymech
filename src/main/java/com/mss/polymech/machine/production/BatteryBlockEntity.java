package com.mss.polymech.machine.production;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.SideConfig;
import com.mss.polymech.powergrid.GridNode;
import com.mss.polymech.powergrid.GridNodeBlock;
import com.mss.polymech.powergrid.MachineEnergyStorage;
import com.mss.polymech.powergrid.VoltageTier;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

/**
 * 普通蓄电池方块实体。
 * <p>
 * 储能设备：充电模式时作为消费者从电网吸收电力存入内部缓冲，
 * 放电模式时作为发电机向电网输出电力。
 * 通过电线接入电网，支持 GT 风格电压等级。
 * </p>
 */
public class BatteryBlockEntity extends BlockEntity implements MenuProvider {

    // ==================== 储能参数 ====================

    /** 最大容量（FE） */
    protected int maxEnergy = 1_000_000;
    /** 当前储能（FE） */
    protected int energyStored = 0;
    /** 最大充电速率（FE/t） */
    protected int maxChargeRate = 1024;
    /** 最大放电速率（FE/t） */
    protected int maxDischargeRate = 1024;
    /** 额定电压（FE/t） */
    protected int ratedVoltage = VoltageTier.LV.getMaxVoltage();
    /** 是否豁免过压熔断（创造电池覆盖为 true） */
    protected boolean overVoltageImmune = false;

    // ==================== 状态 ====================

    /** 是否启用（开机才参与电网调度） */
    protected boolean enabled = true;
    /** 首次加载标记 */
    protected boolean needsInit = true;
    /** 电网节点缓存 */
    @Nullable
    protected GridNode powerNode;
    /** 最近一次实际充入速率（FE/t，供 UI 显示） */
    protected int lastInputRate = 0;
    /** 最近一次实际放出速率（FE/t，供 UI 显示） */
    protected int lastOutputRate = 0;
    /** 电网反馈的本tick实际输出（FE/t，由电网分配回调写入，含线损承担；孤立时清零） */
    protected int lastGridOutput = 0;

    // ==================== 面配置（Mekanism 风格） ====================

    /** 面 IO 配置，支持能源/物品/流体三种能力类型 */
    protected final SideConfig sideConfig = new SideConfig();

    public BatteryBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.BATTERY.get(), pos, state);
    }

    protected BatteryBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.sideConfig.setChangeListener(() -> {
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
                if (!level.isClientSide()) {
                    // 充放电以配置面为准：面配置变更时重新注册电网身份
                    refreshPowerMemberships();
                }
            }
        });
    }

    // ==================== 电网交互 ====================

    @Nullable
    protected GridNode getGridNode() {
        BlockState state = getBlockState();
        if (state.getBlock() instanceof GridNodeBlock gridBlock) {
            return gridBlock.getNodePositions(state).keySet().stream()
                    .min(Integer::compareTo)
                    .map(id -> new GridNode(id, getBlockPos()))
                    .orElse(null);
        }
        return null;
    }

    protected void registerPowerMemberships(ServerLevel world) {
        powerNode = getGridNode();
        if (powerNode == null) {
            Polymech.LOGGER.info("[Battery] {} 节点为空，跳过注册", worldPosition);
            return;
        }

        // 充放电以配置面为准：有 IN 面则作为消费者（充电），有 OUT 面则作为发电机（放电）
        WorldPowerGrid grid = WorldPowerGrid.get(world);
        boolean hasIn = hasEnergyIn();
        boolean hasOut = forceGridOutput() || hasEnergyOut();
        Polymech.LOGGER.info("[Battery] {} 注册: node={} hasIn={} hasOut={} stored={}/{}",
                worldPosition, powerNode, hasIn, hasOut, energyStored, maxEnergy);
        if (hasIn) {
            // 需求 = min(maxChargeRate, 剩余容量)
            grid.registerConsumer(powerNode, this::getChargeDemand, this::receiveCharge, ratedVoltage);
        }
        if (hasOut) {
            // 输出 = min(energyStored, maxDischargeRate)；实际输出由电网按消费量反馈（onGridOutput），只扣真实传输的电量
            // 创造电池（overVoltageImmune）启用自适应输出：电压/功率自动匹配直接相连线缆的承受能力
            grid.registerGenerator(powerNode, this::getDischargeOutput, this::onGridOutput, () -> ratedVoltage, overVoltageImmune);
        }
    }

    /** 是否有配置为 IN 的能源面 */
    protected boolean hasEnergyIn() {
        for (Direction dir : Direction.values()) {
            if (sideConfig.getEnergyConfig(dir) == SideConfig.SideIO.IN) return true;
        }
        return false;
    }

    /** 是否有配置为 OUT 的能源面 */
    protected boolean hasEnergyOut() {
        for (Direction dir : Direction.values()) {
            if (sideConfig.getEnergyConfig(dir) == SideConfig.SideIO.OUT) return true;
        }
        return false;
    }

    /** 是否无视面配置强制向电网输出（创造电池始终放电） */
    protected boolean forceGridOutput() {
        return false;
    }

    /** 按当前面配置重新注册电网身份（面配置变更时调用） */
    protected void refreshPowerMemberships() {
        if (powerNode == null || !(level instanceof ServerLevel serverWorld)) {
            Polymech.LOGGER.info("[Battery] {} 面变更但跳过刷新: powerNode={} level={}",
                    worldPosition, powerNode, level == null ? "null" : level.isClientSide() ? "client" : "server");
            return;
        }
        Polymech.LOGGER.info("[Battery] {} 面配置变更，重新注册电网身份", worldPosition);
        unregisterPowerMemberships(serverWorld);
        registerPowerMemberships(serverWorld);
    }

    protected void unregisterPowerMemberships(ServerLevel world) {
        if (powerNode != null) {
            WorldPowerGrid grid = WorldPowerGrid.get(world);
            grid.unregisterConsumer(powerNode);
            grid.unregisterGenerator(powerNode);
            powerNode = null;
        }
    }

    /** 充电需求：启用、配置了 IN 面且未满时请求充电 */
    protected int getChargeDemand() {
        if (!enabled || !hasEnergyIn()) return 0;
        if (energyStored >= maxEnergy) return 0;
        return Math.min(maxChargeRate, maxEnergy - energyStored);
    }

    /** 放电输出：启用、配置了 OUT 面且有电时输出 */
    protected int getDischargeOutput() {
        if (!enabled || !hasEnergyOut()) return 0;
        return Math.min(energyStored, maxDischargeRate);
    }

    /** 接收电网充电 */
    public void receiveCharge(int amount) {
        if (amount <= 0) return;
        energyStored = Math.min(maxEnergy, energyStored + amount);
        lastInputRate = amount;
        lastOutputRate = 0;
    }

    /** 电网反馈的实际输出（含线损承担），BE 按此量扣储能保证能量守恒 */
    protected void onGridOutput(int amount) {
        lastGridOutput = amount;
    }

    // ==================== 面配置驱动的直接能量传输 ====================

    /**
     * 获取尊重面配置的 IEnergyStorage 包装。
     * 外部方块通过 NeoForge 能力系统与此蓄电池交互时使用。
     * - 从 IN 面可接收（receiveEnergy）
     * - 从 OUT 面可抽取（extractEnergy）
     * - side=null 时：若有任何 OUT 面则可抽取，有任何 IN 面则可接收
     */
    public IEnergyStorage getEnergyStorage(@Nullable Direction side) {
        return new MachineEnergyStorage(
                () -> energyStored,
                newVal -> { energyStored = Math.max(0, Math.min(newVal, maxEnergy)); return energyStored; },
                maxEnergy,
                (side == null || sideConfig.canInputEnergy(side)) ? maxChargeRate : 0,
                (side == null || sideConfig.canOutputEnergy(side)) ? maxDischargeRate : 0
        );
    }

    /**
     * 每 tick 面配置驱动的直接能量推送/拉取。
     * OUTPUT 面：主动向相邻方块的 IEnergyStorage 推送能量。
     * INPUT 面：主动从相邻方块的 IEnergyStorage 拉取能量。
     */
    protected void transferEnergyDirect(Level world) {
        if (world.isClientSide()) return;

        for (Direction dir : Direction.values()) {
            SideConfig.SideIO io = sideConfig.getEnergyConfig(dir);
            if (io == SideConfig.SideIO.NONE) continue;

            BlockPos neighborPos = worldPosition.relative(dir);
            // 从对面获取相邻方块的 IEnergyStorage
            IEnergyStorage neighbor = world.getCapability(
                    Capabilities.EnergyStorage.BLOCK, neighborPos, dir.getOpposite());
            if (neighbor == null) continue;

            if (io == SideConfig.SideIO.OUT && neighbor.canReceive()) {
                // OUTPUT 面：向外推送能量
                int toPush = Math.min(energyStored, maxDischargeRate);
                if (toPush > 0) {
                    int accepted = neighbor.receiveEnergy(toPush, false);
                    if (accepted > 0) {
                        energyStored = Math.max(0, energyStored - accepted);
                        lastOutputRate = accepted;
                    }
                }
            } else if (io == SideConfig.SideIO.IN && neighbor.canExtract()) {
                // INPUT 面：从外部拉取能量
                int space = maxEnergy - energyStored;
                int toPull = Math.min(space, maxChargeRate);
                if (toPull > 0) {
                    int extracted = neighbor.extractEnergy(toPull, false);
                    if (extracted > 0) {
                        energyStored = Math.min(maxEnergy, energyStored + extracted);
                        lastInputRate = extracted;
                    }
                }
            }
        }
    }

    // ==================== Tick ====================

    public void tick(Level world) {
        if (world.isClientSide()) return;

        // 首次初始化：注册电网身份
        if (needsInit && world instanceof ServerLevel serverWorld) {
            needsInit = false;
            registerPowerMemberships(serverWorld);
        }

        // 面配置驱动的直接能量传输
        transferEnergyDirect(world);

        // 配置了 OUT 面时按电网反馈的实际输出扣储能（无消费者/孤网时不白白流失）
        if (enabled && hasEnergyOut()) {
            int output = Math.min(energyStored, lastGridOutput);
            energyStored = Math.max(0, energyStored - output);
            lastOutputRate = output;
        } else {
            lastOutputRate = 0;
        }

        // 满电或停用时清零输入速率（避免 UI 残留旧值）
        if (!enabled || energyStored >= maxEnergy) {
            lastInputRate = 0;
        }

        setChanged();
        if (world.getGameTime() % 20 == 0) {
            world.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ==================== 模式切换 ====================

    public void toggleEnabled() {
        enabled = !enabled;
        setChanged();
        if (level instanceof ServerLevel serverWorld) {
            unregisterPowerMemberships(serverWorld);
            registerPowerMemberships(serverWorld);
        }
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ==================== UI 访问器 ====================

    public int getEnergyStored() { return energyStored; }
    public int getMaxEnergy() { return maxEnergy; }
    public int getMaxChargeRate() { return maxChargeRate; }
    public int getMaxDischargeRate() { return maxDischargeRate; }
    /** 最近一次实际充入速率（FE/t） */
    public int getLastInputRate() { return lastInputRate; }
    /** 最近一次实际放出速率（FE/t） */
    public int getLastOutputRate() { return lastOutputRate; }
    public int getRatedVoltage() { return ratedVoltage; }
    public boolean isEnabled() { return enabled; }
    public boolean isOverVoltageImmune() { return overVoltageImmune; }

    public double getEnergyPercent() {
        return maxEnergy <= 0 ? 0 : (double) energyStored / maxEnergy * 100.0;
    }

    public VoltageTier getVoltageTier() {
        return VoltageTier.fromVoltage(ratedVoltage);
    }

    /** 获取面配置 */
    public SideConfig getSideConfig() {
        return sideConfig;
    }

    /** 获取当前电网电压（供 UI 显示） */
    public int getCurrentGridVoltage() {
        if (powerNode != null && level instanceof ServerLevel sl) {
            return WorldPowerGrid.get(sl).getNodeVoltage(powerNode);
        }
        return 0;
    }

    // ==================== MenuProvider ====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.poly_mech.battery");
    }

    @Nullable
    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int containerId, net.minecraft.world.entity.player.Inventory playerInv, net.minecraft.world.entity.player.Player player) {
        return null; // LDLib2 UI 不使用原版菜单
    }

    // ==================== 持久化 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("EnergyStored", energyStored);
        tag.putInt("MaxEnergy", maxEnergy);
        tag.putInt("MaxChargeRate", maxChargeRate);
        tag.putInt("MaxDischargeRate", maxDischargeRate);
        tag.putInt("RatedVoltage", ratedVoltage);
        tag.putBoolean("Enabled", enabled);
        // 面配置
        tag.put("SideConfig", sideConfig.save());
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energyStored = tag.getInt("EnergyStored");
        if (tag.contains("MaxEnergy")) maxEnergy = tag.getInt("MaxEnergy");
        if (tag.contains("MaxChargeRate")) maxChargeRate = tag.getInt("MaxChargeRate");
        if (tag.contains("MaxDischargeRate")) maxDischargeRate = tag.getInt("MaxDischargeRate");
        if (tag.contains("RatedVoltage")) ratedVoltage = tag.getInt("RatedVoltage");
        enabled = tag.getBoolean("Enabled");
        // 面配置
        if (tag.contains("SideConfig")) {
            sideConfig.load(tag.getCompound("SideConfig"));
        }
    }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel) needsInit = true;
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            unregisterPowerMemberships(serverLevel);
        }
        super.setRemoved();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithFullMetadata(registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
