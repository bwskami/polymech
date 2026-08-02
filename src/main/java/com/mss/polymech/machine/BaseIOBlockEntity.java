package com.mss.polymech.machine;

import com.mss.polymech.power.PowerNetworkManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
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
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public abstract class BaseIOBlockEntity extends BlockEntity implements MenuProvider {

    protected int tickNum = 0;
    protected boolean isPowered = false;
    protected int storedPower;
    protected static final int MAX_STORED_POWER = 10000;
    protected boolean isWorking;
    protected boolean enable = false;
    protected int progress = 0;
    protected int maxProgress;
    protected boolean needsInit = true;

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
            PowerNetworkManager.get(serverWorld).registerConsumer(
                    be.getBlockPos(), be::getRequiredPower, be::receiveElectricCharge);
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

        // 子类每 tick 自定义逻辑（如：水桶自动转水、锅炉温度管理）
        be.onTick(world);

        if (!be.isPowered && be.storedPower < be.getPowerCostPerTick() && !be.hasFuelPower()) return;

        if (be.isOutputSlotAvailable()) {
            boolean hasRecipe = be.hasCorrectRecipe(world);
            boolean canRun = hasRecipe && (be.storedPower >= be.getPowerCostPerTick() || be.hasFuelPower());
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
                    be.storedPower -= be.getPowerCostPerTick();
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
            PowerNetworkManager.get(serverLevel).unregisterConsumer(getBlockPos());
        }
        super.setRemoved();
    }

    public void receiveElectricCharge(int amount) {
        storedPower = Math.min(storedPower + amount * 20, MAX_STORED_POWER);
    }

    public boolean needsPower() { return storedPower < getPowerCostPerTick(); }

    public int getRequiredPower() {
        if (isWorking || (isPowered && storedPower < MAX_STORED_POWER)) {
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
        storedPower = tag.getInt("storedPower");
        isWorking = tag.getBoolean("isWorking");
        enable = tag.getBoolean("enable");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", itemStackHandler.serializeNBT(registries));
        tag.putInt("progress", progress);
        tag.putInt("storedPower", storedPower);
        tag.putBoolean("isWorking", isWorking);
        tag.putBoolean("enable", enable);
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
}
