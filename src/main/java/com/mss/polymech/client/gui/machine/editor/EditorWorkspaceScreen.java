package com.mss.polymech.client.gui.machine.editor;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.mss.polymech.machine.BaseMachineBlock;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑器窗口 —— <b>整个编辑器只有这一个 Screen</b>，照浏览器的模型：
 *
 * <pre>
 * EditorWorkspaceScreen
 *   ├── ws_tabs     唯一的标签栏（标签 + ＋ + 关闭）
 *   └── ws_content  当前标签的内容
 * </pre>
 *
 * <h3>标签是「槽位」，内容是「可替换」的</h3>
 * 这就是浏览器的核心行为：在新标签页（主菜单）里点一个机器，<b>当前这个标签自己导航过去</b>，
 * 变成那个项目 —— 主菜单不会额外留一份。所以：
 *
 * <ul>
 *   <li><b>＋</b> = 开一个新的空白标签（内容是主菜单）；</li>
 *   <li>在主菜单里点机器 = <b>当前标签导航</b>成那个项目（标签数不变）；</li>
 *   <li>如果那个项目已经在别的标签里开着 = 切过去，不开第二份；</li>
 *   <li>关掉最后一个标签 = 自动留一个空白标签（窗口不会空）。</li>
 * </ul>
 *
 * <p>切标签只换 {@code ws_content} 挂的子元素，<b>不重建 Screen</b>，
 * 所以 3D 场景、相机、滚动位置都保留。</p>
 */
public class EditorWorkspaceScreen extends ModularUIScreen {

    public static final String TAB_STRIP_ID = "ws_tabs";
    public static final String CONTENT_ID = "ws_content";

    /** 一个标签位。内容可以被替换（主菜单 → 项目），就像浏览器导航。 */
    private static final class TabSlot {
        final int slotId;
        EditorTab content;

        TabSlot(int slotId, EditorTab content) {
            this.slotId = slotId;
            this.content = content;
        }
    }

    private final List<TabSlot> tabs = new ArrayList<>();
    private TabSlot active;
    private int nextSlotId = 1;
    /** init 之前请求打开的（{@code setScreen} 之前就调 openMachine 的情况）。 */
    private Runnable pendingOnInit;
    private boolean initialized;

    public EditorWorkspaceScreen() {
        super(buildShell(), Component.literal("机器结构编辑器"));
    }

    @Override
    public void init() {
        super.init();
        initialized = true;
        if (pendingOnInit != null) {
            var r = pendingOnInit;
            pendingOnInit = null;
            r.run();
        }
        if (tabs.isEmpty()) newTab();
        else renderAll();
    }

    // ==================== 导航 ====================

    /**
     * 打开一个机器项目（浏览器的"导航到某地址"）。
     *
     * <p>顺序：已开着就切过去 → 当前标签是空白的就<b>原地导航</b> → 否则开新标签。</p>
     */
    public void openMachine(String machineId, BaseMachineBlock block) {
        if (machineId == null || block == null) return;
        if (!initialized) {
            pendingOnInit = () -> openMachine(machineId, block);
            return;
        }
        var existing = findMachineSlot(machineId);
        if (existing != null) {
            activate(existing);
            return;
        }
        var tab = new MachineTab(this, machineId, block);
        // 当前停在空白页（主菜单）→ 这个标签自己导航过去，不留主菜单
        if (active != null && active.content instanceof PickerTab) {
            active.content = tab;
            mountContent();
            tab.onShown();
            renderTabStrip();
            return;
        }
        addTab(tab);
    }

    /** 打开主菜单：已有空白标签就切过去，否则开一个。 */
    public void openPicker() {
        if (!initialized) {
            pendingOnInit = this::openPicker;
            return;
        }
        for (var slot : tabs) {
            if (slot.content instanceof PickerTab) {
                activate(slot);
                return;
            }
        }
        newTab();
    }

    /** 「＋」：永远开一个新的空白标签。 */
    public void newTab() {
        if (!initialized) {
            pendingOnInit = this::newTab;
            return;
        }
        addTab(new PickerTab(this));
    }

    private void addTab(EditorTab content) {
        var slot = new TabSlot(nextSlotId++, content);
        tabs.add(slot);
        activate(slot);
    }

    /** 切到某个标签。 */
    private void activate(TabSlot slot) {
        if (slot == null) return;
        if (slot == active) {
            renderTabStrip();
            return;
        }
        if (active != null) active.content.onHidden();
        active = slot;
        mountContent();
        active.content.onShown();
        renderTabStrip();
    }

    /** 关掉一个标签；关光了自动补一个空白标签。 */
    public void closeTab(int slotId) {
        var slot = findBySlot(slotId);
        if (slot == null || !slot.content.closable()) return;
        // 关标签是"丢弃"：先让内容确认（未保存会弹窗），确认后再真删
        slot.content.requestClose(() -> doClose(slot));
    }

    private void doClose(TabSlot slot) {
        slot.content.onClosed();
        // 只有机器项目才需要丢草稿与历史（空白页没有这些）
        if (slot.content instanceof MachineTab machine) {
            EditorDrafts.close(machine.tabId());
            EditorHistories.close(machine.tabId());
        }
        int idx = tabs.indexOf(slot);
        if (idx < 0) return;
        tabs.remove(idx);
        if (tabs.isEmpty()) {
            active = null;
            newTab();
            return;
        }
        if (slot == active) {
            active = null;
            activate(tabs.get(Math.min(idx, tabs.size() - 1)));
        } else {
            renderTabStrip();
        }
    }

    /**
     * 关掉整个窗口：<b>存草稿</b>（下次打开还在），不按标签的"丢弃"语义走。
     */
    public void quit() {
        for (var slot : new ArrayList<>(tabs)) slot.content.onHidden();
        Minecraft.getInstance().setScreen(null);
    }

    public String activeTabId() {
        return active == null ? null : active.content.tabId();
    }

    /** 这个项目现在开着吗（主菜单用它标 ●）。 */
    public boolean isOpen(String machineId) {
        return findMachineSlot(machineId) != null;
    }

    private TabSlot findMachineSlot(String machineId) {
        if (machineId == null) return null;
        for (var slot : tabs) {
            if (slot.content instanceof MachineTab m && machineId.equals(m.tabId())) return slot;
        }
        return null;
    }

    private TabSlot findBySlot(int slotId) {
        for (var slot : tabs) if (slot.slotId == slotId) return slot;
        return null;
    }

    // ==================== 渲染 ====================

    private void renderAll() {
        mountContent();
        renderTabStrip();
    }

    /** 内容区只换挂载，不重建标签自己的元素。 */
    private void mountContent() {
        var content = getModularUI().getElementById(CONTENT_ID);
        if (!(content instanceof UIElement box)) return;
        box.clearAllChildren();
        if (active != null) box.addChild(active.content.tabView());
    }

    /** 标签栏只在这里画一次 —— 所有页面共用。 */
    private void renderTabStrip() {
        var bar = getModularUI().getElementById(TAB_STRIP_ID);
        if (!(bar instanceof UIElement box)) return;
        box.clearAllChildren();

        var title = new Label();
        title.setText(Component.literal("结构编辑器"));
        EditorTheme.style(title, EditorTheme.CLS_TITLE);
        title.layout(l -> l.width(78).heightPercent(100));
        box.addChild(title);

        for (var slot : tabs) {
            boolean isActive = slot == active;
            var tabRow = new UIElement();
            tabRow.layout(l -> l.width(isActive ? 124 : 114).heightPercent(100)
                    .flexDirection(FlexDirection.ROW).gapAll(2));

            var tabBtn = new Button();
            tabBtn.setText(Component.literal((slot.content.isDirty() ? "● " : "") + slot.content.tabTitle()));
            // 选中态 = 一个 class（样式在 lss 的 .me-tab-active）
            if (isActive) tabBtn.addClass(EditorTheme.CLS_TAB_ACTIVE);
            tabBtn.setOnClick(e -> activate(slot));
            tabBtn.layout(l -> l.flex(1).heightPercent(100));
            tabRow.addChild(tabBtn);

            if (slot.content.closable()) {
                var closeTab = new Button();
                closeTab.setText(Component.literal("x"));
                closeTab.addClass(EditorTheme.CLS_DANGER);
                closeTab.setOnClick(e -> closeTab(slot.slotId));
                closeTab.layout(l -> l.width(14).heightPercent(100));
                tabRow.addChild(closeTab);
            }
            box.addChild(tabRow);
        }

        // 「＋」= 开一个新的空白标签（就是浏览器的新标签页）
        var addTab = new Button();
        addTab.setId("ws_add_tab");
        addTab.setText(Component.literal("+"));
        addTab.setOnClick(e -> newTab());
        addTab.layout(l -> l.width(20).heightPercent(100));
        box.addChild(addTab);

        var spacer = new UIElement();
        spacer.layout(l -> l.flex(1).heightPercent(100));
        box.addChild(spacer);

        var close = new Button();
        close.setText(Component.literal("关闭"));
        close.setOnClick(e -> quit());
        close.layout(l -> l.width(40).heightPercent(100));
        box.addChild(close);

        EditorTheme.flatize(box);
    }

    // ==================== 输入 ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 先问当前标签（Ctrl+Z 之类），再交给默认处理
        if (active != null && active.content.handleKey(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private static ModularUI buildShell() {
        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100)
                .flexDirection(FlexDirection.COLUMN));
        EditorTheme.style(root, EditorTheme.CLS_BG);

        var strip = new UIElement();
        strip.setId(TAB_STRIP_ID);
        strip.layout(l -> l.widthPercent(100).height(22)
                .flexDirection(FlexDirection.ROW).gapAll(2).paddingAll(2));
        EditorTheme.style(strip, EditorTheme.CLS_PANEL_ALT);
        root.addChild(strip);

        var content = new UIElement();
        content.setId(CONTENT_ID);
        content.layout(l -> l.widthPercent(100).flex(1).minHeight(0));
        root.addChild(content);

        return ModularUI.of(UI.of(root, EditorTheme.stylesheet()));
    }
}
