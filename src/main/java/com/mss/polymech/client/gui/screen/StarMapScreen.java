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
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 星系星图 UI 基类（同时也是纯星图界面）。
 * <p>
 * 铺满全屏的 {@link SolarSystemView} 星球渲染 + 顶部半透明信息栏。
 * 默认不包含线框网格、科技网格项、选择框等科技树内容；
 * 科技树界面和传送器界面继承本类后，各自叠加自己的独有内容。
 * </p>
 */
public class StarMapScreen extends ModularUIScreen {

    /** 根节点，供子类追加覆盖层使用。 */
    protected final UIElement root;
    /** 星图/星球视图。 */
    protected final SolarSystemView view;
    /** 纯星图模式下按 M 返回的恒星系索引。 */
    private final int starMapSystemIndex;

    protected StarMapScreen(State s, Component title) {
        this(s, title, s.view.getSystemIndex());
    }

    private StarMapScreen(State s, Component title, int starMapSystemIndex) {
        super(s.ui, title);
        this.root = s.root;
        this.view = s.view;
        this.starMapSystemIndex = starMapSystemIndex;
    }

    /** 打开纯星系星图界面。 */
    public static void open(int currentSystemIndex) {
        int safeIndex = (currentSystemIndex >= 0 && currentSystemIndex < StarSystemCatalog.size()) ? currentSystemIndex : 0;
        var back = new Button()
                .setText(Component.literal("返回"))
                .setOnClick(e -> TechTreeScreen.open(safeIndex))
                .layout(l -> l.height(20).width(72));
        State s = buildState(
                Component.literal("星图 / Galaxy Map"),
                Component.literal("左/右键拖拽旋转 · 中键拖拽平移 · 滚轮缩放 · 单击恒星进入 · M 返回"),
                root -> new SolarSystemView(
                        SolarSystem.createGalaxyMap(),
                        node -> { },
                        pi -> TechTreeScreen.open(pi)),
                back);
        StarMapScreen screen = new StarMapScreen(s, Component.literal("星图 / Galaxy Map"), safeIndex);
        Minecraft.getInstance().setScreen(screen);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_M) {
            onMKeyPressed();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** M 键默认行为：纯星图返回科技树；子类可重写。 */
    protected void onMKeyPressed() {
        TechTreeScreen.open(starMapSystemIndex);
    }

    // ============================ 构建 ============================

    /** 界面构建结果。 */
    protected static final class State {
        ModularUI ui;
        UIElement root;
        SolarSystemView view;
    }

    /** 子类提供 SolarSystemView 的工厂；root 先创建好，方便回调里往 root 上挂覆盖层。 */
    @FunctionalInterface
    protected interface ViewFactory {
        SolarSystemView create(UIElement root);
    }

    /**
     * 构建星图基础 UI。
     *
     * @param title         顶栏标题
     * @param hint          顶栏操作提示
     * @param viewFactory   创建星图视图（{@link SolarSystemView}）
     * @param headerButtons 额外顶栏按钮（会按顺序放在提示文字与“关闭”按钮之间）
     */
    protected static State buildState(Component title, Component hint,
                                      ViewFactory viewFactory,
                                      UIElement... headerButtons) {
        State s = new State();

        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100).flexDirection(FlexDirection.COLUMN));

        var view = viewFactory.create(root);
        // 纯星图基类：默认不绘制、不响应科技树线框网格/网格科技项/选择框；需要科技树覆盖层的子类自行重新开启。
        view.setTechOverlayEnabled(false);
        view.layout(l -> l.widthPercent(100).heightPercent(100));
        root.addChild(view);
        s.view = view;

        // 顶栏（覆盖在球体上方）
        var header = new TopBar();
        header.layout(l -> l.widthPercent(100).height(28)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                .paddingHorizontal(8).flexDirection(FlexDirection.ROW).gapColumn(8));
        var titleLabel = new Label().setText(title).layout(l -> l.flex(1));
        var hintLabel = new Label().setText(hint).layout(l -> l.flex(1));
        var close = new Button()
                .setText(Component.literal("关闭"))
                .setOnClick(e -> Minecraft.getInstance().setScreen(null))
                .layout(l -> l.height(20).width(48));

        List<UIElement> headerChildren = new ArrayList<>();
        headerChildren.add(titleLabel);
        headerChildren.add(hintLabel);
        headerChildren.addAll(List.of(headerButtons));
        headerChildren.add(close);
        header.addChildren(headerChildren.toArray(new UIElement[0]));
        root.addChild(header);

        s.root = root;
        s.ui = ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        return s;
    }

    // ============================ 子控件 ============================

    /** 顶栏半透明深色条。 */
    protected static final class TopBar extends UIElement {
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
