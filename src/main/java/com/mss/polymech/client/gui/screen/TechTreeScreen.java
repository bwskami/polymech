package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mss.polymech.techtree.TechNode;
import com.mss.polymech.techtree.TechTree;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 科技树全屏界面（LDLib2 ModularUIScreen）。
 * <p>
 * 结构：header（标题/提示/关闭） + viewport（裁剪容器） + world（可拖拽平移的世界层）。
 * world 内含 {@link ConnectorLayer}（连线，置于最底）与若干机器/科技节点卡片。
 * 点击节点卡片打开“思索”面板（{@link #buildPonderOverlay}），即类 Create 的 ponder 入口。
 * </p>
 */
public class TechTreeScreen extends ModularUIScreen {

    /** 遮罩层固定 id，用于点击节点时先移除旧面板再开新面板。 */
    private static final String ID_PONDER = "ponder_overlay";

    private final UIElement root;
    private final UIElement viewport;
    private final UIElement world;
    private final Map<String, UIElement> nodeCards = new LinkedHashMap<>();

    private TechTreeScreen(State s, Component title) {
        super(s.ui, title);
        this.root = s.root;
        this.viewport = s.viewport;
        this.world = s.world;
        this.nodeCards.putAll(s.nodeCards);
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
        UIElement viewport;
        UIElement world;
        Map<String, UIElement> nodeCards = new LinkedHashMap<>();
    }

    private static State buildState() {
        State s = new State();

        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100).flexDirection(FlexDirection.COLUMN));
        root.addClass("panel_bg");

        // 顶栏
        var header = new UIElement();
        header.layout(l -> l.widthPercent(100).height(28).paddingHorizontal(8).flexDirection(FlexDirection.ROW).gapColumn(8));
        header.addClass("panel_bg");

        var title = new Label().setText(Component.literal("科技树 / Tech Tree")).layout(l -> l.flex(1));
        var hint = new Label().setText(Component.literal("拖拽平移 · 点击节点查看思索")).layout(l -> l.marginRight(8));
        var close = new Button()
                .setText(Component.literal("关闭"))
                .setOnClick(e -> Minecraft.getInstance().setScreen(null))
                .layout(l -> l.height(20).width(48));
        header.addChildren(title, hint, close);

        // 视口（裁剪 + 作为绝对定位容器）
        var viewport = new UIElement();
        viewport.layout(l -> l.widthPercent(100).flex(1).positionType(TaffyPosition.RELATIVE));
        viewport.setOverflowVisible(false);

        // 世界层（可平移）
        TechTree.Layout layout = TechTree.computeLayout();
        var world = new PannableWorld(layout.canvasWidth(), layout.canvasHeight());

        // 连线层（第一个子节点 => 位于节点之下）
        var connector = new ConnectorLayer(s.nodeCards, TechTree.buildEdges());
        connector.layout(l -> l.widthPercent(100).heightPercent(100)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        world.addChild(connector);

        // 节点卡片
        for (TechNode node : TechTree.all()) {
            int[] p = layout.posOf(node.id());
            if (p == null) continue;
            var card = createNodeCard(node, p[0], p[1], root);
            s.nodeCards.put(node.id(), card);
            world.addChild(card);
        }

        viewport.addChild(world);
        root.addChildren(header, viewport);

        s.root = root;
        s.viewport = viewport;
        s.world = world;
        s.ui = ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
        return s;
    }

    private static UIElement createNodeCard(TechNode node, int x, int y, UIElement root) {
        var card = new UIElement();
        card.layout(l -> l.width(TechTree.NODE_W).height(TechTree.NODE_H)
                .positionType(TaffyPosition.ABSOLUTE).left(x).top(y)
                .paddingAll(4).gapColumn(2));
        card.addClass("panel_bg");

        var icon = new ItemIconElement(() -> node.icon(), 32);
        icon.layout(l -> l.width(32).height(32));

        var name = new Label().setText(node.title()).layout(l -> l.widthPercent(100));
        var tag = new Label()
                .setText(Component.literal("T" + node.tier() + " · " + node.category()))
                .layout(l -> l.widthPercent(100));

        card.addChildren(icon, name, tag);

        // 悬停提示
        card.addEventListener(UIEvents.HOVER_TOOLTIPS, e -> {
            List<Component> tips = new ArrayList<>();
            tips.add(node.title());
            if (!node.prerequisites().isEmpty()) {
                tips.add(Component.literal("前置: " + String.join(", ", node.prerequisites())));
            }
            if (node.machineId() != null) {
                tips.add(Component.literal("机器: " + node.machineId()));
            }
            e.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });

        // 点击 -> 打开思索面板
        card.addEventListener(UIEvents.CLICK, e -> {
            root.selectId(ID_PONDER)
                    .collect(java.util.stream.Collectors.toList())
                    .forEach(UIElement::removeSelf);
            root.addChild(buildPonderOverlay(node, root));
            root.markTaffyStyleDirty();
        });

        return card;
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
        overlay.stopInteractionEventsPropagation(); // 阻断底层平移

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

    /** 可拖拽平移的世界层：MOUSE_DOWN 起拖，MOUSE_MOVE 按 delta 平移自身。 */
    private static final class PannableWorld extends UIElement {
        private boolean dragging = false;
        private float panX = 20;
        private float panY = 20;

        PannableWorld(int w, int h) {
            layout(l -> l.width(w).height(h)
                    .positionType(TaffyPosition.ABSOLUTE).left((int) panX).top((int) panY));
            addClass("panel_bg");
            addEventListener(UIEvents.MOUSE_DOWN, e -> dragging = true);
            addEventListener(UIEvents.MOUSE_UP, e -> dragging = false);
            addEventListener(UIEvents.MOUSE_MOVE, e -> {
                if (dragging) {
                    panX += e.deltaX;
                    panY += e.deltaY;
                    layout(l -> l.left((int) panX).top((int) panY));
                    markTaffyStyleDirty();
                }
            });
        }
    }

    /** 连线层：在屏幕绝对坐标中绘制 前置 -> 节点 的折线。 */
    private static final class ConnectorLayer extends UIElement {
        private final Map<String, UIElement> nodeCards;
        private final List<TechTree.Edge> edges;

        ConnectorLayer(Map<String, UIElement> nodeCards, List<TechTree.Edge> edges) {
            this.nodeCards = nodeCards;
            this.edges = edges;
        }

        @Override
        public void drawBackgroundAdditional(GUIContext guiContext) {
            var g = guiContext.graphics;
            for (TechTree.Edge edge : edges) {
                UIElement from = nodeCards.get(edge.from());
                UIElement to = nodeCards.get(edge.to());
                if (from == null || to == null) continue;
                int x1 = (int) (from.getPositionX() + from.getSizeWidth());
                int y1 = (int) (from.getPositionY() + from.getSizeHeight() / 2);
                int x2 = (int) to.getPositionX();
                int y2 = (int) (to.getPositionY() + to.getSizeHeight() / 2);
                drawElbow(g, x1, y1, x2, y2, 0xFF6FB3C8);
            }
        }

        private void drawElbow(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
            int midX = (x1 + x2) / 2;
            g.fill(x1, y1, midX, y1 + 2, color);
            g.fill(midX, Math.min(y1, y2), midX + 2, Math.max(y1, y2), color);
            g.fill(midX, y2, x2, y2 + 2, color);
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
