package com.mss.polymech.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.gui.screen.TechTreeScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 科技树按键：游戏中按 K 打开科技树界面。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class TechTreeInputHandler {

    public static final net.minecraft.client.KeyMapping TECH_TREE_KEY = new net.minecraft.client.KeyMapping(
            "key.poly_mech.tech_tree",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "key.categories.misc"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(TECH_TREE_KEY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (TECH_TREE_KEY.consumeClick()) {
            if (Minecraft.getInstance().screen == null) {
                TechTreeScreen.open();
            }
        }
    }
}
