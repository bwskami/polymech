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
 * 位置完全在 ConveyorBlockEntity 的 tick 中通过 setProgress 驱动。
 * 服务端调用 setPos 更新位置并同步给客户端；
 * 客户端渲染器根据 conveyorPos + progress 直接计算精确渲染位置。
 * </p>
 */
public class ConveyorItemEntity extends Entity {

    private static final EntityDataAccessor<ItemStack> DATA_ITEM =
            SynchedEntityData.defineId(ConveyorItemEntity.class, EntityDataSerializers.ITEM_STACK);

    private static final EntityDataAccessor<BlockPos> DATA_CONVEYOR_POS =
            SynchedEntityData.defineId(ConveyorItemEntity.class, EntityDataSerializers.BLOCK_POS);

    private static final EntityDataAccessor<Float> DATA_PROGRESS =
            SynchedEntityData.defineId(ConveyorItemEntity.class, EntityDataSerializers.FLOAT);

    /** 用于渲染器计算的目标位置缓存 */
    public double renderX, renderY, renderZ;

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

    // ========== 右键交互 ==========

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

    public void setProgress(float progress) {
        progress = Math.max(0.0F, Math.min(1.0F, progress));
        getEntityData().set(DATA_PROGRESS, progress);
        updatePosition();
    }

    /** 计算目标位置（供服务端设位置和渲染器使用） */
    public double[] computePosition() {
        BlockPos conveyorPos = getConveyorPos();
        float progress = getProgress();
        BlockState state = level().getBlockState(conveyorPos);
        if (!(state.getBlock() instanceof ConveyorBlock)) {
            return new double[]{getX(), getY(), getZ()};
        }
        Direction facing = state.getValue(ConveyorBlock.FACING);
        ConveyorType type = state.getValue(ConveyorBlock.TYPE);
        double x = conveyorPos.getX() + 0.5 + facing.getStepX() * (progress - 0.5);
        double z = conveyorPos.getZ() + 0.5 + facing.getStepZ() * (progress - 0.5);
        double y = switch (type) {
            case UP -> conveyorPos.getY() + (4.0 + 16.0 * progress) / 16.0;
            case DOWN -> conveyorPos.getY() + (20.0 - 16.0 * progress) / 16.0;
            default -> conveyorPos.getY() + 4.0 / 16.0;
        };
        return new double[]{x, y, z};
    }

    /**
     * 更新位置。
     * 客户端：setPosRaw 不重置 xOld，super.tick() 中的 baseTick() 已在每次 tick 开头保存。
     * 服务端：setPos 发送位置数据包给客户端同步。
     */
    public void updatePosition() {
        if (level() == null) return;
        double[] pos = computePosition();
        this.renderX = pos[0];
        this.renderY = pos[1];
        this.renderZ = pos[2];

        if (level().isClientSide()) {
            // setPosRaw 保留 xOld，baseTick 已在 tick 开头保存 xOld
            this.setPosRaw(pos[0], pos[1], pos[2]);
            this.setBoundingBox(this.makeBoundingBox());
        } else {
            this.setPos(pos[0], pos[1], pos[2]);
        }
    }

    // ========== 吐出 ==========

    public void ejectAsItemEntity() {
        if (level().isClientSide()) return;
        BlockPos conveyorPos = getConveyorPos();
        BlockState state = level().getBlockState(conveyorPos);
        Direction facing = state.getBlock() instanceof ConveyorBlock
                ? state.getValue(ConveyorBlock.FACING) : Direction.NORTH;

        double ejectX = conveyorPos.getX() + 0.5 + facing.getStepX() * 0.6;
        double ejectZ = conveyorPos.getZ() + 0.5 + facing.getStepZ() * 0.6;
        double ejectY = conveyorPos.getY() + 4.0 / 16.0 + 0.3;

        var itemEntity = new net.minecraft.world.entity.item.ItemEntity(level(), ejectX, ejectY, ejectZ, getItem());
        itemEntity.setDeltaMovement(facing.getStepX() * 0.15, 0.2, facing.getStepZ() * 0.15);
        itemEntity.setPickUpDelay(10);
        level().addFreshEntity(itemEntity);
        this.discard();
    }

    // ========== 实体行为 ==========

    @Override
    public void tick() {
        super.tick();
        // 客户端每 tick 更新位置，super.tick() 中的 baseTick() 已保存 xOld/yOld/zOld
        if (level().isClientSide() && this.tickCount > 0) {
            updatePosition();
        }
    }

    @Override
    public boolean isPickable() { return true; }

    @Override
    public boolean canBeCollidedWith() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    // ========== 网络同步 ==========

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        // 不设位置，只由 tick() 驱动位置更新，防止两路冲突抽动
    }

    // ========== NBT ==========

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("ConveyorX")) {
            setConveyorPos(new BlockPos(tag.getInt("ConveyorX"), tag.getInt("ConveyorY"), tag.getInt("ConveyorZ")));
        }
        this.setProgress(tag.getFloat("Progress"));
        this.heightOffset = tag.getFloat("HeightOffset");
        if (tag.contains("Item")) {
            setItem(ItemStack.parseOptional(this.registryAccess(), tag.getCompound("Item")));
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
    public boolean shouldRenderAtSqrDistance(double distance) { return distance < 256.0; }

    @Override
    public boolean shouldShowName() { return false; }

    @Override
    protected @NotNull MovementEmission getMovementEmission() { return MovementEmission.NONE; }
}