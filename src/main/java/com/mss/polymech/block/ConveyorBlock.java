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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class ConveyorBlock extends BaseEntityBlock {
    public static final MapCodec<ConveyorBlock> CODEC = simpleCodec(ConveyorBlock::new);

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<ConveyorType> TYPE = EnumProperty.create("type", ConveyorType.class);

    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 4, 16);

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
        return SHAPE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
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
        if (entity instanceof ItemEntity || entity instanceof LivingEntity) {
            Direction facing = state.getValue(FACING);
            double speed = entity instanceof ItemEntity ? 0.05 : 0.03;
            entity.setDeltaMovement(
                    entity.getDeltaMovement().x + facing.getStepX() * speed,
                    entity.getDeltaMovement().y,
                    entity.getDeltaMovement().z + facing.getStepZ() * speed
            );
        }
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level instanceof ServerLevel) {
            return createTickerHelper(type, ModBlockEntities.CONVEYOR.get(), ConveyorBlockEntity::serverTick);
        }
        return null;
    }

    // ========== 核心更新逻辑 ==========

    /**
     * 计算当前方块应有的类型。
     * 检测自己的“前方上方”（facing 方向的上方一格）。
     * 如果那里是传送带且朝向与自己相同 → UP
     * 否则 → HORIZONTAL （注意：不再处理 DOWN）
     */
    private static ConveyorType calculateNewType(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(FACING);
        BlockPos targetPos = pos.relative(facing).above();  // 前方上方
        BlockState targetState = level.getBlockState(targetPos);

        if (targetState.getBlock() instanceof ConveyorBlock) {
            Direction targetFacing = targetState.getValue(FACING);
            // 只有目标与自身朝向完全相同时，才变为 UP（上坡）
            if (targetFacing == facing) {
                return ConveyorType.UP;
            }
        }
        return ConveyorType.HORIZONTAL;
    }

    private static void refreshSelfState(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) return;
        ConveyorType newType = calculateNewType(level, pos, state);
        if (newType != state.getValue(TYPE)) {
            level.setBlock(pos, state.setValue(TYPE, newType), Block.UPDATE_NEIGHBORS | Block.UPDATE_CLIENTS);
        }
    }

    /**
     * 刷新所有可能受当前方块影响的传送带：
     * 1. 六个方向的邻居（标准邻居）
     * 2. 自己的“后方下方”（因为该位置可能依赖自己作为“前方上方”）
     */
    private static void refreshNeighbors(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) return;
        // 1. 六个方向
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (neighborState.getBlock() instanceof ConveyorBlock) {
                refreshSelfState(level, neighborPos, neighborState);
            }
        }
        // 2. 自己的后方下方
        Direction facing = state.getValue(FACING);
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