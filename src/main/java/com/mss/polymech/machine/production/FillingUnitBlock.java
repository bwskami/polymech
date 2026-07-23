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

import net.neoforged.neoforge.registries.DeferredBlock;

public class FillingUnitBlock extends BaseMachineBlock {

    private static final MapCodec<FillingUnitBlock> CODEC = simpleCodec(FillingUnitBlock::new);

    public FillingUnitBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FillingUnitBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.FILLING_UNIT.get(), FillingUnitBlockEntity::tick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof FillingUnitBlockEntity fillingBE) {
                player.openMenu(fillingBE, pos);
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
                        ModBlocks.FILLING_UNIT_SIDE.get().defaultBlockState().setValue(FACING, state.getValue(FACING)));
                BlockEntity be = level.getBlockEntity(sidePos);
                if (be instanceof FillingUnitSideBlockEntity sideBE) {
                    sideBE.setParentPos(pos);
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof FillingUnitBlockEntity fillingBE) {
                Containers.dropContents(level, pos, fillingBE.getItems());
            }
            BlockPos[] sidePositions = getSidePositions(state, pos);
            for (BlockPos sidePos : sidePositions) {
                if (level.getBlockState(sidePos).is(ModBlocks.FILLING_UNIT_SIDE.get())) {
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
                new Vec3i(1, 0, 0),
                new Vec3i(-1, 0, 0),
                new Vec3i(0, 0, 1),
                new Vec3i(0, 0, -1),
        };
    }

    @Override
    public DeferredBlock<?> getSideBlock() {
        return ModBlocks.FILLING_UNIT_SIDE;
    }

    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return ModBlockEntities.FILLING_UNIT.get();
    }
}
