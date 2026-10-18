package com.mss.polymech.client.gui.machine.editor;

import com.mss.polymech.machine.definition.MachineDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多开草稿：编辑器可以同时开多个项目（像 Blockbench 的顶部标签页）。
 *
 * <p>切换项目时把当前编辑内容留在这里，切回来时恢复 —— <b>没保存的改动也不会丢</b>。
 * 存的是 {@link MachineDefinition}，和撤销栈用的是同一份快照类型，
 * 所以草稿里的内容就是"当前编辑状态"本身，不需要另写一套序列化。</p>
 *
 * <p>只有显式"关闭项目"才真正丢弃草稿；切标签只是保存草稿。</p>
 */
public final class EditorDrafts {

    /** 保持打开顺序（标签栏按这个顺序画）。 */
    private static final Map<String, MachineDefinition> DRAFTS = new LinkedHashMap<>();

    private EditorDrafts() {
    }

    public static void put(String machineId, MachineDefinition def) {
        if (machineId == null || def == null) return;
        DRAFTS.put(machineId, def);
    }

    public static MachineDefinition get(String machineId) {
        return DRAFTS.get(machineId);
    }

    public static boolean isOpen(String machineId) {
        return DRAFTS.containsKey(machineId);
    }

    /** 打开过的项目 id（按打开顺序）。 */
    public static List<String> openIds() {
        return new ArrayList<>(DRAFTS.keySet());
    }

    /** 关掉一个项目（真正丢弃它的草稿）。 */
    public static void close(String machineId) {
        DRAFTS.remove(machineId);
    }
}
