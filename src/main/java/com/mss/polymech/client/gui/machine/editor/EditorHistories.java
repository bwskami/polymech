package com.mss.polymech.client.gui.machine.editor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>每个项目</b>（机器 id）独立一份撤销栈。
 *
 * <h3>为什么不能放在 {@link MachineEditorScreen} 里</h3>
 * 切项目是<strong>新开一个 Screen 实例</strong>（{@code EditorScreenOpener.open} →
 * {@code new MachineEditorScreen(...)}），实例字段跟着旧实例一起没了 —— 切走再回来历史就是空的。
 * 历史属于"这个项目"，不属于"这一次打开"，所以和草稿（{@link EditorDrafts}）一样
 * 按 machineId 存在这里。
 *
 * <p>只有显式"关闭项目"才真正丢弃它（见 {@link #close}），否则多开几次会一直涨。</p>
 */
public final class EditorHistories {

    /** 每个项目保留多少步。与原来的单一栈一致。 */
    public static final int LIMIT = 64;

    private static final Map<String, UndoStack> STACKS = new LinkedHashMap<>();

    private EditorHistories() {
    }

    /** 取这个项目的历史栈；没有就建一个。 */
    public static UndoStack of(String machineId) {
        String id = machineId == null ? "" : machineId;
        return STACKS.computeIfAbsent(id, k -> new UndoStack(LIMIT));
    }

    /** 关掉一个项目时把它的历史一起丢掉。 */
    public static void close(String machineId) {
        STACKS.remove(machineId);
    }

    /** 这个项目现在有没有历史栈（断言 / 诊断用）。 */
    public static boolean has(String machineId) {
        return STACKS.containsKey(machineId);
    }

    /** 当前记了几个项目的历史（断言用）。 */
    public static int size() {
        return STACKS.size();
    }
}
