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

public class PrimitiveBlastFurnaceBlock extends LargeMachineBlock<PrimitiveBlastFurnaceBlockEntity> {

    private static final MapCodec<PrimitiveBlastFurnaceBlock> CODEC = simpleCodec(PrimitiveBlastFurnaceBlock::new);

    public PrimitiveBlastFurnaceBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected PrimitiveBlastFurnaceBlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new PrimitiveBlastFurnaceBlockEntity(pos, state);
    }

    @Override
    public Supplier<BlockEntityType<PrimitiveBlastFurnaceBlockEntity>> getBlockEntityType() {
        return ModBlockEntities.PRIMITIVE_BLAST_FURNACE;
    }

    @Override
    public DeferredBlock<?> getSideBlock() {
        return ModBlocks.PRIMITIVE_BLAST_FURNACE_SIDE;
    }

    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return ModBlockEntities.PRIMITIVE_BLAST_FURNACE.get();
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
