package com.mss.polymech.client.gui.machine.editor;

import com.mss.polymech.machine.definition.MachineDefinition;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 撤销 / 重做栈（保存的是 {@link MachineDefinition} 快照 + 这一步干了什么）。
 *
 * <h3>为什么用整份定义快照，而不是"反向操作"</h3>
 * 反向操作要为每种编辑各写一份逆运算（加格↔删格、区间切割的逆、改角色…），
 * 而区间切割是"一拆六"、逆运算并不唯一，很容易写出对不上的 bug。
 * 编辑器每次改动前存一份定义（几十字节到几 KB），撤销时整体换回去 ——
 * <strong>正确性不依赖任何逆运算</strong>，这是编辑器这类场景的标准做法。
 *
 * <h3>为什么要带标签</h3>
 * 只有"N 步"没法让人知道自己退到哪一步了。每步记一句"干了什么"
 * （"加格 (1,2,3)"、"改名 主体"），历史面板才能列出来、才能点着跳。
 *
 * <p>纯逻辑，可离线断言：顺序、标签、canUndo/canRedo 的状态迁移、超出上限时丢最旧的。</p>
 */
public final class UndoStack {

    /** 一步：{@code label} 是这一步做了什么，{@code definition} 是<b>做之前</b>的状态。 */
    public record Step(String label, MachineDefinition definition) {}

    private final int limit;
    private final Deque<Step> undo = new ArrayDeque<>();
    private final Deque<Step> redo = new ArrayDeque<>();

    public UndoStack(int limit) {
        this.limit = Math.max(1, limit);
    }

    /** 在<strong>改动之前</strong>记一份当前状态。任何新改动都会清空重做栈。 */
    public void record(String label, MachineDefinition before) {
        undo.push(new Step(label == null || label.isBlank() ? "改动" : label.trim(), before));
        while (undo.size() > limit) {
            undo.removeLast();
        }
        redo.clear();
    }

    /**
     * 撤销一步。
     *
     * @param current 当前状态（会被推进重做栈，标签沿用被撤销那一步的）
     * @return 被撤销的那一步；栈空时返回 {@code null}（调用方据此提示"没有可撤销的操作"）
     */
    @Nullable
    public Step undo(MachineDefinition current) {
        if (undo.isEmpty()) return null;
        Step s = undo.pop();
        redo.push(new Step(s.label(), current));
        return s;
    }

    /** 重做一步；栈空时返回 {@code null}。 */
    @Nullable
    public Step redo(MachineDefinition current) {
        if (redo.isEmpty()) return null;
        Step s = redo.pop();
        undo.push(new Step(s.label(), current));
        return s;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public int undoDepth() {
        return undo.size();
    }

    public int redoDepth() {
        return redo.size();
    }

    /** 历史面板用：还要退几步、每步叫什么（最近的在前）。 */
    public List<String> undoLabels() {
        var out = new ArrayList<String>(undo.size());
        for (Step s : undo) out.add(s.label());
        return out;
    }

    /** 历史面板用：还能重做几步、每步叫什么（最近的在前）。 */
    public List<String> redoLabels() {
        var out = new ArrayList<String>(redo.size());
        for (Step s : redo) out.add(s.label());
        return out;
    }

    public void clear() {
        undo.clear();
        redo.clear();
    }

    /** 界面提示文字，如 {@code 撤销(3)/重做(1)}。 */
    public String describe() {
        return "撤销(" + undoDepth() + ")/重做(" + redoDepth() + ")";
    }
}
