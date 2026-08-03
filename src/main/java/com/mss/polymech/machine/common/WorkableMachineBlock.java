package com.mss.polymech.machine.common;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.client.gui.machine.ProcessingMachineUI;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 通用加工机器方块：右键打开 LDLib2 UI。
 * <p>
 * 通过 {@code MachineConfig.builder(...).blockFactory(WorkableMachineBlock::new)}
 * 注入到任意大型机器，无需为每台机器单独建 Block 子类。
 * UI 内容根据方块实体的槽位/储罐布局自动生成。
 * </p>
 */
public class WorkableMachineBlock extends LargeMachineBlock implements BlockUIMenuType.BlockUI {

    public WorkableMachineBlock(MachineConfig config) {
        super(config);
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return ProcessingMachineUI.create(holder);
    }

    @Override
    public void openMachineUI(ServerPlayer player, BlockPos pos) {
        BlockUIMenuType.openUI(player, pos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockUIMenuType.openUI((ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }
}
