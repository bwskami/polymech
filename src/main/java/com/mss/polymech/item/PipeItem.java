package com.mss.polymech.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

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
}
