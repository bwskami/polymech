package com.mss.polymech.machine.production;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.client.gui.boiler.HorizontalSteamBoilerUI;
import com.mss.polymech.machine.common.LargeMachineBlock;
import com.mss.polymech.machine.common.MachineConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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
