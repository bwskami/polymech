package com.mss.polymech.block.entity;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class ConveyorBlockEntity extends BlockEntity {
    private static final int MAX_ITEMS = 8;
    private static final double SPEED = 0.02;

    private final List<TransportedItem> transportedItems = new ArrayList<>();

    public ConveyorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONVEYOR.get(), pos, state);
    }

    public boolean addTransportedItem(ItemStack stack) {
        return addTransportedItem(stack, 0.0);
    }

    public boolean addTransportedItem(ItemStack stack, double startProgress) {
        if (transportedItems.size() >= MAX_ITEMS) return false;
        transportedItems.add(new TransportedItem(stack.copy(), startProgress));
        setChanged();
        syncToClient();
        return true;
    }

    public ItemStack removeLastItem() {
        if (transportedItems.isEmpty()) return ItemStack.EMPTY;
        TransportedItem removed = transportedItems.remove(transportedItems.size() - 1);
        setChanged();
        syncToClient();
        return removed.stack.copy();
    }

    public List<TransportedItem> getTransportedItems() {
        return transportedItems;
    }

    public static void tick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity be) {
        if (level == null) return;
        if (be.transportedItems.isEmpty()) return;

        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        Iterator<TransportedItem> iter = be.transportedItems.iterator();
        while (iter.hasNext()) {
            TransportedItem item = iter.next();
            item.progress += SPEED;

            if (item.progress >= 1.0) {
                if (!level.isClientSide()) {
                    BlockPos targetPos = pos.relative(facing);
                    if (type == ConveyorType.UP) {
                        targetPos = pos.relative(facing).above();
                    } else if (type == ConveyorType.DOWN) {
                        targetPos = pos.relative(facing).below();
                    }

                    boolean transferred = false;

                    IItemHandler handler = level.getCapability(
                            Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());
                    if (handler != null) {
                        ItemStack remainder = insertItem(handler, item.stack);
                        if (remainder.isEmpty()) {
                            transferred = true;
                        } else {
                            item.stack = remainder;
                        }
                    }

                    if (!transferred) {
                        BlockState targetBlockState = level.getBlockState(targetPos);
                        if (targetBlockState.getBlock() instanceof ConveyorBlock) {
                            BlockEntity targetBE = level.getBlockEntity(targetPos);
                            if (targetBE instanceof ConveyorBlockEntity targetConveyor) {
                                Direction targetFacing = targetBlockState.getValue(ConveyorBlock.FACING);
                                double startProgress = (facing == targetFacing) ? 0.0 : 0.5;
                                if (targetConveyor.addTransportedItem(item.stack, startProgress)) {
                                    transferred = true;
                                }
                            }
                        }
                    }

                    if (!transferred) {
                        BlockPos belowTarget = targetPos.below();
                        BlockState belowState = level.getBlockState(belowTarget);
                        if (belowState.getBlock() instanceof ConveyorBlock) {
                            BlockEntity belowBE = level.getBlockEntity(belowTarget);
                            if (belowBE instanceof ConveyorBlockEntity belowConveyor) {
                                Direction belowFacing = belowState.getValue(ConveyorBlock.FACING);
                                double startProgress = (facing == belowFacing) ? 0.0 : 0.5;
                                if (belowConveyor.addTransportedItem(item.stack, startProgress)) {
                                    transferred = true;
                                }
                            }
                        }
                    }

                    if (!transferred) {
                        Vec3 dropPos = getWorldPosition(pos, facing, type, 1.0);
                        ItemEntity dropped = new ItemEntity(level,
                                dropPos.x, dropPos.y, dropPos.z,
                                item.stack.copy());
                        dropped.setDeltaMovement(
                                facing.getStepX() * 0.15,
                                -0.1,
                                facing.getStepZ() * 0.15);
                        level.addFreshEntity(dropped);
                        transferred = true;
                    }

                    if (transferred) {
                        iter.remove();
                        be.setChanged();
                        be.syncToClient();
                    }
                } else {
                    item.progress = 0.999;
                }
            }
        }

        for (int i = 0; i < be.transportedItems.size(); i++) {
            TransportedItem item = be.transportedItems.get(i);
            if (i > 0) {
                TransportedItem prev = be.transportedItems.get(i - 1);
                if (item.progress > prev.progress - 0.15) {
                    item.progress = prev.progress - 0.15;
                }
            }
        }
    }

    private static ItemStack insertItem(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < handler.getSlots(); i++) {
            remaining = handler.insertItem(i, remaining, false);
            if (remaining.isEmpty()) return ItemStack.EMPTY;
        }
        return remaining;
    }

    public static Vec3 getWorldPosition(BlockPos pos, Direction facing, ConveyorType type, double progress) {
        double x = pos.getX() + 0.5 + facing.getStepX() * (progress - 0.5);
        double z = pos.getZ() + 0.5 + facing.getStepZ() * (progress - 0.5);
        double y = pos.getY() + 0.25;
        if (type == ConveyorType.UP) {
            y += progress * 1.0;
        } else if (type == ConveyorType.DOWN) {
            y += (1.0 - progress) * 1.0;
        }
        return new Vec3(x, y, z);
    }

    private void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // ==================== NBT 同步 ====================

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (TransportedItem item : transportedItems) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.put("Item", item.stack.save(registries));
            itemTag.putDouble("Progress", item.progress);
            list.add(itemTag);
        }
        tag.put("TransportedItems", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        transportedItems.clear();
        ListTag list = tag.getList("TransportedItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            ItemStack stack = ItemStack.parse(registries, itemTag.getCompound("Item")).orElse(ItemStack.EMPTY);
            double progress = itemTag.getDouble("Progress");
            if (!stack.isEmpty()) {
                transportedItems.add(new TransportedItem(stack, progress));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ==================== 运输物品数据 ====================

    public static class TransportedItem {
        public ItemStack stack;
        public double progress;

        public TransportedItem(ItemStack stack, double progress) {
            this.stack = stack;
            this.progress = progress;
        }
    }
}