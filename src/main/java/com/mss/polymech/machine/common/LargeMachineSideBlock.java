package com.mss.polymech.machine.common;

import com.mojang.serialization.MapCodec;
import com.mss.polymech.machine.BaseIOSideBlockEntity;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.SideBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 大型机器侧面方块（非抽象、配置驱动），所有机器共用。
 * <p>
 * 侧面方块全部是通用代理，不分类。
 * 哪个位置对应什么功能，由主方块的 BlockEntity 根据位置映射。
 * </p>
 */
public class LargeMachineSideBlock extends SideBlock {

    private final MapCodec<LargeMachineSideBlock> codec;
    private final DeferredBlock<? extends BaseMachineBlock> mainBlock;
    private final Supplier<BlockEntityType<?>> sideBETypeSupplier;

    public LargeMachineSideBlock(Properties properties,
                                  DeferredBlock<? extends BaseMachineBlock> mainBlock,
                                  Supplier<BlockEntityType<?>> sideBETypeSupplier) {
        super(properties);
        this.mainBlock = mainBlock;
        this.sideBETypeSupplier = sideBETypeSupplier;
        this.codec = simpleCodec(p -> new LargeMachineSideBlock(p, mainBlock, sideBETypeSupplier));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return codec;
    }

    public DeferredBlock<? extends BaseMachineBlock> getMainBlock() {
        return mainBlock;
    }

    // ========== 方块实体 ==========

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GenericSideBlockEntity(sideBETypeSupplier.get(), pos, state);
    }

    // ========== 通用方块状态定义 ==========

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BaseMachineBlock.FACING);
    }

    // ========== 通用侧面方块行为 ==========

    /**
     * 手持流体容器（桶/通用流体单元）右键侧面方块：
     * 通过方块流体能力直接与机器储罐交互（支持部分转移，不吞流体）。
     * 未发生流体交互时放行，继续走默认交互（打开 GUI）。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            if (FluidUtil.interactWithFluidHandler(player, hand, level, pos, hitResult.getDirection())) {
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

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
                BlockPos parentPos = sideBE.getParentPos();
                if (parentPos != null) {
                    BlockState parentState = level.getBlockState(parentPos);
                    Block parentBlock = parentState.getBlock();
                    if (parentBlock instanceof BaseMachineBlock machineBlock) {
                        machineBlock.openMachineUI((net.minecraft.server.level.ServerPlayer) player, parentPos);
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
