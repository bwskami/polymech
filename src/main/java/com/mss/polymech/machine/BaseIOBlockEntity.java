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
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public abstract class BaseIOBlockEntity extends BlockEntity implements MenuProvider {

    protected int tickNum = 0;
    protected boolean isPowered = false;
    protected int storedPower;
    protected static final int MAX_STORED_POWER = 10000;
    protected boolean isWorking;
    protected boolean enable = true;
    protected int progress = 0;
    protected int maxProgress;
    protected boolean needsInit = true;

    protected final ContainerData propertyDelegate;

    protected final ItemStackHandler itemStackHandler = new ItemStackHandler(getInvSize()) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
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

    public static <T extends BaseIOBlockEntity> void tick(Level world, BlockPos pos, BlockState state, T be) {
        if (world.isClientSide()) return;

        if (be.needsInit && world instanceof ServerLevel serverWorld) {
            be.needsInit = false;
            PowerNetworkManager.get(serverWorld).registerConsumer(
                    be.getBlockPos(), be::getRequiredPower, be::receiveElectricCharge);
        }

        if (!be.enable) {
            be.isWorking = false;
            world.sendBlockUpdated(pos, state, state, 3);
            be.setChanged();
            return;
        }

        be.tickNum++;

        if (!be.isPowered && be.storedPower < be.getPowerCostPerTick()) return;

        if (be.isOutputSlotAvailable()) {
            boolean hasRecipe = be.hasCorrectRecipe(world);
            if (be.needsPower() || !hasRecipe) {
                be.isWorking = false;
            } else if (!be.needsPower() && !be.isWorking) {
                be.isWorking = true;
            }
            be.setChanged();
            world.sendBlockUpdated(pos, state, state, 3);

            if (hasRecipe && be.storedPower >= be.getPowerCostPerTick()) {
                be.incrementProgress();
                be.storedPower -= be.getPowerCostPerTick();
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
        itemStackHandler.deserializeNBT(registries, tag.getCompound("inventory"));
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
