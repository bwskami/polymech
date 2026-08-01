package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.LargeMachineSideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class HorizontalSteamBoilerSideBlock extends LargeMachineSideBlock<HorizontalSteamBoilerSideBlockEntity> {

    public static final MapCodec<HorizontalSteamBoilerSideBlock> CODEC = simpleCodec(HorizontalSteamBoilerSideBlock::new);

    public HorizontalSteamBoilerSideBlock(Properties properties) {
        super(properties, ModBlocks.HORIZONTAL_STEAM_BOILER);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected HorizontalSteamBoilerSideBlockEntity createSideBlockEntity(BlockPos pos, BlockState state) {
        return new HorizontalSteamBoilerSideBlockEntity(pos, state);
    }
}
