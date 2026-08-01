package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class SteamRollerCrusherSideBlockEntity extends BaseIOSideBlockEntity {

    public SteamRollerCrusherSideBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STEAM_ROLLER_CRUSHER_SIDE.get(), pos, state);
    }
}
