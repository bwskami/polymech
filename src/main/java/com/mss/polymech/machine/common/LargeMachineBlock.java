package com.mss.polymech.machine.common;

import com.mss.polymech.machine.BaseIOSideBlockEntity;
import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoBlockEntity;

import java.util.function.Supplier;

/**
 * 大型机器主方块抽象基类，封装主方块通用行为：放置时生成侧面方块、破坏时同步移除、
 * 交互打开GUI、tick驱动工作逻辑以及碰撞/存活检测。
 *
 * @param <T> 主方块实体类型，必须继承 {@link BaseIOBlockEntity} 并实现 {@link GeoBlockEntity}
 */
public abstract class LargeMachineBlock<T extends BaseIOBlockEntity & GeoBlockEntity> extends BaseMachineBlock {

    protected LargeMachineBlock(Properties properties) {
        super(properties);
    }

    /**
     * 获取主方块对应的侧面方块引用。
     *
     * @return 侧面方块延迟引用
     */
    @Override
    public abstract DeferredBlock<?> getSideBlock();

    /**
     * 获取主方块实体类型引用。
     *
     * @return 方块实体类型供应商
     */
    public abstract Supplier<BlockEntityType<T>> getBlockEntityType();

    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return getBlockEntityType().get();
    }

    /**
     * 创建主方块实体实例。
     *
     * @param pos   方块位置
     * @param state 方块状态
     * @return 主方块实体
     */
    protected abstract T createBlockEntity(BlockPos pos, BlockState state);

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return createBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T2 extends BlockEntity> BlockEntityTicker<T2> getTicker(Level level, BlockState state, BlockEntityType<T2> type) {
        return createTickerHelper(type, getBlockEntityType().get(), BaseIOBlockEntity::tick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseIOBlockEntity machineBE) {
                player.openMenu(machineBE, pos);
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
                        getSideBlock().get().defaultBlockState().setValue(FACING, state.getValue(FACING)));
                BlockEntity be = level.getBlockEntity(sidePos);
                if (be instanceof BaseIOSideBlockEntity sideBE) {
                    sideBE.setParentPos(pos);
                }
            }
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseIOBlockEntity machineBE) {
                Containers.dropContents(level, pos, machineBE.getItems());
            }
            BlockPos[] sidePositions = getSidePositions(state, pos);
            for (BlockPos sidePos : sidePositions) {
                if (level.getBlockState(sidePos).is(getSideBlock().get())) {
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
}
