package com.mss.polymech.block.entity;

import com.mss.polymech.block.ConveyorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

public class ConveyorBlockEntity extends BlockEntity {
    // 传送带上最多容纳 8 个物品
    private static final int SLOT_COUNT = 8;

    // 物品存储
    private final ItemStackHandler itemHandler = new ItemStackHandler(SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (level instanceof ServerLevel serverLevel) {
                serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    };

    private int tickCounter = 0;

    public ConveyorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONVEYOR.get(), pos, state);
    }

    // 服务端 tick 逻辑
    public static void serverTick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity be) {
        be.tickCounter++;

        // 每 10 tick 移动一次物品
        if (be.tickCounter % 10 == 0) {
            be.moveItems(level, pos, state);
        }
    }

    private void moveItems(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(ConveyorBlock.FACING);
        BlockPos targetPos = pos.relative(facing);

        for (int i = 0; i < itemHandler.getSlots(); i++) {
            ItemStack stack = itemHandler.getStackInSlot(i);
            if (stack.isEmpty()) continue;

            // 尝试输出到相邻容器
            IItemHandler targetHandler = level.getCapability(
                    Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());

            if (targetHandler != null) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(targetHandler, stack, false);
                if (remainder.isEmpty()) {
                    itemHandler.setStackInSlot(i, ItemStack.EMPTY);
                    continue;
                }
                itemHandler.setStackInSlot(i, remainder);
            }

            // 传递给下一个传送带
            if (level.getBlockState(targetPos).getBlock() instanceof ConveyorBlock) {
                BlockEntity targetBE = level.getBlockEntity(targetPos);
                if (targetBE instanceof ConveyorBlockEntity targetConveyor) {
                    for (int j = 0; j < targetConveyor.itemHandler.getSlots(); j++) {
                        if (targetConveyor.itemHandler.getStackInSlot(j).isEmpty()) {
                            targetConveyor.itemHandler.setStackInSlot(j, stack.copy());
                            itemHandler.setStackInSlot(i, ItemStack.EMPTY);
                            targetConveyor.setChanged();
                            break;
                        }
                    }
                }
            }
        }
    }

    // 从上方接收物品（供漏斗等使用）
    public boolean insertItem(ItemStack stack) {
        for (int i = 0; i < itemHandler.getSlots(); i++) {
            if (itemHandler.getStackInSlot(i).isEmpty()) {
                itemHandler.setStackInSlot(i, stack.copy());
                setChanged();
                return true;
            }
        }
        return false;
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    // ==================== NBT 同步 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Items", itemHandler.serializeNBT(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        itemHandler.deserializeNBT(registries, tag.getCompound("Items"));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.put("Items", itemHandler.serializeNBT(registries));
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.contains("Items")) {
            itemHandler.deserializeNBT(registries, tag.getCompound("Items"));
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}