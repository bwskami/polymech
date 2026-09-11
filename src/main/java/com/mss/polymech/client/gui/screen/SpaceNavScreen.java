package com.mss.polymech.client.gui.screen;

import com.mss.polymech.client.gui.widget.planet.NavSolarSystemView;
import com.mss.polymech.client.gui.widget.planet.SolarSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 宇宙空间导航星图：太空维度内按 N 打开。
 * 在纯星图上叠加玩家当前位置标记（绿色菱形，见 {@link NavSolarSystemView}），
 * 用于确认"我在哪、离各星球多远"，配合传送器快速导航。
 */
public class SpaceNavScreen extends StarMapScreen {

    private SpaceNavScreen(State s, Component title) {
        super(s, title);
    }

    public static void open() {
        State s = buildState(
                Component.literal("宇宙导航图 / Space Nav"),
                Component.literal("左/右键拖拽旋转 · 中键平移 · 滚轮缩放 · 绿色菱形=当前位置 · M/ESC 关闭"),
                root -> new NavSolarSystemView(SolarSystem.createDefault(), node -> { }));
        SpaceNavScreen screen = new SpaceNavScreen(s, Component.literal("宇宙导航图 / Space Nav"));
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    protected void onMKeyPressed() {
        Minecraft.getInstance().setScreen(null);
    }
}
