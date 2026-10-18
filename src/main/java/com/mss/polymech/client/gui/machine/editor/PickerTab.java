package com.mss.polymech.client.gui.machine.editor;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.mss.polymech.machine.BaseMachineBlock;
import com.mss.polymech.machine.common.MachineRegistry;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

/**
 * 主菜单 —— 相当于浏览器的<b>新标签页</b>（搜索引擎首页）。
 *
 * <p>它 <b>不常驻</b>：在这里点一台机器，<b>当前这个标签自己导航过去</b>变成那个项目，
 * 主菜单不会额外留一份（就像在搜索引擎里点结果，那个标签就跳到目标页）。</p>
 *
 * <p>它<b>没有</b>自己的顶栏：标签栏由 {@link EditorWorkspaceScreen} 统一画。</p>
 */
final class PickerTab implements EditorTab {

    static final String ID = "home";

    private final EditorWorkspaceScreen workspace;
    private final UIElement root;
    private final UIElement list;

    PickerTab(EditorWorkspaceScreen workspace) {
        this.workspace = workspace;
        this.list = new UIElement();
        this.list.setId("picker_list");
        this.list.layout(l -> l.widthPercent(100).flex(1).minHeight(0)
                .flexDirection(FlexDirection.COLUMN).gapAll(2));
        this.root = build();
        refresh();
    }

    @Override
    public String tabId() {
        return ID;
    }

    @Override
    public String tabTitle() {
        return "主菜单";
    }

    @Override
    public UIElement tabView() {
        return root;
    }

    @Override
    public void onShown() {
        refresh();
    }

    /** 机器列表随"已开标签"变化，每次显示时重画一次。 */
    private void refresh() {
        list.clearAllChildren();
        var ids = BaseMachineBlock.getMachineIds();
        if (ids.isEmpty()) {
            var empty = new Label();
            empty.setText(Component.literal("（还没有注册任何机器）"));
            EditorTheme.style(empty, EditorTheme.CLS_DIM);
            empty.layout(l -> l.widthPercent(100).height(16));
            list.addChild(empty);
            return;
        }
        for (String id : ids) {
            boolean open = workspace.isOpen(id);
            var btn = new Button();
            btn.setText(Component.literal((open ? "● " : "  ") + id));
            if (open) btn.addClass(EditorTheme.CLS_BTN_ACCENT);
            btn.setOnClick(e -> {
                var block = MachineRegistry.getMachineBlock(id);
                if (block != null) workspace.openMachine(id, block);
            });
            btn.layout(l -> l.widthPercent(100).height(18));
            list.addChild(btn);
        }
        EditorTheme.flatize(list);
    }

    private UIElement build() {
        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100).paddingAll(8)
                .flexDirection(FlexDirection.COLUMN).gapAll(6));
        root.addChild(list);
        return root;
    }
}
