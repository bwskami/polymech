package com.mss.polymech.block.large;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * 抽象多方块机器基类
 * 这是一个占据多个方块空间但只有一个BlockEntity的大型机器
 */
public abstract class AbstractMultiblockMachine extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    protected AbstractMultiblockMachine(Properties properties) {
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

    // 简单的1x1x1碰撞箱
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);

        Direction facing = state.getValue(FACING);
        
        // 创建多方块结构并验证空间
        MultiblockStructure structure = createStructure(facing);
        if (!canPlaceStructure(level, pos, structure)) {
            // 空间不足，撤销放置
            level.removeBlock(pos, false);
            return;
        }

        // 放置占位方块
        placeStructure(level, pos, structure, state);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (state.is(newState.getBlock())) {
            return;
        }

        // 移除整个结构
        Direction facing = state.getValue(FACING);
        MultiblockStructure structure = createStructure(facing);

        removeStructure(level, pos, structure);

        super.onRemove(state, level, pos, newState, isMoving);
    }

    /**
     * 检查是否可以放置结构
     */
    private boolean canPlaceStructure(Level level, BlockPos corePos, MultiblockStructure structure) {
        for (BlockPos offset : structure.getOccupiedPositions()) {
            if (!offset.equals(BlockPos.ZERO)) { // 跳过核心位置（已放置）
                BlockPos worldPos = corePos.offset(offset);
                BlockState existingState = level.getBlockState(worldPos);
                
                // 检查该位置是否为空气或可以替换的方块
                if (!existingState.isAir() && !isReplaceableBlock(existingState)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 放置多方块结构
     */
    private void placeStructure(Level level, BlockPos corePos, MultiblockStructure structure, BlockState originalState) {
        for (BlockPos offset : structure.getOccupiedPositions()) {
            if (!offset.equals(BlockPos.ZERO)) { // 跳过核心位置
                BlockPos worldPos = corePos.offset(offset);
                level.setBlock(worldPos, getPlaceholderState(originalState), Block.UPDATE_ALL_IMMEDIATE);
            }
        }
    }

    /**
     * 移除多方块结构
     */
    private void removeStructure(Level level, BlockPos corePos, MultiblockStructure structure) {
        for (BlockPos offset : structure.getOccupiedPositions()) {
            if (!offset.equals(BlockPos.ZERO)) { // 跳过核心位置（这个方法正在处理它）
                BlockPos worldPos = corePos.offset(offset);
                BlockState existingState = level.getBlockState(worldPos);
                
                // 只移除我们的占位方块
                if (isPlaceholderBlock(existingState)) {
                    level.removeBlock(worldPos, false);
                }
            }
        }
    }

    /**
     * 检查方块是否可以被替换（例如草方块、花等）
     */
    protected boolean isReplaceableBlock(BlockState state) {
        return state.canBeReplaced();
    }

    // 子类需要实现的方法
    protected abstract MultiblockStructure createStructure(Direction facing);

    protected abstract BlockState getPlaceholderState(BlockState originalState);

    protected abstract boolean isPlaceholderBlock(BlockState state);

    // 必须实现codec方法
    @Override
    protected abstract MapCodec<? extends BaseEntityBlock> codec();
}