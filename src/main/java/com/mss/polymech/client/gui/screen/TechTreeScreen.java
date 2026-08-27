package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mss.polymech.client.gui.widget.PolyhedronView;
import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;
import com.mss.polymech.techtree.TechTree;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 科技树全屏界面（LDLib2 ModularUIScreen）。
 * <p>
 * 主视觉为一个自转的“地块球”（{@link PolyhedronView}）：正六边形 + 12 五边形拼接，
 * 每个地块 = 一个科技；纯黑背景、线框显示但正面实体遮挡背面、科技地块用程序化六边形图标。
 * 拖拽旋转、悬停高亮 + 名称提示、点击有科技的面对应节点打开“思索”面板。
 * </p>
 */
public class TechTreeScreen extends ModularUIScreen {

    /** 遮罩层固定 id，用于点击节点时先移除旧面板再开新面板。 */
    private static final String ID_PONDER = "ponder_overlay";

    private final UIElement root;

    private TechTreeScreen(State s, Component title) {
        super(s.ui, title);
        this.root = s.root;
    }

    /** 打开科技树（客户端调用）。 */
    public static void open() {
        State s = buildState();
        TechTreeScreen screen = new TechTreeScreen(s, Component.literal("科技树 / Tech Tree"));
        Minecraft.getInstance().setScreen(screen);
    }

    // ============================ 构建 ============================

    private static final class State {
        ModularUI ui;
        UIElement root;
    }

    private static State buildState() {
        State s = new State();

        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100).flexDirection(FlexDirection.COLUMN));

        // 外壳（六边形+五边形线框）+ 内核（类地星球），铺满整屏，自带纯黑背景
        Polyhedron shell = Polyhedron.goldberg(2);
        Polyhedron atmosphere = Polyhedron.sphere(24, 32);
        List<TechNode> nodes = TechTree.all();
        Consumer<TechNode> onSelect = node -> {
            root.selectId(ID_PONDER).collect(Collectors.toList()).forEach(UIElement::removeSelf);
            root.addChild(buildPonderOverlay(node, root));
            root.markTaffyStyleDirty();
        };
        var view = new PolyhedronView(shell, atmosphere, nodes, onSelect);
        view.layout(l -> l.widthPercent(100).heightPercent(100));
        root.addChild(view);

        // 顶栏（覆盖在球体上方）
        var header = new TopBar();
        header.layout(l -> l.widthPercent(100).height(28)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0)
                .paddingHorizontal(8).flexDirection(FlexDirection.ROW).gapColumn(8));
        var title = new Label().setText(Component.literal("科技树 / Tech Tree")).layout(l -> l.flex(1));
        var hint = new Label().setText(Component.literal("拖拽旋转 · 点击科技地块查看思索")).layout(l -> l.marginRight(8));
        var close = new Button()
                .setText(Component.literal("关闭"))
                .setOnClick(e -> Minecraft.getInstance().setScreen(null))
                .layout(l -> l.height(20).width(48));
        header.addChildren(title, hint, close);
        root.addChild(header);

        s.root = root;
        s.ui = ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        return s;
    }

    // ============================ 思索面板 ============================

    private static UIElement buildPonderOverlay(TechNode node, UIElement root) {
        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int pw = 380, ph = 460;
        int px = Math.max(8, (sw - pw) / 2);
        int py = Math.max(8, (sh - ph) / 2);

        var overlay = new UIElement();
        overlay.setId(ID_PONDER);
        overlay.layout(l -> l.widthPercent(100).heightPercent(100)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        overlay.stopInteractionEventsPropagation(); // 阻断底层旋转

        var dim = new DimLayer();
        dim.layout(l -> l.widthPercent(100).heightPercent(100)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        overlay.addChild(dim);

        var panel = new UIElement();
        panel.layout(l -> l.width(pw).height(ph)
                .positionType(TaffyPosition.ABSOLUTE).left(px).top(py)
                .paddingAll(10).gapColumn(6));
        panel.addClass("panel_bg");

        var icon = new ItemIconElement(() -> node.icon(), 48);
        icon.layout(l -> l.width(48).height(48));

        panel.addChild(icon);
        panel.addChild(new Label().setText(node.title()).layout(l -> l.widthPercent(100)));

        if (!node.description().isEmpty()) {
            panel.addChild(new Label().setText(Component.literal("说明")).layout(l -> l.widthPercent(100)));
            for (var d : node.description()) {
                panel.addChild(new Label().setText(d).layout(l -> l.widthPercent(100)));
            }
        }
        if (!node.prerequisites().isEmpty()) {
            panel.addChild(new Label().setText(Component.literal("前置科技: " + String.join(", ", node.prerequisites())))
                    .layout(l -> l.widthPercent(100)));
        }
        if (!node.steps().isEmpty()) {
            panel.addChild(new Label().setText(Component.literal("思索步骤")).layout(l -> l.widthPercent(100)));
            int i = 1;
            for (var st : node.steps()) {
                panel.addChild(new Label().setText(Component.literal((i++) + ". " + st.getString()))
                        .layout(l -> l.widthPercent(100)));
            }
        }
        if (node.machineId() != null) {
            panel.addChild(new Label().setText(Component.literal("对应机器: " + node.machineId()))
                    .layout(l -> l.widthPercent(100)));
        }

        var close = new Button()
                .setText(Component.literal("关闭"))
                .setOnClick(e -> overlay.removeSelf())
                .layout(l -> l.height(20).width(80));
        panel.addChild(close);

        overlay.addChild(panel);
        return overlay;
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

    /** 半透明遮罩层。 */
    private static final class DimLayer extends UIElement {
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

    /** 物品图标绘制（按尺寸缩放渲染）。 */
    private static final class ItemIconElement extends UIElement {
        private final java.util.function.Supplier<ItemStack> stack;
        private final int size;

        ItemIconElement(java.util.function.Supplier<ItemStack> stack, int size) {
            this.stack = stack;
            this.size = size;
        }

        @Override
        public void drawBackgroundAdditional(GUIContext guiContext) {
            ItemStack s = stack.get();
            if (s == null || s.isEmpty()) return;
            var g = guiContext.graphics;
            int x = (int) getPositionX();
            int y = (int) getPositionY();
            int w = (int) getSizeWidth();
            int h = (int) getSizeHeight();
            int iconSize = Math.min(w, h);
            int ox = x + (w - iconSize) / 2;
            int oy = y + (h - iconSize) / 2;
            float scale = iconSize / 16f;
            g.pose().pushPose();
            g.pose().translate(ox, oy, 0);
            g.pose().scale(scale, scale, 1);
            g.renderItem(s, 0, 0);
            g.pose().popPose();
        }
    }
}
