package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.LargeMachineSideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class BeehiveCokeOvenSideBlock extends LargeMachineSideBlock<BeehiveCokeOvenSideBlockEntity> {

    public static final MapCodec<BeehiveCokeOvenSideBlock> CODEC = simpleCodec(BeehiveCokeOvenSideBlock::new);

    public BeehiveCokeOvenSideBlock(Properties properties) {
        super(properties, ModBlocks.BEEHIVE_COKE_OVEN);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected BeehiveCokeOvenSideBlockEntity createSideBlockEntity(BlockPos pos, BlockState state) {
        return new BeehiveCokeOvenSideBlockEntity(pos, state);
    }
}
