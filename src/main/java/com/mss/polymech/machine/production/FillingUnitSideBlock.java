package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.LargeMachineSideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class FillingUnitSideBlock extends LargeMachineSideBlock<FillingUnitSideBlockEntity> {

    public static final MapCodec<FillingUnitSideBlock> CODEC = simpleCodec(FillingUnitSideBlock::new);

    public FillingUnitSideBlock(Properties properties) {
        super(properties, ModBlocks.FILLING_UNIT);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected FillingUnitSideBlockEntity createSideBlockEntity(BlockPos pos, BlockState state) {
        return new FillingUnitSideBlockEntity(pos, state);
    }
}
