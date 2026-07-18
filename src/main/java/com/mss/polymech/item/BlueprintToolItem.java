package com.mss.polymech.item;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;

public class BlueprintToolItem extends Item {
    public BlueprintToolItem(Properties properties) {
        super(properties.stacksTo(1)); // 工具通常只堆叠到1
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();

        if (player == null) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) {
            // 在客户端侧触发UI界面（如打开多方块机器选择菜单）
            openMultiblockSelectionMenu(player);
            return InteractionResult.SUCCESS;
        }

        // 在服务端侧处理多方块机器的建造逻辑
        // 这里暂时返回成功，具体实现将在后续步骤中添加
        return InteractionResult.SUCCESS;
    }

    /**
     * 在客户端打开多方块机器选择菜单
     */
    private void openMultiblockSelectionMenu(Player player) {
        // 此处将在后续实现中打开GUI菜单，显示可建造的多方块机器
        // 目前仅做占位实现
        Polymech.LOGGER.info("Opening multiblock selection menu for player: {}", player.getName().getString());
    }
}
