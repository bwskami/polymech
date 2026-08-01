package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.LargeMachineSideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class PrimitiveBlastFurnaceSideBlock extends LargeMachineSideBlock<PrimitiveBlastFurnaceSideBlockEntity> {

    public static final MapCodec<PrimitiveBlastFurnaceSideBlock> CODEC = simpleCodec(PrimitiveBlastFurnaceSideBlock::new);

    public PrimitiveBlastFurnaceSideBlock(Properties properties) {
        super(properties, ModBlocks.PRIMITIVE_BLAST_FURNACE);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected PrimitiveBlastFurnaceSideBlockEntity createSideBlockEntity(BlockPos pos, BlockState state) {
        return new PrimitiveBlastFurnaceSideBlockEntity(pos, state);
    }
}
