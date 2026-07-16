package com.mss.polymech.entity;

import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ConveyorType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

/**
 * 传送带物品实体。
 * <p>
 * 该实体看起来像掉落物，但不受原版掉落物的物理、消失、碰撞机制影响。
 * 位置完全由传送带 BlockEntity 控制，沿传送带表面移动。
 * </p>
 */
public class ConveyorItemEntity extends Entity {

    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(ConveyorItemEntity.class, EntityDataSerializers.ITEM_STACK);

    private static final EntityDataAccessor<BlockPos> DATA_CONVEYOR_POS =
            SynchedEntityData.defineId(ConveyorItemEntity.class, EntityDataSerializers.BLOCK_POS);

    private static final EntityDataAccessor<Float> DATA_PROGRESS =
            SynchedEntityData.defineId(ConveyorItemEntity.class, EntityDataSerializers.FLOAT);

    /** 相对传送带表面的 Y 偏移（用于微调显示高度） */
    private float heightOffset = 0.0F;

    public ConveyorItemEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.blocksBuilding = false;
    }

    private ConveyorItemEntity(Level level, BlockPos conveyorPos, ItemStack stack) {
        this(ModEntities.CONVEYOR_ITEM.get(), level);
        this.setConveyorPos(conveyorPos);
        this.setProgress(0.0F);
        this.setItem(stack);
        updatePosition();
    }

    // ========== 工厂方法 ==========

    public static ConveyorItemEntity create(Level level, BlockPos conveyorPos, ItemStack stack) {
        return new ConveyorItemEntity(level, conveyorPos, stack);
    }

    // ========== 数据同步 ==========

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_ITEM, ItemStack.EMPTY);
        builder.define(DATA_CONVEYOR_POS, BlockPos.ZERO);
        builder.define(DATA_PROGRESS, 0.0F);
    }

    public void setItem(ItemStack stack) {
        getEntityData().set(DATA_ITEM, stack.copy());
    }

    public ItemStack getItem() {
        return getEntityData().get(DATA_ITEM);
    }

    // ========== 右键交互：玩家直接右键物品实体即可拾取 ==========

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide()) return InteractionResult.SUCCESS;

        ItemStack stack = getItem();
        if (!stack.isEmpty()) {
            player.getInventory().placeItemBackInInventory(stack.copy());
            this.discard();
        }
        return InteractionResult.SUCCESS;
    }

    // ========== 传送带控制接口 ==========

    public BlockPos getConveyorPos() {
        return getEntityData().get(DATA_CONVEYOR_POS);
    }

    public void setConveyorPos(BlockPos pos) {
        getEntityData().set(DATA_CONVEYOR_POS, pos);
    }

    public float getProgress() {
        return getEntityData().get(DATA_PROGRESS);
    }

    /**
     * 设置进度并更新世界坐标位置。
     */
    public void setProgress(float progress) {
        progress = Math.max(0.0F, Math.min(1.0F, progress));
        getEntityData().set(DATA_PROGRESS, progress);
        updatePosition();
    }

    /**
     * 根据当前所在传送带的状态，计算并更新世界坐标。
     */
    public void updatePosition() {
        if (level() == null) return;

        BlockPos conveyorPos = getConveyorPos();
        float progress = getProgress();

        BlockState state = level().getBlockState(conveyorPos);
        if (!(state.getBlock() instanceof ConveyorBlock)) return;

        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);

        double x = conveyorPos.getX() + 0.5 + facing.getStepX() * (progress - 0.5);
        double z = conveyorPos.getZ() + 0.5 + facing.getStepZ() * (progress - 0.5);
        // 表面高度计算（物品直接放在表面上）：
        // HORIZONTAL: 始终在 4/16
        // UP:        从 4/16 上升到 20/16（= 1格 + 4/16），行程 1 整格
        // DOWN:      从 20/16 下降到 4/16，行程 1 整格
        double y = switch (type) {
            case UP -> conveyorPos.getY() + (4.0 + 16.0 * progress) / 16.0;
            case DOWN -> conveyorPos.getY() + (20.0 - 16.0 * progress) / 16.0;
            default -> conveyorPos.getY() + 4.0 / 16.0;
        };

        this.setPos(x, y, z);
    }

    /**
     * 将此实体转换为原版掉落物并移除自身。
     */
    public void ejectAsItemEntity() {
        if (level().isClientSide()) return;

        BlockPos conveyorPos = getConveyorPos();
        BlockState state = level().getBlockState(conveyorPos);
        Direction facing = state.getBlock() instanceof ConveyorBlock
                ? state.getValue(ConveyorBlock.FACING)
                : Direction.NORTH;

        double ejectX = conveyorPos.getX() + 0.5 + facing.getStepX() * 0.6;
        double ejectZ = conveyorPos.getZ() + 0.5 + facing.getStepZ() * 0.6;
        double ejectY = conveyorPos.getY() + 4.0 / 16.0 + 0.3;

        var itemEntity = new net.minecraft.world.entity.item.ItemEntity(level(), ejectX, ejectY, ejectZ, getItem());
        itemEntity.setDeltaMovement(
                facing.getStepX() * 0.15,
                0.2,
                facing.getStepZ() * 0.15
        );
        itemEntity.setPickUpDelay(10);

        level().addFreshEntity(itemEntity);
        this.discard();
    }

    // ========== 实体行为 ==========

    @Override
    public void tick() {
        if (this.tickCount > 0 && this.tickCount % 20 == 0) {
            updatePosition();
        }
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    // ========== 网络同步 ==========

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == DATA_PROGRESS || key == DATA_CONVEYOR_POS) {
            updatePosition();
        }
    }

    // ========== NBT ==========

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("ConveyorX")) {
            setConveyorPos(new BlockPos(
                    tag.getInt("ConveyorX"),
                    tag.getInt("ConveyorY"),
                    tag.getInt("ConveyorZ")
            ));
        }
        this.setProgress(tag.getFloat("Progress"));
        this.heightOffset = tag.getFloat("HeightOffset");
        if (tag.contains("Item")) {
            CompoundTag itemTag = tag.getCompound("Item");
            setItem(ItemStack.parseOptional(this.registryAccess(), itemTag));
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        BlockPos conveyorPos = getConveyorPos();
        tag.putInt("ConveyorX", conveyorPos.getX());
        tag.putInt("ConveyorY", conveyorPos.getY());
        tag.putInt("ConveyorZ", conveyorPos.getZ());
        tag.putFloat("Progress", getProgress());
        tag.putFloat("HeightOffset", heightOffset);
        tag.put("Item", getItem().saveOptional(this.registryAccess()));
    }

    // ========== Entity 基础 ==========

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 256.0;
    }

    @Override
    public boolean shouldShowName() {
        return false;
    }

    @Override
    protected @NotNull MovementEmission getMovementEmission() {
        return MovementEmission.NONE;
    }
}