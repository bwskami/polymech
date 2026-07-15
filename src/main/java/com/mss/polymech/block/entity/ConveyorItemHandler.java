package com.mss.polymech.block.entity;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 传送带物品物流处理器。
 * <p>
 * 单一职责：管理传送带上的物品队列，包括：
 * <ul>
 *   <li>物品推进与防碰撞</li>
 *   <li>物品传输到相邻容器/传送带</li>
 *   <li>掉落物吸收</li>
 *   <li>NBT序列化</li>
 * </ul>
 * </p>
 */
public class ConveyorItemHandler {
    private static final int MAX_ITEMS = 8;
    private static final double SPEED = 0.02;
    private static final double MIN_GAP = 0.15;

    private final List<TransportedItem> items = new ArrayList<>();
    private final ConveyorBlockEntity blockEntity;

    public ConveyorItemHandler(ConveyorBlockEntity blockEntity) {
        this.blockEntity = blockEntity;
    }

    // ========== 外部接口 ==========

    /**
     * 添加物品到传送带队列末尾。
     *
     * @param stack 要添加的物品堆栈
     * @return 是否成功添加
     */
    public boolean addItem(ItemStack stack) {
        return addItem(stack, 0.0);
    }

    /**
     * 添加物品到传送带队列末尾，指定起始进度。
     *
     * @param stack         要添加的物品堆栈
     * @param startProgress 起始进度 (0.0 ~ 1.0)
     * @return 是否成功添加
     */
    public boolean addItem(ItemStack stack, double startProgress) {
        if (items.size() >= MAX_ITEMS) return false;
        if (stack.isEmpty()) return false;
        items.add(new TransportedItem(stack, startProgress));
        markDirty();
        return true;
    }

    /**
     * 移除并返回队列末尾的物品。
     */
    public ItemStack removeLastItem() {
        if (items.isEmpty()) return ItemStack.EMPTY;
        TransportedItem removed = items.remove(items.size() - 1);
        markDirty();
        return removed.getStack();
    }

    /**
     * 吸收传送带上的掉落物实体。
     * <p>
     * 由 {@link ConveyorBlock#stepOn} 触发，将落在传送带上的物品实体吸入队列。
     * 刚被传送带弹出的物品（年龄小于5 tick）会有免疫期，防止被立即重新吸入形成死循环。
     * </p>
     *
     * @param itemEntity 要吸收的掉落物实体
     * @return 是否成功吸收
     */
    public boolean absorbItemEntity(ItemEntity itemEntity) {
        if (itemEntity == null || !itemEntity.isAlive()) return false;

        // 免疫期检查：刚被传送带弹出的物品不吸收，防止循环
        if (itemEntity.tickCount < 5) return false;

        ItemStack stack = itemEntity.getItem();
        if (stack.isEmpty()) return false;

        if (addItem(stack)) {
            itemEntity.discard();
            return true;
        }
        return false;
    }

    /**
     * @return 当前所有运输物品的不可修改视图
     */
    public List<TransportedItem> getItems() {
        return List.copyOf(items);
    }

    // ========== Tick 核心逻辑 ==========

    /**
     * 每 Tick 调用，执行物品推进、防碰撞、传输。
     */
    public void tick() {
        if (items.isEmpty()) return;

        Level level = blockEntity.getLevel();
        if (level == null) return;

        BlockPos pos = blockEntity.getBlockPos();
        BlockState state = blockEntity.getBlockState();
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        // 1. 推进物品
        advanceItems();

        // 2. 防碰撞（保持物品间距）
        enforceSpacing();

        // 3. 处理到达终点的物品
        transferFinishedItems(level, pos, state, facing, type);
    }

    /**
     * 推进所有物品的进度。
     */
    private void advanceItems() {
        for (TransportedItem item : items) {
            item.setProgress(item.getProgress() + SPEED);
        }
    }

    /**
     * 保持物品之间的最小间距，防止重叠。
     */
    private void enforceSpacing() {
        for (int i = 1; i < items.size(); i++) {
            TransportedItem current = items.get(i);
            TransportedItem previous = items.get(i - 1);
            if (current.getProgress() > previous.getProgress() - MIN_GAP) {
                current.setProgress(previous.getProgress() - MIN_GAP);
            }
        }
    }

    /**
     * 处理所有已到达终点的物品：尝试传输到相邻容器/传送带，失败则弹出掉落物。
     */
    private void transferFinishedItems(Level level, BlockPos pos, BlockState state,
                                       Direction facing, ConveyorType type) {
        Iterator<TransportedItem> iter = items.iterator();
        while (iter.hasNext()) {
            TransportedItem item = iter.next();
            if (!item.isFinished()) continue;

            if (level.isClientSide()) {
                // 客户端：将进度锁定在末端，等待服务端同步
                item.setProgress(0.999);
                continue;
            }

            // 计算目标位置
            BlockPos targetPos = calculateTargetPos(pos, facing, type);

            boolean transferred = tryTransferToInventory(level, targetPos, facing, item)
                    || tryTransferToConveyor(level, targetPos, facing, item)
                    || tryTransferToConveyorBelow(level, targetPos, facing, item);

            if (transferred) {
                iter.remove();
                markDirty();
            } else {
                // 所有传输方式都失败，弹出掉落物
                ejectAsItemEntity(level, pos, facing, type, item);
                iter.remove();
                markDirty();
            }
        }
    }

    /**
     * 根据传送带类型计算物品的目标位置。
     * <p>
     * UP：物品上升到 y+1.25，目标在 pos.relative(facing).above()
     * DOWN：物品下降到 y+0.25，目标在 pos.relative(facing)（和水平相同，下坡仅是块内的视觉变化）
     * HORIZONTAL：物品在 y+0.25，目标在 pos.relative(facing)
     * </p>
     */
    private BlockPos calculateTargetPos(BlockPos pos, Direction facing, ConveyorType type) {
        return switch (type) {
            case UP -> pos.relative(facing).above();
            default -> pos.relative(facing);
        };
    }

    /**
     * 尝试将物品注入目标位置的物品容器（如箱子、熔炉等）。
     */
    private boolean tryTransferToInventory(Level level, BlockPos targetPos, Direction facing, TransportedItem item) {
        IItemHandler handler = level.getCapability(
                Capabilities.ItemHandler.BLOCK, targetPos, facing.getOpposite());
        if (handler == null) return false;

        ItemStack remainder = insertItem(handler, item.internalStack());
        if (remainder.isEmpty()) return true;

        // 部分传输：更新剩余物品
        if (remainder.getCount() < item.internalStack().getCount()) {
            item.internalStack().setCount(remainder.getCount());
            return false;
        }
        return false;
    }

    /**
     * 尝试将物品传输到目标位置的传送带。
     */
    private boolean tryTransferToConveyor(Level level, BlockPos targetPos, Direction facing, TransportedItem item) {
        BlockState targetState = level.getBlockState(targetPos);
        if (!(targetState.getBlock() instanceof ConveyorBlock)) return false;

        BlockEntity be = level.getBlockEntity(targetPos);
        if (!(be instanceof ConveyorBlockEntity targetConveyor)) return false;

        Direction targetFacing = targetState.getValue(ConveyorBlock.FACING);
        double startProgress = (facing == targetFacing) ? 0.0 : 0.5;
        return targetConveyor.addTransportedItem(item.internalStack(), startProgress);
    }

    /**
     * 尝试将物品传输到目标位置下方的传送带（下坡衔接）。
     */
    private boolean tryTransferToConveyorBelow(Level level, BlockPos targetPos, Direction facing, TransportedItem item) {
        BlockPos belowPos = targetPos.below();
        BlockState belowState = level.getBlockState(belowPos);
        if (!(belowState.getBlock() instanceof ConveyorBlock)) return false;

        BlockEntity be = level.getBlockEntity(belowPos);
        if (!(be instanceof ConveyorBlockEntity belowConveyor)) return false;

        Direction belowFacing = belowState.getValue(ConveyorBlock.FACING);
        double startProgress = (facing == belowFacing) ? 0.0 : 0.5;
        return belowConveyor.addTransportedItem(item.internalStack(), startProgress);
    }

    /**
     * 将物品作为掉落物实体弹出到世界中。
     */
    private void ejectAsItemEntity(Level level, BlockPos pos, Direction facing, ConveyorType type, TransportedItem item) {
        Vec3 dropPos = ConveyorBlockEntity.getWorldPosition(pos, facing, type, 1.0);
        ItemEntity dropped = new ItemEntity(level,
                dropPos.x, dropPos.y, dropPos.z,
                item.getStack());
        dropped.setDeltaMovement(
                facing.getStepX() * 0.15,
                -0.1,
                facing.getStepZ() * 0.15);
        level.addFreshEntity(dropped);
    }

    /**
     * 将物品堆栈注入到物品处理器中。
     */
    private static ItemStack insertItem(IItemHandler handler, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < handler.getSlots(); i++) {
            remaining = handler.insertItem(i, remaining, false);
            if (remaining.isEmpty()) return ItemStack.EMPTY;
        }
        return remaining;
    }

    // ========== 数据同步 ==========

    private void markDirty() {
        blockEntity.setChanged();
        blockEntity.syncToClient();
    }

    // ========== NBT 序列化 ==========

    private static final String TAG_ITEMS = "TransportedItems";
    private static final String TAG_ITEM = "Item";
    private static final String TAG_PROGRESS = "Progress";

    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (TransportedItem item : items) {
            CompoundTag itemTag = new CompoundTag();
            itemTag.put(TAG_ITEM, item.internalStack().save(registries));
            itemTag.putDouble(TAG_PROGRESS, item.getProgress());
            list.add(itemTag);
        }
        tag.put(TAG_ITEMS, list);
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        items.clear();
        ListTag list = tag.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag itemTag = list.getCompound(i);
            ItemStack stack = ItemStack.parse(registries, itemTag.getCompound(TAG_ITEM)).orElse(ItemStack.EMPTY);
            double progress = itemTag.getDouble(TAG_PROGRESS);
            if (!stack.isEmpty()) {
                items.add(new TransportedItem(stack, progress));
            }
        }
    }
}