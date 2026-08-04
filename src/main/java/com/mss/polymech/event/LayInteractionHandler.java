package com.mss.polymech.event;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.ConveyorItem;
import com.mss.polymech.item.PipeItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 铺设交互拦截。
 * <p>
 * 手持管道/传送带且非潜行时，右键目标方块不触发其自身激活
 * （避免对着箱子/机器铺设时误打开 GUI）。
 * 潜行时保持原版行为（正常放置方块/打开 GUI）。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID)
public class LayInteractionHandler {

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity().isShiftKeyDown()) return;

        Item held = event.getItemStack().getItem();
        if (!(held instanceof PipeItem) && !(held instanceof ConveyorItem)) return;

        // 铺设模式：阻止被点击方块激活（客户端与服务端都拦截，防止 GUI 打开）
        event.setUseBlock(TriState.FALSE);
    }
}
