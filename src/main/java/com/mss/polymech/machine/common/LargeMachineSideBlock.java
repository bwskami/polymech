package com.mss.polymech.machine.common;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.SideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

/**
 * 大型机器侧面方块抽象基类，封装所有侧面方块的通用行为。
 * <p>
 * 子类只需提供侧面方块实体构造逻辑和主方块引用，其余行为（右键打开父方块GUI、
 * 破坏时连锁移除父方块、中键选取返回主方块物品等）由基类统一实现。
 * </p>
 *
 * @param <T> 侧面方块实体类型，必须继承 {@link BaseIOSideBlockEntity}
 */
public abstract class LargeMachineSideBlock<T extends BaseIOSideBlockEntity> extends SideBlock {

    /** 对应的主方块延迟引用 */
    private final DeferredBlock<? extends BaseMachineBlock> mainBlock;

    protected LargeMachineSideBlock(Properties properties, DeferredBlock<? extends BaseMachineBlock> mainBlock) {
        super(properties);
        this.mainBlock = mainBlock;
    }

    /** 获取对应的主方块引用 */
    public DeferredBlock<? extends BaseMachineBlock> getMainBlock() {
        return mainBlock;
    }

    // ========== 子类需实现的抽象方法 ==========

    @Override
    protected abstract MapCodec<? extends Block> codec();

    /**
     * 创建侧面方块实体实例。
     *
     * @param pos   方块位置
     * @param state 方块状态
     * @return 侧面方块实体
     */
    protected abstract T createSideBlockEntity(BlockPos pos, BlockState state);

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return createSideBlockEntity(pos, state);
    }

    // ========== 通用方块状态定义 ==========

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BaseMachineBlock.FACING);
    }

    // ========== 通用侧面方块行为 ==========

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return new ItemStack(mainBlock.get());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseIOSideBlockEntity sideBE) {
                BlockEntity parent = sideBE.getParentBlock();
                if (parent instanceof MenuProvider menuProvider) {
                    BlockPos parentPos = sideBE.getParentPos();
                    if (parentPos != null) {
                        player.openMenu(menuProvider, parentPos);
                        return InteractionResult.SUCCESS;
                    }
                }
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof BaseIOSideBlockEntity sideBE) {
                BlockPos parentPos = sideBE.getParentPos();
                if (parentPos != null) {
                    level.destroyBlock(parentPos, true);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
