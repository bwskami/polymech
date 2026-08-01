package com.mss.polymech.machine.production;

import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public class PrimitiveBlastFurnaceSideBlockEntity extends BaseIOSideBlockEntity {

    public PrimitiveBlastFurnaceSideBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PRIMITIVE_BLAST_FURNACE_SIDE.get(), pos, state);
    }
}
