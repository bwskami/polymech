package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class HorizontalSteamBoilerSideBlockEntity extends BaseIOSideBlockEntity {

    public HorizontalSteamBoilerSideBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.HORIZONTAL_STEAM_BOILER_SIDE.get(), pos, state);
    }
}
