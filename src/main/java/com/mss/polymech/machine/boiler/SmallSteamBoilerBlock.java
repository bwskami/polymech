package com.mss.polymech.machine.boiler;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.client.gui.boiler.SmallSteamBoilerUI;
import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.BaseMachineBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.fluids.FluidUtil;
import org.jetbrains.annotations.Nullable;

/**
 * 小型蒸汽锅炉方块（单方块机器）。
 * <p>
 * 继承 {@link BaseMachineBlock} 复用通用机器行为（FACING 状态、面配置 Shift 交互、破坏掉落），
 * 覆盖 LDLib2 UI 和流体容器交互（锅炉有独立的入水/出蒸汽处理器）。
 * </p>
 */
public class SmallSteamBoilerBlock extends BaseMachineBlock implements BlockUIMenuType.BlockUI {

    public static final MapCodec<SmallSteamBoilerBlock> CODEC = simpleCodec(SmallSteamBoilerBlock::new);

    public SmallSteamBoilerBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    // ========== 基类抽象方法实现 ==========

    @Nullable
    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return ModBlockEntities.SMALL_STEAM_BOILER.get();
    }

    // ========== 方块实体 ==========

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SmallSteamBoilerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.SMALL_STEAM_BOILER.get(),
                (level1, pos1, state1, be1) ->
                        BaseIOBlockEntity.tick(level1, pos1, state1, (BaseIOBlockEntity) be1));
    }

    // ========== 渲染 ==========

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ========== 流体容器交互（继承基类后仍需保持锅炉特有的双处理器交互） ==========

    /**
     * 手持流体容器（桶/通用流体单元）右键小锅炉：
     * 直接与水输入罐/蒸汽输出罐交互（支持部分转移，不吞流体）。
     * 未发生流体交互时放行，继续走默认交互（打开 GUI）。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AbstractSteamBoilerBlockEntity boiler) {
                if (FluidUtil.interactWithFluidHandler(player, hand, boiler.getWaterInputHandler())
                        || FluidUtil.interactWithFluidHandler(player, hand, boiler.getSteamOutputHandler())) {
                    return ItemInteractionResult.SUCCESS;
                }
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    // ========== LDLib2 UI ==========

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return SmallSteamBoilerUI.create(holder);
    }

    @Override
    public void openMachineUI(ServerPlayer player, BlockPos pos) {
        BlockUIMenuType.openUI(player, pos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown()) {
            // Shift+右键：面配置 UI（走基类的 ClientHooks）
            if (level.isClientSide()) {
                if (level.getBlockEntity(pos) instanceof BaseIOBlockEntity machine) {
                    com.mss.polymech.ClientHooks.sideConfigOpener.accept(pos.immutable(), machine);
                }
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide()) {
            BlockUIMenuType.openUI((ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }
}
