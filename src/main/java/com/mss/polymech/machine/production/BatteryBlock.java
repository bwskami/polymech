package com.mss.polymech.machine.production;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.client.gui.battery.BatteryUI;
import com.mss.polymech.powergrid.GridNode;
import com.mss.polymech.powergrid.GridNodeBlock;
import com.mss.polymech.powergrid.WorldPowerGrid;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * 蓄电池方块（单方块机器）。
 * <p>
 * 提供电网节点（位于方块中心），支持线轴拉线连接。
 * 右键打开 LDLib2 蓄电池 UI，显示储能/充放电状态。
 * </p>
 */
public class BatteryBlock extends BaseEntityBlock implements GridNodeBlock, BlockUIMenuType.BlockUI {

    public static final MapCodec<BatteryBlock> CODEC = simpleCodec(BatteryBlock::new);

    private final boolean creative;

    public BatteryBlock(Properties properties) {
        this(properties, false);
    }

    protected BatteryBlock(Properties properties, boolean creative) {
        super(properties);
        this.creative = creative;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
    }

    public boolean isCreative() {
        return creative;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(BlockStateProperties.HORIZONTAL_FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (creative) {
            return new CreativeBatteryBlockEntity(pos, state);
        }
        return new BatteryBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (creative) {
            return createTickerHelper(type, ModBlockEntities.CREATIVE_BATTERY.get(),
                    (level1, pos1, state1, be1) -> be1.tick(level1));
        }
        return createTickerHelper(type, ModBlockEntities.BATTERY.get(),
                (level1, pos1, state1, be1) -> be1.tick(level1));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide()) {
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide()) {
            WorldPowerGrid.get((ServerLevel) level).removeNode(pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown()) {
            if (level.isClientSide()) {
                var be = level.getBlockEntity(pos);
                if (be instanceof BatteryBlockEntity battery) {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    final var screenPos = pos.immutable();
                    final var config = battery.getSideConfig();
                    mc.execute(() -> mc.setScreen(
                            new com.mss.polymech.client.gui.screen.SideConfigScreen(
                                    screenPos, config)));
                }
            }
            return InteractionResult.SUCCESS;
        }
        if (!level.isClientSide()) {
            BlockUIMenuType.openUI((net.minecraft.server.level.ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return BatteryUI.create(holder);
    }

    // ========== GridNodeBlock ==========

    @Override
    public Map<Integer, Vec3> getNodePositions(BlockState state) {
        return Map.of(0, new Vec3(0.5, 0.5, 0.5));
    }
}
