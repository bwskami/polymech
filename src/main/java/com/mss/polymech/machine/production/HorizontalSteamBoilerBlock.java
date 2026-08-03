package com.mss.polymech.machine.production;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.client.gui.boiler.HorizontalSteamBoilerUI;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.boiler.AbstractSteamBoilerBlockEntity;
import com.mss.polymech.machine.common.LargeMachineBlock;
import com.mss.polymech.machine.common.MachineConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
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

    // ========== 侧面方块代理配置 ==========

    /**
     * 物品代理声明（本地坐标，facing=NORTH）：位置 → 槽位 + IO 方向。
     * 槽位号参考 HorizontalSteamBoilerBlockEntity：
     * 0=水桶输入, 1=空桶输出, 2=燃料, 3=空桶/蒸汽桶输入, 4=蒸汽桶输出, 5=灰烬输出
     */
    @Override
    public ItemProxy getItemProxy(Vec3i relativeOffset) {
        if (relativeOffset.equals(new Vec3i(0, 1, -2))) return new ItemProxy(new int[]{2}, ProxyIO.INPUT);   // 燃料输入
        if (relativeOffset.equals(new Vec3i(0, 0, -2))) return new ItemProxy(new int[]{5}, ProxyIO.OUTPUT);  // 灰烬输出
        return null;
    }

    /**
     * 流体代理声明（本地坐标，facing=NORTH）：位置 → 储罐 + IO 方向。
     * 储罐索引见 {@link AbstractSteamBoilerBlockEntity}：0=水输入罐，1=蒸汽输出罐
     */
    @Override
    public FluidProxy getFluidProxy(Vec3i relativeOffset) {
        if (relativeOffset.equals(new Vec3i(0, 0, 1))) return new FluidProxy(new int[]{AbstractSteamBoilerBlockEntity.TANK_WATER}, ProxyIO.INPUT);   // 水输入
        if (relativeOffset.equals(new Vec3i(0, 3, 0))) return new FluidProxy(new int[]{AbstractSteamBoilerBlockEntity.TANK_STEAM}, ProxyIO.OUTPUT);  // 蒸汽输出
        return null;
    }

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
