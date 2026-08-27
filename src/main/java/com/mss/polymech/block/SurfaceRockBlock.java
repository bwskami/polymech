package com.mss.polymech.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 地表碎石指示方块（对齐GTM SurfaceRockBlock）。
 * <p>
 * 贴在地表的小薄片方块，用于指示下方矿脉方向。
 * 不同矿物的碎石通过tintindex染色区分颜色。
 * 碰撞形状为3像素高薄片，不是完整方块。
 * </p>
 */
public class SurfaceRockBlock extends Block {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    private static final VoxelShape AABB_NORTH = Block.box(2, 2, 0, 14, 14, 3);
    private static final VoxelShape AABB_SOUTH = Block.box(2, 2, 13, 14, 14, 16);
    private static final VoxelShape AABB_WEST  = Block.box(0, 2, 2, 3, 14, 14);
    private static final VoxelShape AABB_EAST  = Block.box(13, 2, 2, 16, 14, 14);
    private static final VoxelShape AABB_UP    = Block.box(2, 13, 2, 14, 16, 14);
    private static final VoxelShape AABB_DOWN  = Block.box(2, 0, 2, 14, 3, 14);

    public SurfaceRockBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.defaultBlockState().setValue(FACING, Direction.DOWN));
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case DOWN  -> AABB_DOWN;
            case UP    -> AABB_UP;
            case NORTH -> AABB_NORTH;
            case SOUTH -> AABB_SOUTH;
            case WEST  -> AABB_WEST;
            case EAST  -> AABB_EAST;
        };
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        var facing = state.getValue(FACING);
        var attachedBlock = pos.relative(facing);
        return level.getBlockState(attachedBlock).isFaceSturdy(level, attachedBlock, facing.getOpposite());
    }

    @Override
    public void neighborChanged(BlockState state, net.minecraft.world.level.Level level, BlockPos pos,
                                Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        if (!canSurvive(state, level, pos)) {
            level.removeBlock(pos, false);
        }
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return getStateForDirection(context.getNearestLookingVerticalDirection());
    }

    public BlockState getStateForDirection(Direction direction) {
        return defaultBlockState().setValue(FACING, direction);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public boolean isCollisionShapeFullBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }
}
