package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.LargeMachineSideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class SteamTurbineGeneratorSideBlock extends LargeMachineSideBlock<SteamTurbineGeneratorSideBlockEntity> {

    public static final MapCodec<SteamTurbineGeneratorSideBlock> CODEC = simpleCodec(SteamTurbineGeneratorSideBlock::new);

    public SteamTurbineGeneratorSideBlock(Properties properties) {
        super(properties, ModBlocks.STEAM_TURBINE_GENERATOR);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected SteamTurbineGeneratorSideBlockEntity createSideBlockEntity(BlockPos pos, BlockState state) {
        return new SteamTurbineGeneratorSideBlockEntity(pos, state);
    }
}
