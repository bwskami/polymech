package com.mss.polymech.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.gui.screen.SpaceNavScreen;
import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 宇宙导航星图按键：太空维度内按 N 打开 {@link SpaceNavScreen}。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class SpaceNavInputHandler {

    public static final KeyMapping SPACE_NAV_KEY = new KeyMapping(
            "key.poly_mech.space_nav",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.misc"
    );

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SPACE_NAV_KEY);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        if (SPACE_NAV_KEY.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null && mc.level != null
                    && PlanetDimensions.SPACE.equals(mc.level.dimension())) {
                SpaceNavScreen.open();
            }
        }
    }
}
