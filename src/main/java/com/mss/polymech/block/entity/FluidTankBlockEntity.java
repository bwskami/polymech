package com.mss.polymech.block.entity;

import com.mss.polymech.menu.FluidTankMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

public class FluidTankBlockEntity extends BlockEntity implements MenuProvider {
    public static final int CAPACITY = 16000;

    private final FluidTank tank = new FluidTank(CAPACITY) {
        @Override
        protected void onContentsChanged() {
            setChanged();
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            FluidStack result = super.drain(maxDrain, action);
            if (action.execute() && fluid.getAmount() <= 0) {
                fluid = FluidStack.EMPTY;
            }
            return result;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            FluidStack result = super.drain(resource, action);
            if (action.execute() && fluid.getAmount() <= 0) {
                fluid = FluidStack.EMPTY;
            }
            return result;
        }
    };

    private final ItemStackHandler bucketHandler = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }
    };

    public FluidTankBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.FLUID_TANK.get(), pos, blockState);
    }

    public IFluidHandler getFluidHandler() {
        return tank;
    }

    public IItemHandler getBucketHandler() {
        return bucketHandler;
    }

    public FluidStack getFluidStack() {
        return tank.getFluid();
    }

    public int getFluidAmount() {
        return tank.getFluidAmount();
    }

    public void dropContents(Level level, BlockPos pos) {
        if (!tank.isEmpty()) {
            int totalBuckets = tank.getFluidAmount() / 1000;
            for (int i = 0; i < totalBuckets; i++) {
                ItemStack bucket = FluidUtil.getFilledBucket(tank.getFluid());
                if (!bucket.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), bucket);
                }
            }
            tank.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.EXECUTE);
        }
        for (int i = 0; i < bucketHandler.getSlots(); i++) {
            ItemStack stack = bucketHandler.getStackInSlot(i);
            if (!stack.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
            }
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FluidTankBlockEntity be) {
        be.processBuckets();
    }

    private void processBuckets() {
        ItemStack input = bucketHandler.getStackInSlot(0);
        if (input.isEmpty()) return;

        if (input.getItem() instanceof net.minecraft.world.item.BucketItem && input.getItem() != Items.BUCKET) {
            FluidStack contained = FluidUtil.getFluidContained(input.copyWithCount(1)).orElse(FluidStack.EMPTY);
            if (contained.isEmpty()) return;
            int filled = tank.fill(contained, IFluidHandler.FluidAction.SIMULATE);
            if (filled < contained.getAmount()) return;
            if (!canOutputAccept(new ItemStack(Items.BUCKET))) return;
            tank.fill(contained, IFluidHandler.FluidAction.EXECUTE);
            input.shrink(1);
            addToOutput(new ItemStack(Items.BUCKET));
            if (input.isEmpty()) bucketHandler.setStackInSlot(0, ItemStack.EMPTY);
        } else if (input.is(Items.BUCKET)) {
            if (tank.isEmpty()) return;
            FluidStack drained = tank.drain(1000, IFluidHandler.FluidAction.SIMULATE);
            if (drained.getAmount() < 1000) return;
            ItemStack filledBucket = FluidUtil.getFilledBucket(drained);
            if (filledBucket.isEmpty()) return;
            if (!canOutputAccept(filledBucket)) return;
            tank.drain(1000, IFluidHandler.FluidAction.EXECUTE);
            input.shrink(1);
            addToOutput(filledBucket);
            if (input.isEmpty()) bucketHandler.setStackInSlot(0, ItemStack.EMPTY);
        }
    }

    private boolean canOutputAccept(ItemStack stack) {
        ItemStack output = bucketHandler.getStackInSlot(1);
        if (output.isEmpty()) return true;
        return ItemStack.isSameItemSameComponents(output, stack) && output.getCount() < output.getMaxStackSize();
    }

    private void addToOutput(ItemStack stack) {
        ItemStack output = bucketHandler.getStackInSlot(1);
        if (output.isEmpty()) {
            bucketHandler.setStackInSlot(1, stack.copy());
        } else if (ItemStack.isSameItemSameComponents(output, stack) && output.getCount() < output.getMaxStackSize()) {
            output.grow(stack.getCount());
        }
    }

    // ==================== NBT ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tank.writeToNBT(registries, tag);
        tag.put("BucketInventory", bucketHandler.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        tank.readFromNBT(registries, tag);
        if (tag.contains("BucketInventory")) {
            bucketHandler.deserializeNBT(registries, tag.getCompound("BucketInventory"));
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tank.writeToNBT(registries, tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        tank.readFromNBT(registries, tag);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ==================== MenuProvider ====================

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.poly_mech.fluid_tank");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return new FluidTankMenu(containerId, playerInventory, this);
    }
}
