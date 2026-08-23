package com.mss.polymech.item;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.client.gui.prospector.ProspectorUI;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/*
 * 探矿仪：格雷式勘探工具。
 * <p>
 * 右键打开勘探地图GUI：以玩家所在区块为中心，扫描5×5区块范围内的
 * 岩石类型（噪声确定性计算）与矿物矿石（方块扫描），
 * 以彩色网格形式展示"看岩认矿"的地下地质信息。
 * </p>
 */
public class ProspectorItem extends Item implements HeldItemUIMenuType.HeldItemUI {

    public ProspectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            HeldItemUIMenuType.openUI(serverPlayer, hand);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return ProspectorUI.create(holder);
    }
}
