package com.mss.polymech.machine.production;

import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.block.entity.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import com.mojang.serialization.MapCodec;
import org.jetbrains.annotations.Nullable;

public class HorizontalSteamBoilerBlock extends BaseMachineBlock {

    private static final MapCodec<HorizontalSteamBoilerBlock> CODEC = simpleCodec(HorizontalSteamBoilerBlock::new);

    public HorizontalSteamBoilerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new HorizontalSteamBoilerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.HORIZONTAL_STEAM_BOILER.get(), HorizontalSteamBoilerBlockEntity::tick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HorizontalSteamBoilerBlockEntity boilerBE) {
                player.openMenu(boilerBE, pos);
                return InteractionResult.SUCCESS;
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide()) {
            BlockPos[] sidePositions = getSidePositions(state, pos);
            for (BlockPos sidePos : sidePositions) {
                level.setBlockAndUpdate(sidePos,
                        ModBlocks.HORIZONTAL_STEAM_BOILER_SIDE.get().defaultBlockState().setValue(FACING, state.getValue(FACING)));
                BlockEntity be = level.getBlockEntity(sidePos);
                if (be instanceof HorizontalSteamBoilerSideBlockEntity sideBE) {
                    sideBE.setParentPos(pos);
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof HorizontalSteamBoilerBlockEntity boilerBE) {
                Containers.dropContents(level, pos, boilerBE.getItems());
            }
            BlockPos[] sidePositions = getSidePositions(state, pos);
            for (BlockPos sidePos : sidePositions) {
                if (level.getBlockState(sidePos).is(ModBlocks.HORIZONTAL_STEAM_BOILER_SIDE.get())) {
                    level.destroyBlock(sidePos, false);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!level.isClientSide()) {
            BlockPos[] sidePositions = getSidePositions(state, pos);
            for (BlockPos sidePos : sidePositions) {
                if (!level.getBlockState(sidePos).isAir()) {
                    return false;
                }
            }
        }
        return true;
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
