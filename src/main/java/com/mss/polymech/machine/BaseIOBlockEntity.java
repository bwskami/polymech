package com.mss.polymech.machine;

import com.mss.polymech.powergrid.GridNode;
import com.mss.polymech.powergrid.GridNodeBlock;
import com.mss.polymech.powergrid.MachineEnergyStorage;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public abstract class BaseIOBlockEntity extends BlockEntity implements MenuProvider {

    protected int tickNum = 0;
    protected boolean isPowered = false;
    protected int internalEnergy;
    protected static final int MAX_INTERNAL_ENERGY = 10000;
    protected boolean isWorking;
    protected boolean enable = false;
    protected int progress = 0;
    protected int maxProgress;
    protected boolean needsInit = true;

    // ==================== 面配置（Mekanism 风格） ====================

    /** 面 IO 配置，支持能源/物品/流体三种能力类型 */
    protected final SideConfig sideConfig = new SideConfig();

    // ==================== 主动输出配置 ====================

    /** 主动输出周期（tick） */
    private static final int PROXY_EXPORT_INTERVAL = 10;
    /** 每周期每个槽位最大输出物品数 */
    private static final int MAX_ITEM_EXPORT_PER_CYCLE = 16;
    /** 每周期每个储罐最大输出流体量（mB） */
    private static final int MAX_FLUID_EXPORT_PER_CYCLE = 1000;

    protected final ContainerData propertyDelegate;

    protected final ItemStackHandler itemStackHandler = new ItemStackHandler(getInvSize()) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, @NotNull net.minecraft.world.item.ItemStack stack) {
            return BaseIOBlockEntity.this.isItemValidForSlot(slot, stack);
        }

        @Override
        public @NotNull net.minecraft.world.item.ItemStack insertItem(int slot, @NotNull net.minecraft.world.item.ItemStack stack, boolean simulate) {
            if (!isItemValid(slot, stack)) return stack;
            return super.insertItem(slot, stack, simulate);
        }
    };
    protected IItemHandler input = getInput();
    protected IItemHandler output = getOutput();

    public BaseIOBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state, int maxProgress) {
        super(type, pos, state);
        this.maxProgress = maxProgress;
        this.propertyDelegate = createPropertyDelegate();
        // 面配置变更时触发方块更新和能力刷新
        this.sideConfig.setChangeListener(() -> {
            setChanged();
            if (level != null) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        });
    }

    protected abstract int getInvSize();
    protected abstract ContainerData createPropertyDelegate();
    protected abstract int getPowerCostPerTick();
    protected abstract Optional<RecipeHolder<?>> getMatchRecipe(Level world);
    protected abstract void craftItem(Level world);
    protected abstract boolean hasCorrectRecipe(Level world);
    protected abstract IItemHandler getInput();
    protected abstract IItemHandler getOutput();
    protected abstract int getOutputSlotIndex();

    /**
     * 每 tick 调用的自定义逻辑钩子（仅服务端、机器启用时）。
     * 子类可覆盖以实现自动转换等逻辑。
     */
    protected void onTick(Level world) {}

    /**
     * 是否有燃料动力（用于非电力驱动的机器，如蒸汽锅炉）。
     * 子类可覆盖以返回 true，使机器在燃料充足时运行。
     */
    protected boolean hasFuelPower() { return false; }

    /**
     * 是否使用基类的配方加工流程（progress 累加 + craftItem）。
     * 发电机类机器覆盖为 false，自行在 {@link #onTick(Level)} 中处理能量输出。
     */
    protected boolean usesRecipeCrafting() { return true; }

    /** 本机注册到电网时使用的节点（首次注册时解析并缓存，注销时复用） */
    @Nullable
    protected GridNode powerNode;

    /**
     * 解析本机的电网节点：方块实现 {@link GridNodeBlock} 时取节点ID最小的节点。
     * 非电网方块返回 null。
     */
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

    /**
     * 注册电网身份。默认为用电器；发电机子类可覆盖以额外注册为发电机。
     */
    protected void registerPowerMemberships(ServerLevel world) {
        powerNode = getGridNode();
        if (powerNode != null) {
            WorldPowerGrid.get(world).registerConsumer(powerNode, this::getRequiredPower, this::receiveElectricCharge);
        }
    }

    /** 注销电网身份，与 {@link #registerPowerMemberships(ServerLevel)} 对称。 */
    protected void unregisterPowerMemberships(ServerLevel world) {
        if (powerNode != null) {
            WorldPowerGrid.get(world).unregisterConsumer(powerNode);
            powerNode = null;
        }
    }

    /**
     * 子类覆盖此方法以定义 GUI 槽位的物品验证规则。
     * 默认允许所有物品放入所有槽位。
     */
    protected boolean isItemValidForSlot(int slot, @NotNull net.minecraft.world.item.ItemStack stack) {
        return true;
    }

    public static <T extends BaseIOBlockEntity> void tick(Level world, BlockPos pos, BlockState state, T be) {
        if (world.isClientSide()) return;

        // 建造动画服务端计时（仅 HorizontalSteamBoilerBlockEntity 需要）
        if (be instanceof com.mss.polymech.machine.production.HorizontalSteamBoilerBlockEntity boiler) {
            boiler.tickServerSide();
        }

        if (be.needsInit && world instanceof ServerLevel serverWorld) {
            be.needsInit = false;
            be.registerPowerMemberships(serverWorld);
        }

        // 主动输出：周期性把 OUTPUT 代理面的产物推送到结构外部（停机时也输出）
        if (world.getGameTime() % PROXY_EXPORT_INTERVAL == 0) {
            be.exportProxyOutputs(world);
        }

        if (!be.enable) {
            be.isWorking = false;
            // 停机时仍然调用 onTick（用于锅炉余温冷却、水桶转换等）
            be.onTick(world);
            world.sendBlockUpdated(pos, state, state, 3);
            be.setChanged();
            return;
        }

        be.tickNum++;

        // 子类每 tick 自定义逻辑（如：水桶自动转水、锅炉温度管理、发电机发电）
        be.onTick(world);

        // 发电机等自行处理能量的机器不走配方加工流程
        if (!be.usesRecipeCrafting()) {
            be.setChanged();
            return;
        }

        if (!be.isPowered && be.internalEnergy < be.getPowerCostPerTick() && !be.hasFuelPower()) return;

        if (be.isOutputSlotAvailable()) {
            boolean hasRecipe = be.hasCorrectRecipe(world);
            boolean canRun = hasRecipe && (be.internalEnergy >= be.getPowerCostPerTick() || be.hasFuelPower());
            if (!canRun) {
                be.isWorking = false;
            } else if (!be.isWorking) {
                be.isWorking = true;
            }
            be.setChanged();
            world.sendBlockUpdated(pos, state, state, 3);

            if (canRun) {
                be.incrementProgress();
                if (!be.hasFuelPower()) {
                    be.internalEnergy -= be.getPowerCostPerTick();
                }
                if (be.hasCraftingFinished()) {
                    be.craftItem(world);
                    be.resetProgress();
                }
            } else {
                be.resetProgress();
            }
        } else {
            be.resetProgress();
        }
        be.setChanged();
    }

    public IItemHandler getInputHandler() { return input; }
    public IItemHandler getOutputHandler() { return output; }

    protected boolean hasCraftingFinished() { return progress >= maxProgress; }

    public NonNullList<ItemStack> getItems() {
        NonNullList<ItemStack> items = NonNullList.withSize(itemStackHandler.getSlots(), ItemStack.EMPTY);
        for (int i = 0; i < itemStackHandler.getSlots(); i++) {
            items.set(i, itemStackHandler.getStackInSlot(i));
        }
        return items;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(getBlockPos(), level.getBlockState(getBlockPos()), level.getBlockState(getBlockPos()), 3);
        }
    }

    public void toggleEnable() {
        setEnable(!enable);
    }

    @Override
    public void setLevel(Level pLevel) {
        super.setLevel(pLevel);
        if (pLevel instanceof ServerLevel) needsInit = true;
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            unregisterPowerMemberships(serverLevel);
        }
        super.setRemoved();
    }

    public void receiveElectricCharge(int amount) {
        // 面配置检查：仅从 IN 面接收能量
        if (level != null && !level.isClientSide()) {
            boolean hasInput = false;
            for (Direction dir : Direction.values()) {
                if (sideConfig.canInputEnergy(dir)) { hasInput = true; break; }
            }
            if (!hasInput) return;
        }
        internalEnergy = Math.min(internalEnergy + amount, MAX_INTERNAL_ENERGY);
    }

    public boolean needsPower() { return internalEnergy < getPowerCostPerTick(); }

    public int getRequiredPower() {
        if (isWorking || (isPowered && internalEnergy < MAX_INTERNAL_ENERGY)) {
            return getPowerCostPerTick();
        }
        return 0;
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        // 兼容旧存档：如果保存的槽位数与当前不一致，跳过加载（避免崩溃）
        if (tag.contains("inventory")) {
            var invTag = tag.getCompound("inventory");
            int savedSize = invTag.getInt("Size");
            if (savedSize == getInvSize()) {
                itemStackHandler.deserializeNBT(registries, invTag);
            }
            // 槽位数不匹配时保留新创建的空 handler，旧物品数据丢失
        }
        progress = tag.getInt("progress");
        // 兼容旧存档：同时检查新旧键名
        if (tag.contains("internalEnergy")) {
            internalEnergy = tag.getInt("internalEnergy");
        } else {
            internalEnergy = tag.getInt("storedPower");
        }
        isWorking = tag.getBoolean("isWorking");
        enable = tag.getBoolean("enable");
        // 加载面配置
        if (tag.contains("SideConfig")) {
            sideConfig.load(tag.getCompound("SideConfig"));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", itemStackHandler.serializeNBT(registries));
        tag.putInt("progress", progress);
        tag.putInt("internalEnergy", internalEnergy);
        tag.putBoolean("isWorking", isWorking);
        tag.putBoolean("enable", enable);
        // 保存面配置
        tag.put("SideConfig", sideConfig.save());
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithFullMetadata(registries);
    }

    protected void resetProgress() { progress = 0; }
    protected void incrementProgress() { progress++; }

    // -- UI 访问器 --
    public int getProgress() { return progress; }
    public int getMaxProgress() { return maxProgress; }
    public boolean isEnable() { return enable; }
    public boolean isWorkingState() { return isWorking; }
    public int getStoredPower() { return internalEnergy; }

    /** 获取内部能量缓冲（FE） */
    public int getInternalEnergy() { return internalEnergy; }
    public int getMaxInternalEnergy() { return MAX_INTERNAL_ENERGY; }

    /**
     * 获取 IEnergyStorage 包装器，供 NeoForge 能力系统使用。
     * 外部 Mod 可通过此接口与本机进行 FE 交互。
     */
    public MachineEnergyStorage getEnergyStorage() {
        return new MachineEnergyStorage(
                () -> internalEnergy,
                newVal -> { internalEnergy = Math.max(0, Math.min(newVal, MAX_INTERNAL_ENERGY)); return internalEnergy; },
                MAX_INTERNAL_ENERGY,
                getPowerCostPerTick() * 20,  // maxReceive: 允许快速充入
                getPowerCostPerTick() * 20   // maxExtract: 允许快速抽出
        );
    }
    public double getProgressPercent() {
        return maxProgress <= 0 ? 0.0 : progress / (double) maxProgress;
    }

    protected boolean canOutputAccept(ItemStack result) {
        ItemStack out = itemStackHandler.getStackInSlot(getOutputSlotIndex());
        return (out.isEmpty() || out.getItem() == result.getItem())
                && out.getCount() + result.getCount() <= 64;
    }

    protected boolean isOutputSlotAvailable() {
        ItemStack outputStack = itemStackHandler.getStackInSlot(getOutputSlotIndex());
        return outputStack.isEmpty() || outputStack.getCount() < 64;
    }

    public ItemStackHandler getItemStackHandler() { return itemStackHandler; }

    /** 获取面配置（供 UI/网络包/能力注册使用） */
    public SideConfig getSideConfig() { return sideConfig; }

    // ==================== 面配置集成辅助 ====================

    /**
     * 检查指定能力类型是否在某方向上被 sideConfig 允许（IN 或 OUT）。
     * 若所有面均为 NONE，则跳过检查（向后兼容未配置的情况）。
     */
    public boolean isSideConfigAllowed(Direction worldDir, SideConfig.CapabilityType capType) {
        SideConfig.SideIO io = sideConfig.getConfig(capType, worldDir);
        return io == SideConfig.SideIO.IN || io == SideConfig.SideIO.OUT;
    }

    /**
     * 检查指定能力类型是否有任何面被配置为 OUTPUT。
     * 若所有面均为 NONE（未配置），返回 true（向后兼容）。
     */
    public boolean hasAnyOutputFace(SideConfig.CapabilityType capType) {
        for (Direction dir : Direction.values()) {
            if (sideConfig.getConfig(capType, dir) == SideConfig.SideIO.OUT) return true;
        }
        // 所有面 NONE = 未配置 → 视为允许（向后兼容）
        return true;
    }

    /**
     * 检查指定能力类型是否有任何面被配置为 INPUT。
     */
    public boolean hasAnyInputFace(SideConfig.CapabilityType capType) {
        for (Direction dir : Direction.values()) {
            if (sideConfig.getConfig(capType, dir) == SideConfig.SideIO.IN) return true;
        }
        return true;
    }

    // ==================== 主动输出（OUTPUT 代理面向外推送） ====================

    /**
     * 遍历 Block 定义的所有代理位置，将 OUTPUT 方向的物品/流体
     * 主动推送到结构外部的相邻方块。跳过属于机器结构自身的相邻位置。
     */
    protected void exportProxyOutputs(Level world) {
        // 面配置检查：若没有任何 OUTPUT 面，跳过主动输出
        if (!hasAnyOutputFace(SideConfig.CapabilityType.ITEM) && !hasAnyOutputFace(SideConfig.CapabilityType.FLUID)) return;
        if (!(world.getBlockState(worldPosition).getBlock() instanceof BaseMachineBlock mb)) return;
        Direction facing = world.getBlockState(worldPosition).getValue(BaseMachineBlock.FACING);
        for (Vec3i local : mb.enumerateLocalOffsets()) {
            exportItemAtProxy(world, mb, facing, local);
            exportFluidAtProxy(world, mb, facing, local);
        }
    }

    /** 将 OUTPUT 物品代理面的槽位内容推送到外部相邻容器 */
    private void exportItemAtProxy(Level world, BaseMachineBlock mb, Direction facing, Vec3i local) {
        BaseMachineBlock.ItemProxy proxy = mb.getItemProxy(local);
        if (proxy == null || proxy.io() != BaseMachineBlock.ProxyIO.OUTPUT) return;
        int[] slots = proxy.slots();
        BlockPos sidePos = worldPosition.offset(BaseMachineBlock.rotateVec3i(local, facing));
        for (Direction dir : Direction.values()) {
            BlockPos targetPos = sidePos.relative(dir);
            Vec3i targetLocal = BaseMachineBlock.unrotateVec3i(targetPos.subtract(worldPosition), facing);
            if (mb.isLocalPartOfStructure(targetLocal)) continue;
            if (!proxy.allowsWorldFace(dir, facing)) continue; // 仅从声明的有效面推送
            IItemHandler target = world.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, dir.getOpposite());
            if (target == null) continue;
            for (int internalSlot : slots) {
                ItemStack stack = itemStackHandler.getStackInSlot(internalSlot);
                if (stack.isEmpty()) continue;
                ItemStack toMove = stack.copyWithCount(Math.min(stack.getCount(), MAX_ITEM_EXPORT_PER_CYCLE));
                // 用插入前的数量计算实际接受量：部分目标 handler 会就地修改传入栈，
                // 若插入后再读 toMove.getCount() 会把 moved 算错（曾导致刷物）
                int planned = toMove.getCount();
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, toMove, false);
                int moved = planned - remainder.getCount();
                if (moved > 0) {
                    itemStackHandler.extractItem(internalSlot, moved, false);
                }
            }
        }
    }

    /** 将 OUTPUT 流体代理面的储罐内容推送到外部相邻储罐 */
    private void exportFluidAtProxy(Level world, BaseMachineBlock mb, Direction facing, Vec3i local) {
        BaseMachineBlock.FluidProxy proxy = mb.getFluidProxy(local);
        if (proxy == null || proxy.io() != BaseMachineBlock.ProxyIO.OUTPUT) return;
        int[] tanks = proxy.tanks();
        BlockPos sidePos = worldPosition.offset(BaseMachineBlock.rotateVec3i(local, facing));
        for (Direction dir : Direction.values()) {
            BlockPos targetPos = sidePos.relative(dir);
            Vec3i targetLocal = BaseMachineBlock.unrotateVec3i(targetPos.subtract(worldPosition), facing);
            if (mb.isLocalPartOfStructure(targetLocal)) continue;
            if (!proxy.allowsWorldFace(dir, facing)) continue; // 仅从声明的有效面推送
            IFluidHandler target = world.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, dir.getOpposite());
            if (target == null) continue;
            for (int tankIndex : tanks) {
                IFluidHandler tank = getFluidTank(tankIndex);
                if (tank == null) continue;
                // 先模拟抽取，按对方实际接受量执行，避免流体丢失
                FluidStack drained = tank.drain(MAX_FLUID_EXPORT_PER_CYCLE, IFluidHandler.FluidAction.SIMULATE);
                if (drained.isEmpty()) continue;
                int filled = target.fill(drained, IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    tank.drain(filled, IFluidHandler.FluidAction.EXECUTE);
                }
            }
        }
    }

    // ==================== 侧面方块流体代理解析（子类覆盖） ====================

    /**
     * 按逻辑储罐索引获取实际的 IFluidHandler。
     * <p>
     * 与物品代理对称：Block 通过 {@code getFluidProxy} 声明储罐索引，
     * 能力层据此调用本方法取出具体储罐。储罐数量不受一进一出限制，
     * 多进多出的化学反应机器可定义任意多个储罐。默认无储罐返回 null。
     * </p>
     *
     * @param tankIndex Block 定义的逻辑储罐索引
     * @return 对应的 IFluidHandler，或 null
     */
    @Nullable
    public IFluidHandler getFluidTank(int tankIndex) {
        return null;
    }
}
