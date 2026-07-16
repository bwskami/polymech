package com.mss.polymech.block;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.entity.ConveyorBlockEntity;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.entity.ConveyorItemEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ConveyorBlock extends BaseEntityBlock {
    public static final MapCodec<ConveyorBlock> CODEC = simpleCodec(ConveyorBlock::new);

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<ConveyorType> TYPE = EnumProperty.create("type", ConveyorType.class);

    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

    private static final VoxelShape SLOPE_UP_NORTH = makeSlopeShape();
    private static final VoxelShape SLOPE_UP_SOUTH = rotateSlope180(SLOPE_UP_NORTH);
    private static final VoxelShape SLOPE_UP_EAST  = rotateSlope90(SLOPE_UP_NORTH);
    private static final VoxelShape SLOPE_UP_WEST  = rotateSlope270(SLOPE_UP_NORTH);

    private static final VoxelShape SLOPE_DOWN_NORTH = makeReverseSlopeShape();
    private static final VoxelShape SLOPE_DOWN_SOUTH = rotateSlope180(SLOPE_DOWN_NORTH);
    private static final VoxelShape SLOPE_DOWN_EAST  = rotateSlope90(SLOPE_DOWN_NORTH);
    private static final VoxelShape SLOPE_DOWN_WEST  = rotateSlope270(SLOPE_DOWN_NORTH);

    private static VoxelShape makeSlopeShape() {
        VoxelShape shape = Shapes.empty();
        int steps = 8;
        for (int i = 0; i < steps; i++) {
            double yMax = 16.0 * (i + 1) / steps;
            double zFrom = 16.0 - 16.0 * (i + 1) / steps;
            double zTo = 16.0 - 16.0 * i / steps;
            shape = Shapes.joinUnoptimized(shape, Block.box(0, 0, zFrom, 16, yMax, zTo), BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static VoxelShape makeReverseSlopeShape() {
        VoxelShape shape = Shapes.empty();
        int steps = 8;
        for (int i = 0; i < steps; i++) {
            double yMax = 16.0 * (i + 1) / steps;
            double zFrom = 16.0 * i / steps;
            double zTo = 16.0 * (i + 1) / steps;
            shape = Shapes.joinUnoptimized(shape, Block.box(0, 0, zFrom, 16, yMax, zTo), BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static VoxelShape rotateSlope180(VoxelShape source) {
        VoxelShape shape = Shapes.empty();
        for (var box : source.toAabbs()) {
            shape = Shapes.joinUnoptimized(shape,
                    Block.box(16 - box.maxX * 16, box.minY * 16, 16 - box.maxZ * 16,
                              16 - box.minX * 16, box.maxY * 16, 16 - box.minZ * 16),
                    BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static VoxelShape rotateSlope90(VoxelShape source) {
        VoxelShape shape = Shapes.empty();
        for (var box : source.toAabbs()) {
            shape = Shapes.joinUnoptimized(shape,
                    Block.box(16 - box.maxZ * 16, box.minY * 16, box.minX * 16,
                              16 - box.minZ * 16, box.maxY * 16, box.maxX * 16),
                    BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static VoxelShape rotateSlope270(VoxelShape source) {
        VoxelShape shape = Shapes.empty();
        for (var box : source.toAabbs()) {
            shape = Shapes.joinUnoptimized(shape,
                    Block.box(box.minZ * 16, box.minY * 16, 16 - box.maxX * 16,
                              box.maxZ * 16, box.maxY * 16, 16 - box.minX * 16),
                    BooleanOp.OR);
        }
        return shape.optimize();
    }

    private static VoxelShape getSlopeShape(Direction facing, ConveyorType type) {
        if (type == ConveyorType.DOWN) {
            return switch (facing) {
                case SOUTH -> SLOPE_DOWN_SOUTH;
                case EAST  -> SLOPE_DOWN_EAST;
                case WEST  -> SLOPE_DOWN_WEST;
                default    -> SLOPE_DOWN_NORTH;
            };
        }
        return switch (facing) {
            case SOUTH -> SLOPE_UP_SOUTH;
            case EAST  -> SLOPE_UP_EAST;
            case WEST  -> SLOPE_UP_WEST;
            default    -> SLOPE_UP_NORTH;
        };
    }

    public ConveyorBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(TYPE, ConveyorType.HORIZONTAL));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TYPE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection())
                .setValue(TYPE, ConveyorType.HORIZONTAL);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        ConveyorType type = state.getValue(TYPE);
        return type != ConveyorType.HORIZONTAL ? getSlopeShape(state.getValue(FACING), type) : SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        ConveyorType type = state.getValue(TYPE);
        return type != ConveyorType.HORIZONTAL ? getSlopeShape(state.getValue(FACING), type) : SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ConveyorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.CONVEYOR.get(), ConveyorBlockEntity::tick);
    }

    // ========== 核心更新逻辑 ==========

    private static ConveyorType calculateNewType(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);

        BlockPos front = pos.relative(facing);
        BlockPos frontAbove = front.above();
        BlockState frontAboveState = level.getBlockState(frontAbove);
        if (frontAboveState.getBlock() instanceof ConveyorBlock
                && frontAboveState.getValue(FACING) == facing
                && frontAboveState.getValue(TYPE) != ConveyorType.DOWN
                && isSolidSupport(level, front)) {
            return ConveyorType.UP;
        }

        BlockPos back = pos.relative(facing.getOpposite());
        BlockPos backAbove = back.above();
        BlockState backAboveState = level.getBlockState(backAbove);
        if (backAboveState.getBlock() instanceof ConveyorBlock
                && backAboveState.getValue(FACING) == facing
                && backAboveState.getValue(TYPE) != ConveyorType.UP
                && isSolidSupport(level, back)) {
            return ConveyorType.DOWN;
        }

        return ConveyorType.HORIZONTAL;
    }

    private static boolean isSolidSupport(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !(state.getBlock() instanceof ConveyorBlock)
                && state.isCollisionShapeFullBlock(level, pos);
    }

    private static void refreshSelfState(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) return;
        ConveyorType newType = calculateNewType(level, pos, state);
        if (newType != state.getValue(TYPE)) {
            BlockState newState = state.setValue(TYPE, newType);
            level.setBlock(pos, newState, Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
        }
    }

    private static void refreshNeighbors(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) return;
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof ConveyorBlock) {
                refreshSelfState(level, neighborPos, neighborState);
            }
        }
        Direction facing = state.getValue(FACING);
        BlockPos frontBelow = pos.relative(facing).below();
        BlockState frontBelowState = level.getBlockState(frontBelow);
        if (frontBelowState.getBlock() instanceof ConveyorBlock) {
            refreshSelfState(level, frontBelow, frontBelowState);
        }
        BlockPos backBelow = pos.relative(facing.getOpposite()).below();
        BlockState backBelowState = level.getBlockState(backBelow);
        if (backBelowState.getBlock() instanceof ConveyorBlock) {
            refreshSelfState(level, backBelow, backBelowState);
        }
    }

    // ========== 生命周期 ==========

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos,
                                Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        refreshSelfState(level, pos, state);
        if (level.isClientSide()) {
            level.getModelDataManager().requestRefresh(level.getBlockEntity(pos));
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos,
                        BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        refreshSelfState(level, pos, state);
        refreshNeighbors(level, pos, state);
        if (level.isClientSide()) {
            level.getModelDataManager().requestRefresh(level.getBlockEntity(pos));
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighborPos = pos.relative(dir);
                BlockEntity neighborBE = level.getBlockEntity(neighborPos);
                if (neighborBE != null) {
                    level.getModelDataManager().requestRefresh(neighborBE);
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            // 弹出传送带上的所有物品
            ejectConveyorItems(level, pos);

            super.onRemove(state, level, pos, newState, movedByPiston);
            refreshNeighbors(level, pos, state);
            if (level.isClientSide()) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos neighborPos = pos.relative(dir);
                    BlockEntity neighborBE = level.getBlockEntity(neighborPos);
                    if (neighborBE != null) {
                        level.getModelDataManager().requestRefresh(neighborBE);
                    }
                }
            }
        }
    }

    private static void ejectConveyorItems(Level level, BlockPos pos) {
        if (level.isClientSide()) return;
        AABB scanBox = buildScanBox(pos);
        List<ConveyorItemEntity> items = level.getEntitiesOfClass(
                ConveyorItemEntity.class, scanBox,
                item -> item.isAlive() && item.getConveyorPos().equals(pos)
        );
        for (ConveyorItemEntity item : items) {
            item.ejectAsItemEntity();
        }
    }

    // ========== 右键交互：放入/取出物品 ==========

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
                                               BlockPos pos, Player player, InteractionHand hand,
                                               BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }

        // 空手：取出鼠标瞄准的物品（离命中点最近的）
        if (stack.isEmpty()) {
            return pickupItem(level, pos, player, hitResult);
        }

        // 手持物品：放入传送带
        return insertItem(level, pos, player, stack);
    }

    private static AABB buildScanBox(BlockPos pos) {
        return new AABB(
                pos.getX() - 0.1, pos.getY() + 4.0 / 16.0 - 0.1, pos.getZ() - 0.1,
                pos.getX() + 1.1, pos.getY() + 1.0 + 0.3, pos.getZ() + 1.1
        );
    }

    private static ItemInteractionResult pickupItem(Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        AABB scanBox = buildScanBox(pos);
        List<ConveyorItemEntity> items = level.getEntitiesOfClass(ConveyorItemEntity.class, scanBox,
                item -> item.isAlive() && item.getConveyorPos().equals(pos));

        if (items.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // 优先取瞄准点附近的物品（判定范围 0.8 格，足够宽松）
        double hitX = hitResult.getLocation().x;
        double hitZ = hitResult.getLocation().z;
        ConveyorItemEntity target = null;
        double closestDist = 0.8; // 最大有效距离

        for (ConveyorItemEntity item : items) {
            double dx = item.getX() - hitX;
            double dz = item.getZ() - hitZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < closestDist) {
                closestDist = dist;
                target = item;
            }
        }

        // 瞄准点附近没有物品，退回到取第一个
        if (target == null) {
            target = items.getFirst();
        }

        ItemStack result = target.getItem();
        if (!result.isEmpty()) {
            player.getInventory().placeItemBackInInventory(result.copy());
            target.discard();
        }

        return ItemInteractionResult.SUCCESS;
    }

    /** 禁止放入传送带的物品类型（可扩展） */
    private static final java.util.Set<Class<?>> INSERT_BLACKLIST = java.util.Set.of(
            com.mss.polymech.item.ConveyorItem.class,
            com.mss.polymech.item.WrenchItem.class
    );

    private static ItemInteractionResult insertItem(Level level, BlockPos pos, Player player, ItemStack stack) {
        // 黑名单物品禁止放入传送带
        for (Class<?> clazz : INSERT_BLACKLIST) {
            if (clazz.isInstance(stack.getItem())) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
        }

        AABB scanBox = buildScanBox(pos);
        List<ConveyorItemEntity> items = level.getEntitiesOfClass(ConveyorItemEntity.class, scanBox,
                item -> item.isAlive() && item.getConveyorPos().equals(pos));

        // 起点附近已有物品则不允许放入
        for (ConveyorItemEntity item : items) {
            if (item.getProgress() < 0.4F) {
                return ItemInteractionResult.FAIL;
            }
        }

        ItemStack toInsert = stack.split(1);
        if (!toInsert.isEmpty()) {
            ConveyorItemEntity conveyorItem = ConveyorItemEntity.create(level, pos, toInsert);
            level.addFreshEntity(conveyorItem);
        }

        return ItemInteractionResult.SUCCESS;
    }
}