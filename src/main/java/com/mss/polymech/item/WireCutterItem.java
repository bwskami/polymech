package com.mss.polymech.item;

import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.WireTargetCache;
import com.mss.polymech.powergrid.WireTargeting;
import com.mss.polymech.powergrid.WorldPowerGrid;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 剪线钳。
 * <p>
 * 瞄准世界中的真实电线时，客户端会显示描边高亮与类似机械动力工程师护目镜的
 * 屏幕信息面板；右键瞄准的电线即可剪断该连接。
 * 交互由 {@code WireTargeting} 做视线-弧垂路径命中检测，服务端负责真正断开。
 * </p>
 */
public class WireCutterItem extends Item {

    public WireCutterItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return tryCutOnBlock(context.getLevel(), context.getPlayer(), context.getHand());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player == null)
            return InteractionResultHolder.pass(stack);

        if (level.isClientSide()) {
            return WireTargetCache.getClientTarget() != null
                    ? InteractionResultHolder.success(stack)
                    : InteractionResultHolder.pass(stack);
        }

        WorldPowerGrid grid = WorldPowerGrid.get((ServerLevel) level);
        GridConnection target = WireTargeting.findTarget(level, player, grid.getAllConnections());
        if (target == null)
            return InteractionResultHolder.pass(stack);

        cut(level, player, hand, target);
        return InteractionResultHolder.success(stack);
    }

    /** 右键点击方块时优先处理剪线，避免打开背后方块的 GUI */
    private InteractionResult tryCutOnBlock(Level level, @Nullable Player player, InteractionHand hand) {
        if (player == null)
            return InteractionResult.PASS;

        if (level.isClientSide()) {
            return WireTargetCache.getClientTarget() != null ? InteractionResult.SUCCESS : InteractionResult.PASS;
        }

        WorldPowerGrid grid = WorldPowerGrid.get((ServerLevel) level);
        GridConnection target = WireTargeting.findTarget(level, player, grid.getAllConnections());
        if (target == null)
            return InteractionResult.PASS;

        cut(level, player, hand, target);
        return InteractionResult.SUCCESS;
    }

    private void cut(Level level, Player player, InteractionHand hand, GridConnection target) {
        WorldPowerGrid grid = WorldPowerGrid.get((ServerLevel) level);
        grid.disconnect(target.node1(), target.node2());
        player.displayClientMessage(Component.translatable("message.poly_mech.wire_cutter.cut"), true);
        player.playSound(SoundEvents.SHEEP_SHEAR, 0.8F, 1.2F);
        player.swing(hand);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.poly_mech.wire_cutter")
                .withStyle(ChatFormatting.GRAY));
    }
}
