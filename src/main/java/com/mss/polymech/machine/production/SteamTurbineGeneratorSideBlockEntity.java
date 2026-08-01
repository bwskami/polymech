package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class SteamTurbineGeneratorSideBlockEntity extends BaseIOSideBlockEntity {

    public SteamTurbineGeneratorSideBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_TURBINE_GENERATOR_SIDE.get(), pos, state);
    }
}
