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

public class HorizontalSteamBoilerBlock extends LargeMachineBlock<HorizontalSteamBoilerBlockEntity> {

    private static final MapCodec<HorizontalSteamBoilerBlock> CODEC = simpleCodec(HorizontalSteamBoilerBlock::new);

    public HorizontalSteamBoilerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected HorizontalSteamBoilerBlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new HorizontalSteamBoilerBlockEntity(pos, state);
    }

    @Override
    public Supplier<BlockEntityType<HorizontalSteamBoilerBlockEntity>> getBlockEntityType() {
        return ModBlockEntities.HORIZONTAL_STEAM_BOILER;
    }

    @Override
    public DeferredBlock<?> getSideBlock() {
        return ModBlocks.HORIZONTAL_STEAM_BOILER_SIDE;
    }

    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return ModBlockEntities.HORIZONTAL_STEAM_BOILER.get();
    }

    @Override
    public Vec3i[] getSideOffsets() {
        return new Vec3i[]{
                new Vec3i(0, 3, 0),
                new Vec3i(0, 3, 2),
                new Vec3i(0, 4, 2),
        };
    }

    @Override
    public Vec3i[][] getFillRegions() {
        return new Vec3i[][]{
                {new Vec3i(-1, 0, -2), new Vec3i(1, 2, 2)},
        };
    }
}
