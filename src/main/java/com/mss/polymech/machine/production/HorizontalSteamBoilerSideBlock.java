package com.mss.polymech.machine.production;

import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import com.mojang.serialization.MapCodec;
import org.jetbrains.annotations.Nullable;

public class HorizontalSteamBoilerSideBlock extends Block implements EntityBlock {

    public static final MapCodec<HorizontalSteamBoilerSideBlock> CODEC = simpleCodec(HorizontalSteamBoilerSideBlock::new);

    public HorizontalSteamBoilerSideBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(BaseMachineBlock.FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BaseMachineBlock.FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HorizontalSteamBoilerSideBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HorizontalSteamBoilerSideBlockEntity sideBE) {
                BlockPos parentPos = sideBE.getParentPos();
                if (parentPos != null) {
                    BlockEntity parentBE = level.getBlockEntity(parentPos);
                    if (parentBE instanceof HorizontalSteamBoilerBlockEntity) {
                        level.destroyBlock(parentPos, false);
                    }
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
