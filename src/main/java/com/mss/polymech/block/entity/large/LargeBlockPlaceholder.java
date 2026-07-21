package com.mss.polymech.block.entity.large;

import com.mss.polymech.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class LargeBlockPlaceholder extends Block implements EntityBlock {

    public LargeBlockPlaceholder() {
        super(Properties.of()
                .strength(-1.0F, 3600000.0F)
                .noOcclusion()
                .isValidSpawn((state, reader, pos, entityType) -> false)
                .isSuffocating((state, reader, pos) -> false)
                .isViewBlocking((state, reader, pos) -> false));
    }

    @Nullable
    public static BlockPos getMainBlockPos(BlockGetter world, BlockPos thisPos) {
        BlockEntity be = world.getBlockEntity(thisPos);
        if (be instanceof LargeBlockPlaceholderBlockEntity placeholder) {
            return placeholder.getMainPos();
        }
        return null;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new LargeBlockPlaceholderBlockEntity(pos, state);
    }

    @NotNull
    @Override
    public RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @NotNull
    @Override
    public VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return proxyShape(world, pos);
    }

    @NotNull
    @Override
    public VoxelShape getCollisionShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return proxyShape(world, pos);
    }

    @NotNull
    @Override
    public VoxelShape getVisualShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return Shapes.empty();
    }

    @NotNull
    @Override
    public VoxelShape getOcclusionShape(@NotNull BlockState state, @NotNull BlockGetter world, @NotNull BlockPos pos) {
        return Shapes.empty();
    }

    private VoxelShape proxyShape(BlockGetter world, BlockPos pos) {
        BlockPos mainPos = getMainBlockPos(world, pos);
        if (mainPos == null) return Shapes.empty();

        BlockState mainState = world.getBlockState(mainPos);
        Block mainBlock = mainState.getBlock();
        if (!(mainBlock instanceof HorizontalSteamBoilerBlock boilerBlock)) return Shapes.empty();

        Direction facing = mainState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        VoxelShape fullShape = boilerBlock.getShapeForFacing(facing);
        
        if (fullShape == Shapes.empty()) return Shapes.empty();

        BlockPos offset = pos.subtract(mainPos);
        VoxelShape movedShape = fullShape.move(-offset.getX() * 16.0, -offset.getY() * 16.0, -offset.getZ() * 16.0);
        
        // 裁剪到占位方块的1×1×1空间
        VoxelShape unitBox = Block.box(0, 0, 0, 16, 16, 16);
        return Shapes.joinUnoptimized(movedShape, unitBox, BooleanOp.AND).optimize();
    }

    @Override
    public void onRemove(@NotNull BlockState state, @NotNull net.minecraft.world.level.Level world, @NotNull BlockPos pos, @NotNull BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockPos mainPos = getMainBlockPos(world, pos);
            if (mainPos != null) {
                BlockState mainState = world.getBlockState(mainPos);
                if (!mainState.isAir()) {
                    world.removeBlock(mainPos, false);
                }
            }
            super.onRemove(state, world, pos, newState, isMoving);
        }
    }
}
