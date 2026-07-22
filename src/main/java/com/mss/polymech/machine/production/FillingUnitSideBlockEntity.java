package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class FillingUnitSideBlockEntity extends BaseIOSideBlockEntity {

    public FillingUnitSideBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FILLING_UNIT_SIDE.get(), pos, state);
    }
}
