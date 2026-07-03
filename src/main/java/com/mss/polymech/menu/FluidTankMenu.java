package com.mss.polymech.menu;

import com.mss.polymech.block.entity.FluidTankBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

public class FluidTankMenu extends AbstractContainerMenu {
    private final FluidTankBlockEntity blockEntity;
    private final BlockPos pos;

    public static final int INPUT_SLOT_X = 136;
    public static final int OUTPUT_SLOT_X = 136;
    public static final int INPUT_SLOT_Y = 16;
    public static final int OUTPUT_SLOT_Y = 58;

    private final SimpleContainerData data;

    public FluidTankMenu(int containerId, Inventory playerInventory, FluidTankBlockEntity blockEntity) {
        super(ModMenuTypes.FLUID_TANK_MENU.get(), containerId);
        this.blockEntity = blockEntity;
        this.pos = blockEntity.getBlockPos();

        this.data = new SimpleContainerData(2);
        this.addDataSlots(data);

        IItemHandler handler = blockEntity.getBucketHandler();
        addSlot(new SlotItemHandler(handler, 0, INPUT_SLOT_X, INPUT_SLOT_Y));
        addSlot(new SlotItemHandler(handler, 1, OUTPUT_SLOT_X, OUTPUT_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
        });

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                addSlot(new Slot(playerInventory, j + i * 9 + 9, 8 + j * 18, 84 + i * 18));
            }
        }
        for (int i = 0; i < 9; i++) {
            addSlot(new Slot(playerInventory, i, 8 + i * 18, 142));
        }
    }

    public FluidTankMenu(int containerId, Inventory playerInventory, FriendlyByteBuf extraData) {
        this(containerId, playerInventory, getBlockEntityFromBuf(playerInventory, extraData));
    }

    private static FluidTankBlockEntity getBlockEntityFromBuf(Inventory inv, FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        return (FluidTankBlockEntity) inv.player.level().getBlockEntity(pos);
    }

    public FluidTankBlockEntity getBlockEntity() {
        return blockEntity;
    }

    public FluidStack getFluidStack() {
        return blockEntity.getFluidStack();
    }

    public int getFluidAmount() {
        return data.get(0);
    }

    public int getCapacity() {
        return FluidTankBlockEntity.CAPACITY;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        data.set(0, blockEntity.getFluidAmount());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot.hasItem()) {
            ItemStack current = slot.getItem();
            result = current.copy();
            if (index < 2) {
                if (!this.moveItemStackTo(current, 2, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                if (!this.moveItemStackTo(current, 0, 1, false)) {
                    return ItemStack.EMPTY;
                }
            }
            if (current.isEmpty()) {
                slot.set(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(ContainerLevelAccess.create(blockEntity.getLevel(), pos),
                player, blockEntity.getBlockState().getBlock());
    }
}
