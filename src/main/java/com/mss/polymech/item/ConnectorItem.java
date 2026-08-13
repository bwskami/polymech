package com.mss.polymech.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

import java.util.List;

/**
 * 连接器物品。
 * <p>
 * 在普通 BlockItem 基础上添加说明性 tooltip：
 * 连接器是电网的接入点，可用线轴拉线接入电网，
 * 与蓄电池等电气设备互动传输电量。
 * </p>
 */
public class ConnectorItem extends BlockItem {

    public ConnectorItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.poly_mech.connector.node")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.poly_mech.connector.stack")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.poly_mech.connector.wire")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
