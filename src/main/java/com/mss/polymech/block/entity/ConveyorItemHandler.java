package com.mss.polymech.block.entity;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorSpec;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 传送带物品缓冲处理器。
 * <p>
 * 职责：管理 FIFO 物品队列，每个物品附带计时器，计时到后尝试输出到下一个方块。
 * 不涉及任何视觉概念（位置、动画、间距），视觉效果由渲染器自行计算。
 * </p>
 */
public class ConveyorItemHandler {
    private final ConveyorSpec spec;
    private final ConveyorBlockEntity blockEntity;

    /** 缓冲队列，索引 0 = 最新放入（入口），末尾 = 最早放入（出口） */
    private final List<ItemStack> buffer = new ArrayList<>();

    /** 与 buffer 一一对应的计时器，记录每个物品已停留的 tick 数 */
    private final List<Integer> timers = new ArrayList<>();

    public ConveyorItemHandler(ConveyorBlockEntity blockEntity) {
        this(blockEntity, ConveyorSpec.DEFAULT);
    }

    public ConveyorItemHandler(ConveyorBlockEntity blockEntity, ConveyorSpec spec) {
        this.blockEntity = blockEntity;
        this.spec = spec;
    }

    public ConveyorSpec getSpec() {
        return spec;
    }

    /** 添加物品到入口（默认从起点开始）。返回是否成功。 */
    public boolean addItem(ItemStack stack) {
        return addItem(stack, 0.0);
    }

    /**
     * 添加物品到传送带，指定起始进度。
     *
     * @param stack         物品
     * @param startProgress 起始进度（0.0=入口，0.5=中间，1.0=出口）
     * @return 是否成功
     */
    public boolean addItem(ItemStack stack, double startProgress) {
        if (stack.isEmpty()) return false;
        if (buffer.size() >= spec.slotCount()) return false;

        int totalTicks = spec.slotCount() * spec.ticksPerSlot();
        int startTimer = (int)(startProgress * totalTicks);

        // 间隔存入：从入口送来的（startProgress=0）需要等入口空出。
        // 从侧面来的（startProgress>0）直接插入，不受入口间隔限制。
        if (startProgress <= 0.0 && !buffer.isEmpty()) {
            if (timers.getFirst() < spec.ticksPerSlot()) return false;
        }

        buffer.addFirst(stack.copyWithCount(1));
        timers.addFirst(startTimer);
        markDirty();
        return true;
    }

    /** 移除出口处的物品（最早放入的）。 */
    public ItemStack removeLastItem() {
        if (buffer.isEmpty()) return ItemStack.EMPTY;
        timers.removeLast();
        ItemStack removed = buffer.removeLast();
        markDirty();
        return removed;
    }

    /** 吸收掉落物。每次只取 1 个。 */
    public boolean absorbItemEntity(ItemEntity entity) {
        if (entity == null || !entity.isAlive()) return false;
        if (entity.tickCount < 5) return false;
        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) return false;
        if (!addItem(stack.copyWithCount(1))) return false;

        stack.shrink(1);
        if (stack.isEmpty()) entity.discard();
        else entity.setItem(stack);
        return true;
    }

    /** @return 缓冲队列的不可修改视图（索引 0 = 入口，末尾 = 出口） */
    public List<ItemStack> getContents() {
        return Collections.unmodifiableList(buffer);
    }

    /** @return 指定索引物品的计时器值 */
    public int getTimer(int index) {
        return timers.get(index);
    }

    /** @return 入口物品的计时器（最新放入的） */
    public int getFirstTimer() {
        return timers.isEmpty() ? 0 : timers.get(0);
    }

    /** 客户端 tick：仅推进计时器以实现平滑视觉动画，不做物品传输。 */
    public void tickClient() {
        for (int i = 0; i < timers.size(); i++) {
            timers.set(i, timers.get(i) + 1);
        }
    }

    /** 传输结果：SUCCESS=已传输，RETRY=目标存在但已满需等待，EJECT=目标消失需弹射 */
    private enum TransferResult { SUCCESS, RETRY, EJECT }

    /** 服务端 tick：推进计时器，输出到期的物品。 */
    public void tick() {
        if (buffer.isEmpty()) return;
        Level level = blockEntity.getLevel();
        if (level == null || level.isClientSide()) return;

        // 所有计时器 +1
        for (int i = 0; i < timers.size(); i++) {
            timers.set(i, timers.get(i) + 1);
        }

        // 从出口开始检查：物品走完全程后才尝试输出
        int totalTicks = spec.slotCount() * spec.ticksPerSlot();
        while (!buffer.isEmpty()) {
            int lastIdx = buffer.size() - 1;
            if (timers.get(lastIdx) < totalTicks) break;

            BlockPos pos = blockEntity.getBlockPos();
            BlockState state = blockEntity.getBlockState();
            Direction facing = state.getValue(ConveyorBlock.FACING);
            ConveyorType type = state.getValue(ConveyorBlock.TYPE);

            ItemStack oldest = buffer.getLast();
            BlockPos targetPos = switch (type) {
                case UP -> pos.relative(facing).above();
                default -> pos.relative(facing);
            };

            TransferResult invResult = tryTransferToInventory(level, targetPos, facing, oldest);
            TransferResult convResult = tryTransferToConveyor(level, targetPos, facing, oldest);

            // 综合结果：任一成功 = SUCCESS；两者都消失 = EJECT；否则 RETRY（等待）
            TransferResult result;
            if (invResult == TransferResult.SUCCESS || convResult == TransferResult.SUCCESS) {
                result = TransferResult.SUCCESS;
            } else if (invResult == TransferResult.EJECT && convResult == TransferResult.EJECT) {
                // 前方既没有容器也没有传送带 → 弹射
                result = TransferResult.EJECT;
            } else {
                // 有目标但满了 → 等待
                result = TransferResult.RETRY;
            }

            switch (result) {
                case SUCCESS -> {
                    buffer.removeLast();
                    timers.removeLast();
                    markDirty();
                }
                case RETRY -> {
                    // 目标存在但满了 → 等待，不弹射。物品停在出口，下个 tick 重试
                    return; // 跳出 while，不再检查后续物品
                }
                case EJECT -> {
                    ejectAsItemEntity(level, pos, facing, type, oldest);
                    buffer.removeLast();
                    timers.removeLast();
                    markDirty();
                }
            }
        }
    }

    private TransferResult tryTransferToInventory(Level level, BlockPos targetPos, Direction facing, ItemStack stack) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());
        if (handler == null) return TransferResult.EJECT;
        boolean success = insertItem(handler, stack).isEmpty();
        return success ? TransferResult.SUCCESS : TransferResult.RETRY;
    }

    private TransferResult tryTransferToConveyor(Level level, BlockPos targetPos, Direction facing, ItemStack stack) {
        BlockState targetState = level.getBlockState(targetPos);
        if (!(targetState.getBlock() instanceof ConveyorBlock)) return TransferResult.EJECT;
        BlockEntity be = level.getBlockEntity(targetPos);
        if (!(be instanceof ConveyorBlockEntity targetConveyor)) return TransferResult.EJECT;

        Direction targetFacing = targetState.getValue(ConveyorBlock.FACING);
        double startProgress = (facing == targetFacing) ? 0.0 : 0.5;
        boolean success = targetConveyor.addTransportedItem(stack, startProgress);
        return success ? TransferResult.SUCCESS : TransferResult.RETRY;
    }

    private void ejectAsItemEntity(Level level, BlockPos pos, Direction facing, ConveyorType type, ItemStack stack) {
        double x = pos.getX() + 0.5 + facing.getStepX() * 0.6;
        double z = pos.getZ() + 0.5 + facing.getStepZ() * 0.6;
        double y = pos.getY() + 0.3;
        if (type == ConveyorType.UP) y += 1.0;

        ItemEntity dropped = new ItemEntity(level, x, y, z, stack.copy());
        dropped.setDeltaMovement(facing.getStepX() * 0.15, 0.0, facing.getStepZ() * 0.15);
        level.addFreshEntity(dropped);
    }

    private static ItemStack insertItem(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < handler.getSlots(); i++) {
            remaining = handler.insertItem(i, remaining, false);
            if (remaining.isEmpty()) return ItemStack.EMPTY;
        }
        return remaining;
    }

    private void markDirty() {
        blockEntity.setChanged();
        blockEntity.syncToClient();
    }

    // ========== NBT ==========

    private static final String TAG_ITEMS = "Items";
    private static final String TAG_TIMERS = "Timers";

    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (ItemStack s : buffer) list.add(s.save(registries));
        tag.put(TAG_ITEMS, list);
        tag.putIntArray(TAG_TIMERS, timers.stream().mapToInt(i -> i).toArray());
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        buffer.clear();
        timers.clear();
        ListTag list = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack.parse(registries, list.getCompound(i)).ifPresent(s -> {
                if (!s.isEmpty()) buffer.add(s);
            });
        }
        for (int t : tag.getIntArray(TAG_TIMERS)) timers.add(t);
        while (timers.size() < buffer.size()) timers.add(0);
        while (timers.size() > buffer.size()) timers.removeLast();
    }
}