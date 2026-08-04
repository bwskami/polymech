package com.mss.polymech.item;

import com.mss.polymech.block.PipeBlock;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class PipeItem extends BlockItem {
    public PipeItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        
        if (player.isShiftKeyDown()) {
            return super.useOn(context);
        }
        
        if (!context.getLevel().isClientSide()) {
            return InteractionResult.PASS;
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        // 流速 = 尺寸基准流速 × 材质乘数
        if (getBlock() instanceof PipeBlock pipe) {
            int rate = pipe.getPipeSize().getThroughput(pipe.getPipeMaterial());
            tooltipComponents.add(Component.literal("流速: " + rate + " mB/t").withStyle(ChatFormatting.GRAY));
        }
    }
}
