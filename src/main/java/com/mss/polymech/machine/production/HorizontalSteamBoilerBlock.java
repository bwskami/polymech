package com.mss.polymech.machine.production;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.client.gui.block.HorizontalSteamBoilerUI;
import com.mss.polymech.machine.common.LargeMachineBlock;
import com.mss.polymech.machine.common.MachineConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class HorizontalSteamBoilerBlock extends LargeMachineBlock implements BlockUIMenuType.BlockUI {

    public HorizontalSteamBoilerBlock(MachineConfig config) {
        super(config);
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return HorizontalSteamBoilerUI.create(holder);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockUIMenuType.openUI((net.minecraft.server.level.ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }
}
