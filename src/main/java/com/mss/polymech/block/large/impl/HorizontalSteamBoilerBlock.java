package com.mss.polymech.block.large.impl;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.entity.large.HorizontalSteamBoilerBlockEntity;
import com.mss.polymech.block.large.LargeBlock;
import com.mss.polymech.block.large.LargeBlockPlaceholder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class HorizontalSteamBoilerBlock extends LargeBlock {
    public static final MapCodec<HorizontalSteamBoilerBlock> CODEC = simpleCodec(HorizontalSteamBoilerBlock::new);

    private static final VoxelShape SHAPE_NORTH_SOUTH = Shapes.or(
        box(-8, 0, -24, 24, 32, 24)
    );

    private static final VoxelShape SHAPE_EAST_WEST = Shapes.or(
        box(-8, 0, -8, 40, 32, 24)
    );

    public HorizontalSteamBoilerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalSteamBoilerBlock> codec() {
        return CODEC;
    }

    @Override
    protected BlockPos[] getExtensionPositions(BlockPos centerPos, Direction facing) {
        return switch (facing) {
            case NORTH -> new BlockPos[]{centerPos.north(), centerPos.north(2), centerPos.north(3)};
            case SOUTH -> new BlockPos[]{centerPos.south(), centerPos.south(2), centerPos.south(3)};
            case WEST -> new BlockPos[]{centerPos.west(), centerPos.west(2), centerPos.west(3)};
            case EAST -> new BlockPos[]{centerPos.east(), centerPos.east(2), centerPos.east(3)};
            default -> new BlockPos[]{centerPos.north(), centerPos.north(2), centerPos.north(3)};
        };
    }

    @Override
    protected BlockState getPlaceholderState(BlockState originalState) {
        return ModBlocks.LARGE_BLOCK_PLACEHOLDER.get().defaultBlockState()
            .setValue(LargeBlockPlaceholder.FACING, originalState.getValue(FACING));
    }

    @Override
    protected boolean isPlaceholderBlock(BlockState state) {
        return state.getBlock() instanceof LargeBlockPlaceholder;
    }

    @Override
    protected VoxelShape getLargeBlockShape(BlockState state, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            return SHAPE_NORTH_SOUTH;
        }
        return SHAPE_EAST_WEST;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HorizontalSteamBoilerBlockEntity(pos, state);
    }
}
