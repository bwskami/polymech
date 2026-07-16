package com.mss.polymech.block.entity;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import com.mss.polymech.client.model.conveyor.BakedConveyorModel;
import com.mss.polymech.entity.ConveyorItemEntity;
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
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 传送带方块实体。
 * <p>
 * 每 tick 驱动传送带上的 {@link ConveyorItemEntity} 前进。
 * 物品沿传送带表面移动，到达终点时根据传送带类型和方向自动传递到下一个传送带，
 * 或吐回为原版掉落物。
 * </p>
 * <p>
 * 物理布局（传送带默认面朝 NORTH，即负Z方向）：
 * <pre>
 * HORIZONTAL: 平坦
 * UP:         y+1 处的前方有水平传送带，同层前方是固体支撑
 * DOWN:       y 处的前方通向下一层，后方上方(y+1)有水平传送带
 * </pre>
 * </p>
 */
public class ConveyorBlockEntity extends BlockEntity {

    /** 每次 tick 的进度增量，与 16 帧传送带动画同步 */
    private static final float PROGRESS_PER_TICK = 1.0F / 16.0F;

    /** 扫描原版掉落物的范围 */
    private static final double PICKUP_RADIUS = 0.35;

    /** 本传送带管理的物品 UUID 列表 */
    private final List<UUID> managedItems = new ArrayList<>();

    public ConveyorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CONVEYOR.get(), pos, state);
    }

    // ========== ModelData ==========

    public ModelData getModelData() {
        ConveyorType type = getBlockState().getValue(ConveyorBlock.TYPE);
        return ModelData.builder()
                .with(BakedConveyorModel.CONVEYOR_TYPE, type)
                .build();
    }

    // ========== Tick ==========

    public static void tick(Level level, BlockPos pos, BlockState state, ConveyorBlockEntity be) {
        if (level.isClientSide()) return;

        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        // 1. 扫描原版掉落物 → 传送带物品
        be.tryPickupItems(level, pos, type);

        // 2. 驱动已有物品前进
        be.driveItems(level, pos, state, facing, type);

        // 3. 清理无效记录
        if (level instanceof ServerLevel serverLevel) {
            be.managedItems.removeIf(uuid -> {
                var entity = serverLevel.getEntity(uuid);
                return entity == null || !entity.isAlive();
            });
        }
    }

    // ========== 拾取 ==========

    private void tryPickupItems(Level level, BlockPos pos, ConveyorType type) {
        // 拾取范围覆盖整个方块高度（上下坡表面可从 y+0.25 到 y+1.25）
        AABB pickupBox = new AABB(
                pos.getX() + 0.5 - PICKUP_RADIUS, pos.getY() + 0.02, pos.getZ() + 0.5 - PICKUP_RADIUS,
                pos.getX() + 0.5 + PICKUP_RADIUS, pos.getY() + 1.3, pos.getZ() + 0.5 + PICKUP_RADIUS
        );

        // 检查传送带上已有物品的最近进度
        float minProgress = getMinProgressOnBelt(level, pos, type);

        // 只有起点附近空闲才放入
        if (minProgress < 0.4F) return;

        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, pickupBox,
                item -> item.isAlive() && !item.getItem().isEmpty()
        );

        for (ItemEntity item : items) {
            ItemStack stack = item.getItem();
            if (stack.isEmpty()) continue;

            ConveyorItemEntity conveyorItem = ConveyorItemEntity.create(level, pos, stack.split(1));
            if (stack.getCount() <= 0) {
                item.discard();
            }

            level.addFreshEntity(conveyorItem);
            managedItems.add(conveyorItem.getUUID());
            break; // 每 tick 只放一个
        }
    }

    private float getMinProgressOnBelt(Level level, BlockPos pos, ConveyorType type) {
        AABB scanBox = buildItemScanBox(pos);
        List<ConveyorItemEntity> items = level.getEntitiesOfClass(
                ConveyorItemEntity.class, scanBox,
                item -> item.isAlive() && item.getConveyorPos().equals(pos)
        );
        float min = 1.0F;
        for (ConveyorItemEntity item : items) {
            min = Math.min(min, item.getProgress());
        }
        return min;
    }

    // ========== 驱动 ==========

    /**
     * 驱动传送带上的物品前进。
     * <p>
     * 物品到达终点后，根据所在传送带的类型寻找下一个传送带：
     * <ul>
     *   <li><b>HORIZONTAL</b> → 前方同层 {@code pos.relative(facing)}</li>
     *   <li><b>UP</b> → 前方上层 {@code pos.relative(facing).above()}</li>
     *   <li><b>DOWN</b> → 前方同层 或 前方下层 {@code pos.relative(facing).below()}</li>
     * </ul>
     * </p>
     */
    private void driveItems(Level level, BlockPos pos, BlockState state,
                            Direction facing, ConveyorType type) {
        AABB scanBox = buildItemScanBox(pos);
        List<ConveyorItemEntity> items = level.getEntitiesOfClass(
                ConveyorItemEntity.class, scanBox,
                item -> item.isAlive() && item.getConveyorPos().equals(pos)
        );

        for (ConveyorItemEntity item : items) {
            float newProgress = item.getProgress() + PROGRESS_PER_TICK;

            // 确认该物品属于本传送带
            if (!managedItems.contains(item.getUUID())) {
                managedItems.add(item.getUUID());
            }

            if (newProgress >= 1.0F) {
                BlockPos nextPos = findNextConveyor(level, pos, facing, type);

                if (nextPos != null) {
                    BlockState nextState = level.getBlockState(nextPos);
                    Direction nextFacing = nextState.getBlock() instanceof ConveyorBlock
                            ? nextState.getValue(ConveyorBlock.FACING) : null;

                    boolean isSideEntry = nextFacing != null && nextFacing != facing;
                    float startProgress = isSideEntry ? 0.5F : 0.0F;

                    if (isPositionOccupied(level, nextPos, startProgress)) {
                        // 目标被占用，固定在终点前 1 像素处，不震荡
                        item.setProgress(0.99F);
                        continue;
                    }

                    item.setConveyorPos(nextPos);
                    item.setProgress(startProgress);
                } else {
                    item.setProgress(1.0F);
                    item.ejectAsItemEntity();
                    managedItems.remove(item.getUUID());
                }
            } else {
                // 检查同传送带前方是否有物品阻塞，保持 0.4 间距排队
                float clamped = getClampedProgress(level, pos, item, newProgress);
                item.setProgress(clamped);
            }
        }
    }

    /**
     * 根据当前传送带的类型和朝向，查找下一个合适的传送带位置。
     *
     * @return 下一个传送带的位置，如果没有则返回 null
     */
    @Nullable
    private static BlockPos findNextConveyor(Level level, BlockPos pos, Direction facing, ConveyorType type) {
        return switch (type) {
            case UP -> findNextFromUp(level, pos, facing);
            case DOWN -> findNextFromDown(level, pos, facing);
            default -> findNextFromHorizontal(level, pos, facing);
        };
    }

    private static BlockPos findNextFromUp(Level level, BlockPos pos, Direction facing) {
        // 上坡终点 y = pos.y + 1.0，同层前方是固体支撑（自动判定规则）
        // 唯一有效路径：前方上层（对角线）
        BlockPos upper = pos.relative(facing).above();
        BlockState upperState = level.getBlockState(upper);
        if (upperState.getBlock() instanceof ConveyorBlock
                && upperState.getValue(ConveyorBlock.FACING) == facing) {
            return upper;
        }
        return null;
    }

    private static BlockPos findNextFromDown(Level level, BlockPos pos, Direction facing) {
        BlockPos front = pos.relative(facing);

        // 同层前方：只接受水平/上坡（高度 pos.y+0.25 ≈ pos.y+0.25 匹配）
        BlockState frontState = level.getBlockState(front);
        if (frontState.getBlock() instanceof ConveyorBlock
                && frontState.getValue(ConveyorBlock.FACING) == facing
                && frontState.getValue(ConveyorBlock.TYPE) != ConveyorType.DOWN) {
            return front;
        }

        // 前方下层：对角线下行
        BlockPos lower = front.below();
        BlockState lowerState = level.getBlockState(lower);
        if (lowerState.getBlock() instanceof ConveyorBlock
                && lowerState.getValue(ConveyorBlock.FACING) == facing) {
            return lower;
        }

        return null;
    }

    private static BlockPos findNextFromHorizontal(Level level, BlockPos pos, Direction facing) {
        BlockPos front = pos.relative(facing);

        // 1. 同层前方同朝向（水平链，或转上坡/下坡）
        BlockState frontState = level.getBlockState(front);
        if (frontState.getBlock() instanceof ConveyorBlock
                && frontState.getValue(ConveyorBlock.FACING) == facing) {
            return front;
        }

        // 2. 前方下层同朝向（水平→下坡）
        BlockPos frontBelow = front.below();
        BlockState frontBelowState = level.getBlockState(frontBelow);
        if (frontBelowState.getBlock() instanceof ConveyorBlock
                && frontBelowState.getValue(ConveyorBlock.FACING) == facing) {
            return frontBelow;
        }

        // 3. 前方任意传送带（侧向馈入 - 左右侧传送带输送过来）
        if (frontState.getBlock() instanceof ConveyorBlock) {
            return front;
        }

        return null;
    }

    /**
     * 检查目标传送带入口区域是否已被占用。
     * 直连（起点进入）：检查 0.0~0.3 是否有物品
     * 侧入（中间进入）：检查 0.0~0.6 是否有物品（覆盖从起点到入口的范围）
     * 被占用则当前物品必须等待，避免插队。
     */
    private static boolean isPositionOccupied(Level level, BlockPos pos, float startProgress) {
        AABB scanBox = buildItemScanBox(pos);
        float checkMax = startProgress >= 0.5F ? 0.6F : 0.3F;

        List<ConveyorItemEntity> items = level.getEntitiesOfClass(
                ConveyorItemEntity.class, scanBox,
                item -> item.isAlive()
                        && item.getConveyorPos().equals(pos)
                        && item.getProgress() <= checkMax
        );
        return !items.isEmpty();
    }

    /**
     * 限制物品前进，保持与前方物品 0.4 的间距。
     */
    private static float getClampedProgress(Level level, BlockPos pos, ConveyorItemEntity current, float newProgress) {
        AABB scanBox = buildItemScanBox(pos);
        float nearestAhead = 1.5F;
        List<ConveyorItemEntity> items = level.getEntitiesOfClass(
                ConveyorItemEntity.class, scanBox,
                item -> item.isAlive()
                        && item != current
                        && item.getConveyorPos().equals(pos)
        );
        for (ConveyorItemEntity item : items) {
            float p = item.getProgress();
            if (p > current.getProgress() && p < nearestAhead) {
                nearestAhead = p;
            }
        }
        if (nearestAhead > 1.0F) return newProgress;

        float maxAllowed = nearestAhead - 0.4F;
        if (newProgress > maxAllowed) {
            return Math.max(maxAllowed, current.getProgress());
        }
        return newProgress;
    }

    // ========== 扫描范围 ==========

    private static AABB buildItemScanBox(BlockPos pos) {
        // 完整覆盖整个方块 + 上下各延伸一小段
        // 下坡对角线链：下层下坡起点 y = (pos.y-1) + 1.0 = pos.y
        // 上坡对角线链：上层上坡起点 y = (pos.y+1) + 0.25 = pos.y + 1.25
        // 需要覆盖从 pos.y 到 pos.y + 1.25
        return new AABB(
                pos.getX() - 0.1, pos.getY() - 0.1, pos.getZ() - 0.1,
                pos.getX() + 1.1, pos.getY() + 1.5, pos.getZ() + 1.1
        );
    }

    // ========== 网络同步 ==========

    public void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // ========== NBT ==========

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag uuids = new ListTag();
        for (UUID uuid : managedItems) {
            CompoundTag uuidTag = new CompoundTag();
            uuidTag.putUUID("UUID", uuid);
            uuids.add(uuidTag);
        }
        tag.put("ManagedItems", uuids);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        managedItems.clear();
        if (tag.contains("ManagedItems", Tag.TAG_LIST)) {
            ListTag uuids = tag.getList("ManagedItems", Tag.TAG_COMPOUND);
            for (int i = 0; i < uuids.size(); i++) {
                CompoundTag uuidTag = uuids.getCompound(i);
                managedItems.add(uuidTag.getUUID("UUID"));
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
        requestModelDataUpdate();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}