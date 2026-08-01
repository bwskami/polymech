package com.mss.polymech.machine.production;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.common.LargeMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;

import java.util.function.Supplier;

public class FillingUnitBlock extends LargeMachineBlock<FillingUnitBlockEntity> {

    private static final MapCodec<FillingUnitBlock> CODEC = simpleCodec(FillingUnitBlock::new);

    public FillingUnitBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected FillingUnitBlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new FillingUnitBlockEntity(pos, state);
    }

    @Override
    public Supplier<BlockEntityType<FillingUnitBlockEntity>> getBlockEntityType() {
        return ModBlockEntities.FILLING_UNIT;
    }

    @Override
    public DeferredBlock<?> getSideBlock() {
        return ModBlocks.FILLING_UNIT_SIDE;
    }

    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return ModBlockEntities.FILLING_UNIT.get();
    }

    @Override
    public Vec3i[] getSideOffsets() {
        return new Vec3i[]{
                new Vec3i(1, 0, 0),
                new Vec3i(-1, 0, 0),
                new Vec3i(0, 0, 1),
                new Vec3i(0, 0, -1),
        };
    }
}
