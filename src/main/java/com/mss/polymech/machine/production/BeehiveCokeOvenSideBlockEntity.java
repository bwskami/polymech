package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class BeehiveCokeOvenSideBlockEntity extends BaseIOSideBlockEntity {

    public BeehiveCokeOvenSideBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BEEHIVE_COKE_OVEN_SIDE.get(), pos, state);
    }
}
