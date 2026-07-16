package com.mss.polymech.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

/**
 * 传送带物品。
 * <p>
 * 潜行时正常放置单个方块，非潜行右键由 {@link com.mss.polymech.client.ConveyorInputHandler} 处理连续铺设。
 * </p>
 */
public class ConveyorItem extends BlockItem {
    public ConveyorItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        // 潜行时按默认方块放置行为
        if (player.isShiftKeyDown()) {
            return super.useOn(context);
        }

        // 非潜行：服务端不做任何事（由 ConveyorInputHandler 客户端处理）
        if (!context.getLevel().isClientSide()) {
            return InteractionResult.PASS;
        }

        // 客户端：消费右键事件，由 ConveyorInputHandler 处理铺设逻辑
        return InteractionResult.SUCCESS;
    }
}