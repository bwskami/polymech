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

public class SteamTurbineGeneratorBlock extends LargeMachineBlock<SteamTurbineGeneratorBlockEntity> {

    private static final MapCodec<SteamTurbineGeneratorBlock> CODEC = simpleCodec(SteamTurbineGeneratorBlock::new);

    public SteamTurbineGeneratorBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected SteamTurbineGeneratorBlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SteamTurbineGeneratorBlockEntity(pos, state);
    }

    @Override
    public Supplier<BlockEntityType<SteamTurbineGeneratorBlockEntity>> getBlockEntityType() {
        return ModBlockEntities.STEAM_TURBINE_GENERATOR;
    }

    @Override
    public DeferredBlock<?> getSideBlock() {
        return ModBlocks.STEAM_TURBINE_GENERATOR_SIDE;
    }

    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return ModBlockEntities.STEAM_TURBINE_GENERATOR.get();
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
