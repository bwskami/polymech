package com.mss.polymech.item;

import com.mss.polymech.network.ClampMeterMeasurementPacket;
import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.ClampMeterTargetCache;
import com.mss.polymech.powergrid.WireTargeting;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 钳形表（万用表）。
 * <p>
 * 对准电线右键即可测量该电线最近一次电网分配计算出的实际电压/电流/功率。
 * 没有 Geo 模型与动画，使用普通扁平贴图。
 * </p>
 */
public class ClampMeterItem extends Item {

    public ClampMeterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return measure(context.getLevel(), context.getPlayer(), context.getHand());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        InteractionResult result = measure(level, player, hand);
        return result == InteractionResult.SUCCESS
                ? InteractionResultHolder.success(stack)
                : InteractionResultHolder.pass(stack);
    }

    private InteractionResult measure(Level level, Player player, InteractionHand hand) {
        if (player == null)
            return InteractionResult.PASS;

        if (level.isClientSide()) {
            return ClampMeterTargetCache.getClientTarget() != null
                    ? InteractionResult.SUCCESS
                    : InteractionResult.PASS;
        }

        WorldPowerGrid grid = WorldPowerGrid.get((ServerLevel) level);
        GridConnection target = WireTargeting.findTarget(level, player, grid.getAllConnections());
        if (target == null)
            return InteractionResult.PASS;

        PacketDistributor.sendToPlayer((ServerPlayer) player, new ClampMeterMeasurementPacket(
                target, grid.getConnectionCurrent(target), grid.getConnectionVoltage(target)));
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.poly_mech.clamp_meter")
                .withStyle(ChatFormatting.GRAY));
    }
}
