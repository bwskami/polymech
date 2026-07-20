package com.mss.polymech.block.large;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public abstract class LargeBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    protected LargeBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        Direction facing = state.getValue(FACING);
        BlockPos[] extensionPositions = getExtensionPositions(pos, facing);

        for (BlockPos extensionPos : extensionPositions) {
            if (!level.getBlockState(extensionPos).isAir()) {
                destroyLargeBlock(level, pos, state);
                return;
            }
        }

        for (BlockPos extensionPos : extensionPositions) {
            level.setBlock(extensionPos, getPlaceholderState(state), Block.UPDATE_ALL_IMMEDIATE);
        }
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos currentPos, BlockPos neighborPos) {
        if (!isValidStructure(level, currentPos, state)) {
            destroyLargeBlock((Level) level, currentPos, state);
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, currentPos, neighborPos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.is(newState.getBlock())) {
            return;
        }

        Direction facing = state.getValue(FACING);
        BlockPos[] extensionPositions = getExtensionPositions(pos, facing);

        for (BlockPos extensionPos : extensionPositions) {
            BlockState extensionState = level.getBlockState(extensionPos);
            if (isPlaceholderBlock(extensionState)) {
                level.removeBlock(extensionPos, false);
            }
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }

    protected abstract BlockPos[] getExtensionPositions(BlockPos centerPos, Direction facing);

    protected abstract BlockState getPlaceholderState(BlockState originalState);

    protected abstract boolean isPlaceholderBlock(BlockState state);

    protected boolean isValidStructure(LevelAccessor level, BlockPos centerPos, BlockState state) {
        Direction facing = state.getValue(FACING);
        BlockPos[] extensionPositions = getExtensionPositions(centerPos, facing);

        if (level.getBlockState(centerPos).getBlock() != this) {
            return false;
        }

        for (BlockPos extensionPos : extensionPositions) {
            BlockState extensionState = level.getBlockState(extensionPos);
            if (!isPlaceholderBlock(extensionState)) {
                return false;
            }
        }

        return true;
    }

    protected void destroyLargeBlock(Level level, BlockPos centerPos, BlockState state) {
        Direction facing = state.getValue(FACING);
        BlockPos[] extensionPositions = getExtensionPositions(centerPos, facing);

        for (BlockPos extensionPos : extensionPositions) {
            level.destroyBlock(extensionPos, true);
        }
        level.destroyBlock(centerPos, true);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getLargeBlockShape(state, pos);
    }

    protected abstract VoxelShape getLargeBlockShape(BlockState state, BlockPos pos);
}
