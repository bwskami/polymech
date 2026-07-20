package com.mss.polymech.block.large.impl;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.entity.large.HorizontalSteamBoilerBlockEntity;
import com.mss.polymech.block.large.AbstractMultiblockMachine;
import com.mss.polymech.block.large.MultiblockPlaceholder;
import com.mss.polymech.block.large.MultiblockStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * 水平蒸汽锅炉 - 多方块机器示例实现
 */
public class HorizontalSteamBoilerBlock extends AbstractMultiblockMachine {
    public static final MapCodec<HorizontalSteamBoilerBlock> CODEC = simpleCodec(HorizontalSteamBoilerBlock::new);

    public HorizontalSteamBoilerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends AbstractMultiblockMachine> codec() {
        return CODEC;
    }

    @Override
    protected MultiblockStructure createStructure(Direction facing) {
        Set<BlockPos> positions = new HashSet<>();
        positions.add(BlockPos.ZERO); // 核心位置

        // 根据朝向定义扩展部分
        switch (facing) {
            case NORTH:
                positions.add(new BlockPos(0, 0, -1));
                positions.add(new BlockPos(0, 0, -2));
                positions.add(new BlockPos(0, 0, -3));
                break;
            case SOUTH:
                positions.add(new BlockPos(0, 0, 1));
                positions.add(new BlockPos(0, 0, 2));
                positions.add(new BlockPos(0, 0, 3));
                break;
            case WEST:
                positions.add(new BlockPos(-1, 0, 0));
                positions.add(new BlockPos(-2, 0, 0));
                positions.add(new BlockPos(-3, 0, 0));
                break;
            case EAST:
                positions.add(new BlockPos(1, 0, 0));
                positions.add(new BlockPos(2, 0, 0));
                positions.add(new BlockPos(3, 0, 0));
                break;
        }

        return new MultiblockStructure(positions, facing);
    }

    @Override
    protected BlockState getPlaceholderState(BlockState originalState) {
        return ModBlocks.LARGE_BLOCK_PLACEHOLDER.get().defaultBlockState()
            .setValue(MultiblockPlaceholder.FACING, originalState.getValue(FACING));
    }

    @Override
    protected boolean isPlaceholderBlock(BlockState state) {
        return state.getBlock() instanceof MultiblockPlaceholder;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HorizontalSteamBoilerBlockEntity(pos, state);
    }
}