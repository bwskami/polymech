package com.mss.polymech.machine.production;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.client.gui.battery.BatteryUI;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 蓄电池方块（单方块机器）。
 * <p>
 * 继承 {@link BaseMachineBlock} 复用通用机器行为（FACING 状态、电网节点注册与清理、
 * 放置同步），仅覆盖 LDLib2 UI 与方块实体创建。
 * </p>
 * <p>
 * 提供电网节点（位于方块中心），支持线轴拉线连接。
 * 右键打开 LDLib2 蓄电池 UI，显示储能/充放电状态。
 * </p>
 */
public class BatteryBlock extends BaseMachineBlock implements BlockUIMenuType.BlockUI {

    public static final MapCodec<BatteryBlock> CODEC = simpleCodec(BatteryBlock::new);

    private final boolean creative;

    public BatteryBlock(Properties properties) {
        this(properties, false);
    }

    protected BatteryBlock(Properties properties, boolean creative) {
        super(properties);
        this.creative = creative;
    }

    public boolean isCreative() {
        return creative;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    // ========== 基类抽象方法实现 ==========

    @Nullable
    @Override
    public BlockEntityType<?> getMachineBlockEntityType() {
        return creative ? ModBlockEntities.CREATIVE_BATTERY.get() : ModBlockEntities.BATTERY.get();
    }

    // ========== 方块实体 ==========

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

    // ========== 渲染 ==========

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // ========== LDLib2 UI ==========

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return BatteryUI.create(holder);
    }

    @Override
    public void openMachineUI(ServerPlayer player, BlockPos pos) {
        BlockUIMenuType.openUI(player, pos);
    }
}
