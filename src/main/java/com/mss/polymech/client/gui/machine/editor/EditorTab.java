package com.mss.polymech.client.gui.machine.editor;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;

/**
 * 一份<b>标签页文档</b> —— 相当于浏览器里的一个标签。
 *
 * <h3>为什么要有这层抽象</h3>
 * 之前是"每个界面各自画一条标签栏"，于是主菜单没有标签栏、点加号标签栏就没了。
 * 正确的关系是反过来的：<b>窗口只有一个</b>（{@link EditorWorkspaceScreen}），
 * 它拥有唯一的标签栏和内容区；每个标签只是一份"内容 + 生命周期"。
 *
 * <p>这样加新页面（主菜单、未来的资源查看器…）只要实现这个接口，标签栏自动就有了。</p>
 */
public interface EditorTab {

    /** 标签唯一 id（同一个 id 不会开两次）。 */
    String tabId();

    /** 标签上显示的文字。 */
    String tabTitle();

    /** 内容区要挂的元素（每个标签自己持有一份，切换时只换挂载，不重建）。 */
    UIElement tabView();

    /** 成为当前标签时调用（首次 = 建界面，之后 = 刷新）。 */
    default void onShown() {
    }

    /** 被切走时调用（例如存草稿）。 */
    default void onHidden() {
    }

    /** 标签被关闭时调用（真正丢弃时）。 */
    default void onClosed() {
    }

    /** 有没有未保存的改动（标签上显示圆点用）。 */
    default boolean isDirty() {
        return false;
    }

    /**
     * 关闭前确认：标签可以弹窗问一句，确认后再调 {@code onConfirmed} 让窗口真的移除它。
     *
     * <p>默认直接放行。有未保存改动的文档会在这里问"要不要保存"。</p>
     */
    default void requestClose(Runnable onConfirmed) {
        onConfirmed.run();
    }

    /** 能不能关（主菜单那种"常驻页"可以返回 false）。 */
    default boolean closable() {
        return true;
    }

    /** 快捷键：窗口先问当前标签，再交给默认处理。 */
    default boolean handleKey(int keyCode, int scanCode, int modifiers) {
        return false;
    }
}
