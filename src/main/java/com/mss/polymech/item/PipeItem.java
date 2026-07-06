package com.mss.polymech.item;

import com.mss.polymech.block.ModBlocks;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.UseOnContext;

public class PipeItem extends BlockItem {
    public PipeItem(Properties properties) {
        super(ModBlocks.PIPE.get(), properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        
        // Shift + 右键 = 普通放置
        if (player.isShiftKeyDown()) {
            return super.useOn(context);
        }
        
        // 普通右键 = 管道铺设模式（由客户端 PipeInputHandler 处理）
        // 服务端不需要做任何事，返回 PASS 让客户端处理
        if (!context.getLevel().isClientSide()) {
            return InteractionResult.PASS;
        }

        // 客户端返回 SUCCESS 表示我们处理了这个交互
        return InteractionResult.SUCCESS;
    }
}
