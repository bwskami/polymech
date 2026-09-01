package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mss.polymech.client.gui.widget.planet.SolarSystem;
import com.mss.polymech.client.gui.widget.planet.SolarSystemView;
import com.mss.polymech.client.gui.widget.planet.StarSystemCatalog;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 群星式 3D 星图 UI。
 * <p>
 * 把整张星图看作一个“大恒星系”：每个目录恒星是一个带 BASE + ATMOSPHERE 图层的
 * {@code Planet}（{@code PlanetVisual.star(...)}），直接复用 {@code SolarSystemView}
 * 的星球渲染管线、着色器与图层系统，不重复造轮子。
 * </p>
 */
public class StarMapScreen extends ModularUIScreen {

    private final int currentSystemIndex;

    private StarMapScreen(int currentSystemIndex, ModularUI ui) {
        super(ui, Component.literal("星图 / Galaxy Map"));
        this.currentSystemIndex = currentSystemIndex;
    }

    /** 打开星图。currentSystemIndex 用于按 M 返回当前星系。 */
    public static void open(int currentSystemIndex) {
        int safeIndex = (currentSystemIndex >= 0 && currentSystemIndex < StarSystemCatalog.size()) ? currentSystemIndex : 0;
        StarMapScreen screen = new StarMapScreen(safeIndex, buildUI(safeIndex));
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_M) {
            TechTreeScreen.open(currentSystemIndex);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ============================ 构建 ============================

    private static ModularUI buildUI(int currentSystemIndex) {
        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100).flexDirection(FlexDirection.COLUMN));

        // 核心：直接复用 SolarSystemView 的星球渲染。星图 = 大恒星系。
        var map = new SolarSystemView(
                SolarSystem.createGalaxyMap(),
                node -> { },
                pi -> TechTreeScreen.open(pi));
        map.layout(l -> l.widthPercent(100).heightPercent(100));
        root.addChild(map);

        var header = new TopBar();
        header.layout(l -> l.widthPercent(100).height(28)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                .paddingHorizontal(8).flexDirection(FlexDirection.ROW).gapColumn(8));
        var title = new Label().setText(Component.literal("星图 / Galaxy Map")).layout(l -> l.flex(1));
        var hint = new Label().setText(Component.literal("左/右键拖拽旋转 · 中键拖拽平移 · 滚轮缩放 · 单击恒星进入 · M 返回星系")).layout(l -> l.flex(1));
        var back = new Button()
                .setText(Component.literal("返回星系"))
                .setOnClick(e -> TechTreeScreen.open(currentSystemIndex))
                .layout(l -> l.height(20).width(72));
        var close = new Button()
                .setText(Component.literal("关闭"))
                .setOnClick(e -> Minecraft.getInstance().setScreen(null))
                .layout(l -> l.height(20).width(48));
        header.addChildren(title, hint, back, close);
        root.addChild(header);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    // ============================ 子控件 ============================

    /** 顶栏半透明深色条。 */
    private static final class TopBar extends UIElement {
        @Override
        public void drawBackgroundAdditional(GUIContext guiContext) {
            var g = guiContext.graphics;
            int x = (int) getPositionX();
            int y = (int) getPositionY();
            int w = (int) getSizeWidth();
            int h = (int) getSizeHeight();
            g.fill(x, y, x + w, y + h, 0xAA000000);
        }
    }
}
