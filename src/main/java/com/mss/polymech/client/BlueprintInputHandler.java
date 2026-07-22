package com.mss.polymech.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.gui.screen.MultiblockSelectionScreen;
import com.mss.polymech.item.BlueprintToolItem;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class BlueprintInputHandler {

    // 创建快捷键映射，用于打开多方块机器选择菜单
    public static final KeyMapping OPEN_MULTIBLOCK_MENU_KEY = new KeyMapping(
            "key.poly_mech.open_multiblock_menu",           // 键位描述语言键
            KeyConflictContext.IN_GAME,                     // 键位冲突上下文（在游戏中可用）
            InputConstants.Type.KEYSYM,                     // 输入类型（键盘按键）
            GLFW.GLFW_KEY_B,                               // 默认按键（B键）
            "key.categories.misc"                           // 键位分类
    );

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();

        if (OPEN_MULTIBLOCK_MENU_KEY.consumeClick()) {
            if (mc.player != null && mc.player.getMainHandItem().getItem() instanceof BlueprintToolItem) {
                openMultiblockSelectionMenu(mc);
            }
        }
    }

    private static void openMultiblockSelectionMenu(Minecraft mc) {
        // 打开多方块机器选择GUI
        mc.execute(() -> mc.setScreen(new MultiblockSelectionScreen()));
    }
}
