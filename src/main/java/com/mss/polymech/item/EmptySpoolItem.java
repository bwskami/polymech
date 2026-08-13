package com.mss.polymech.item;

import com.mss.polymech.powergrid.ConnectorBlock;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 空线轴：拆线工具。
 * <p>
 * 右键连接器（唯一接线端子）可断开该节点上的全部电线连接，
 * 用于拆除废弃线路或调整电网拓扑。
 * </p>
 */
public class EmptySpoolItem extends Item {

    public EmptySpoolItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (context.getPlayer() == null)
            return InteractionResult.PASS;
        if (!(level.getBlockState(context.getClickedPos()).getBlock() instanceof ConnectorBlock))
            return InteractionResult.PASS;

        // 客户端放行，服务端执行断开
        if (level.isClientSide())
            return InteractionResult.SUCCESS;

        WorldPowerGrid grid = WorldPowerGrid.get((ServerLevel) level);
        int count = grid.countConnectionsAt(context.getClickedPos());
        grid.removeNode(context.getClickedPos());
        context.getPlayer().displayClientMessage(
                count > 0
                        ? Component.translatable("message.poly_mech.empty_spool.disconnected", count)
                        : Component.translatable("message.poly_mech.empty_spool.no_wire"),
                true);
        return InteractionResult.SUCCESS;
    }
}
