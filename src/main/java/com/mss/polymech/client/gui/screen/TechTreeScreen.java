package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mss.polymech.client.gui.widget.planet.SolarSystem;
import com.mss.polymech.client.gui.widget.planet.SolarSystemView;
import com.mss.polymech.techtree.TechNode;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 科技树全屏界面（LDLib2 ModularUIScreen）。
 * <p>
 * 继承 {@link StarMapScreen} 的纯星图部分（全屏 3D 太阳系 + 顶栏），
 * 再叠加科技树特有的网格科技项、选择框与“思索”面板。
 * 操作逻辑：左键/右键长按拖拽旋转视角，中键长按拖拽进入并移动自由视角，滚轮缩放，左键单点星球锁定摄像机；
 * 悬停高亮 + 名称提示，点击有科技的面对应节点打开“思索”面板。
 * </p>
 */
public class TechTreeScreen extends StarMapScreen {

    /** 遮罩层固定 id，用于点击节点时先移除旧面板再开新面板。 */
    private static final String ID_PONDER = "ponder_overlay";

    private TechTreeScreen(State s, Component title) {
        super(s, title);
    }

    /** M 键打开纯星图。 */
    @Override
    protected void onMKeyPressed() {
        StarMapScreen.open(view.getSystemIndex());
    }

    /** 打开科技树（客户端调用，默认太阳系）。 */
    public static void open() {
        open(0);
    }

    /** 打开科技树并直接进入指定恒星系。 */
    public static void open(int systemIndex) {
        State s = buildState(systemIndex);
        TechTreeScreen screen = new TechTreeScreen(s, Component.literal("科技树 / Tech Tree"));
        Minecraft.getInstance().setScreen(screen);
    }

    // ============================ 构建 ============================

    private static State buildState(int initialSystemIndex) {
        State s = buildState(
                Component.literal("科技树 / Tech Tree"),
                Component.literal("左/右键拖动旋转 · 中键拖动自由视角 · 滚轮缩放 · 左键单击星球锁定 · 单击地块查看思索 · M 星图"),
                root -> {
                    Consumer<TechNode> onSelect = node -> {
                        root.selectId(ID_PONDER).collect(Collectors.toList()).forEach(UIElement::removeSelf);
                        root.addChild(buildPonderOverlay(node, root));
                        root.markTaffyStyleDirty();
                    };
                    return new SolarSystemView(SolarSystem.createDefault(), onSelect);
                });
        s.view.enterSystem(initialSystemIndex);
        // 科技树覆盖层：网格科技项 + 选择框（基类默认已关闭）。
        s.view.setTechOverlayEnabled(true);
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
