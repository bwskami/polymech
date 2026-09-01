package com.mss.polymech.item;

import com.mss.polymech.client.gui.screen.TeleporterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 星际传送器：没有火箭前的临时跨星球传送工具。
 * <p>
 * 手持右键打开继承自 {@code StarMapScreen} 的 {@link TeleporterScreen}，
 * 在星球界面中单击选择目标星球，再点“传送”即可跳转到对应维度。
 * </p>
 */
public class TeleporterItem extends Item {

    public TeleporterItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            Minecraft.getInstance().execute(TeleporterScreen::open);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
