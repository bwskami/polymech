package com.mss.polymech.machine.production;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.client.gui.boiler.HorizontalSteamBoilerUI;
import com.mss.polymech.machine.common.LargeMachineBlock;
import com.mss.polymech.machine.common.MachineConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class HorizontalSteamBoilerBlock extends LargeMachineBlock implements BlockUIMenuType.BlockUI {

    public HorizontalSteamBoilerBlock(MachineConfig config) {
        super(config);
    }

    // ========== 结构 + 侧面方块代理 ==========
    //
    // 形状（side_offsets / fill_regions）与代理语义（item_proxies / fluid_proxies）
    // 已全部搬进 data/poly_mech/machine/horizontal_steam_boiler.json，
    // 由 BaseMachineBlock 按 getMachineId() 查表。要改结构就用游戏内编辑器
    // （/polymech machineeditor horizontal_steam_boiler）改 JSON，不再改这个类。
    //
    // 槽位号参考 HorizontalSteamBoilerBlockEntity：
    // 0=水桶输入, 1=空桶输出, 2=燃料, 3=空桶/蒸汽桶输入, 4=蒸汽桶输出, 5=灰烬输出
    // 储罐索引见 AbstractSteamBoilerBlockEntity：0=水输入罐(TANK_WATER), 1=蒸汽输出罐(TANK_STEAM)

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        return HorizontalSteamBoilerUI.create(holder);
    }

    @Override
    public void openMachineUI(ServerPlayer player, BlockPos pos) {
        BlockUIMenuType.openUI(player, pos);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide()) {
            BlockUIMenuType.openUI((net.minecraft.server.level.ServerPlayer) player, pos);
        }
        return InteractionResult.SUCCESS;
    }
}
