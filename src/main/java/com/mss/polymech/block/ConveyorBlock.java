package com.mss.polymech.block;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.entity.ConveyorBlockEntity;
import com.mss.polymech.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
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
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

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

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.CONVEYOR.get(), ConveyorBlockEntity::tick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (!(level.getBlockEntity(pos) instanceof ConveyorBlockEntity be)) {
            return InteractionResult.PASS;
        }

        if (player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            ItemStack picked = be.removeLastItem();
            if (!picked.isEmpty()) {
                player.setItemInHand(InteractionHand.MAIN_HAND, picked);
                return InteractionResult.CONSUME;
            }
        }

        ItemStack held = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!held.isEmpty()) {
            if (be.addTransportedItem(held)) {
                if (!player.isCreative()) {
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                }
                return InteractionResult.CONSUME;
            }
        }

        return InteractionResult.PASS;
    }

    // ========== 核心更新逻辑 ==========

    /**
     * 计算当前方块应有的类型。
     * 上坡：检查前方上方（低处的自己看到前上方有传送带 → UP）
     * 下坡：检查后方上方（低处的自己看到后上方有传送带 → DOWN）
     * 始终只修改低处的传送带。
     */
    private static ConveyorType calculateNewType(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);

        BlockPos frontAbove = pos.relative(facing).above();
        BlockState frontAboveState = level.getBlockState(frontAbove);
        if (frontAboveState.getBlock() instanceof ConveyorBlock
                && frontAboveState.getValue(FACING) == facing
                && frontAboveState.getValue(TYPE) != ConveyorType.DOWN) {
            return ConveyorType.UP;
        }

        BlockPos backAbove = pos.relative(facing.getOpposite()).above();
        BlockState backAboveState = level.getBlockState(backAbove);
        if (backAboveState.getBlock() instanceof ConveyorBlock
                && backAboveState.getValue(FACING) == facing
                && backAboveState.getValue(TYPE) == ConveyorType.HORIZONTAL) {
            return ConveyorType.DOWN;
        }

        return ConveyorType.HORIZONTAL;
    }

    private static void refreshSelfState(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) return;
        ConveyorType newType = calculateNewType(level, pos, state);
        if (newType != state.getValue(TYPE)) {
            BlockState newState = state.setValue(TYPE, newType);
            level.setBlock(pos, newState, Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 刷新所有可能受当前方块影响的传送带：
     * 1. 六个方向的邻居
     * 2. 前方下方（该传送带检查前方上方时可能依赖自己 → UP）
     * 3. 后方下方（该传送带检查后方上方时可能依赖自己 → DOWN）
     */
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
        refreshSelfState(level, pos, state);          // 自己更新
        refreshNeighbors(level, pos, state);          // 刷新可能受影响的邻居
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
            super.onRemove(state, level, pos, newState, movedByPiston);
            refreshNeighbors(level, pos, state);      // 移除时刷新周围
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

    @Override
    public BlockState updateShape(BlockState state, Direction facing, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return super.updateShape(state, facing, neighborState, level, pos, neighborPos);
    }
}