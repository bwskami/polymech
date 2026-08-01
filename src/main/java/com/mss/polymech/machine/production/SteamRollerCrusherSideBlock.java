package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.LargeMachineSideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class SteamRollerCrusherSideBlock extends LargeMachineSideBlock<SteamRollerCrusherSideBlockEntity> {

    public static final MapCodec<SteamRollerCrusherSideBlock> CODEC = simpleCodec(SteamRollerCrusherSideBlock::new);

    public SteamRollerCrusherSideBlock(Properties properties) {
        super(properties, ModBlocks.STEAM_ROLLER_CRUSHER);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected SteamRollerCrusherSideBlockEntity createSideBlockEntity(BlockPos pos, BlockState state) {
        return new SteamRollerCrusherSideBlockEntity(pos, state);
    }
}
