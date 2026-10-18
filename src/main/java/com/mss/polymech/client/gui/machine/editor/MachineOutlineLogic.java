package com.mss.polymech.client.gui.machine.editor;

import com.mss.polymech.machine.definition.MachineDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 大纲的<strong>决策</strong>：有哪些行、第几层、「+ 组」落在哪一层。
 *
 * <p>刻意做成<strong>不碰界面</strong>的纯函数。这两件事是编辑器里最容易写错、
 * 又最难靠肉眼发现的地方（层级算错、移交时悄悄改了结构），所以要让断言能直接跑
 * <strong>真行为</strong> —— 而不是去源码里 grep 一段字符串（那只能证明"代码还在"，
 * 证明不了"算得对"）。</p>
 *
 * <p>渲染层（{@code MachineEditorScreen}）只负责把这些行喂给 {@code TreeList}。</p>
 */
public final class MachineOutlineLogic {

    private MachineOutlineLogic() {
    }

    /**
     * 大纲的一行。
     *
     * @param path  这一行所属的组（cube 行 = 它所在的组）
     * @param group 所属的组（组头行就是它自己）
     * @param cube  cube 行非 null；组头行为 null
     * @param depth 顶层组 = 1，它的 cube / 子组 = 2，依此类推
     */
    public record Row(MachineStructureSnapshot.GroupPath path,
                      MachineDefinition.Group group,
                      MachineDefinition.Cube cube,
                      int depth) {
        public boolean isGroup() {
            return cube == null;
        }
    }

    /**
     * 深度优先拍平：<b>组头 → 它自己直接挂的 cube → 子组</b>（与 Blockbench 的大纲一致）。
     *
     * <p>这里不做任何过滤 —— 折叠是渲染层的决定（要不要往下走），不是数据的事。</p>
     */
    public static List<Row> rows(MachineStructureSnapshot snapshot) {
        var out = new ArrayList<Row>();
        for (var e : snapshot.allGroups()) {
            out.add(new Row(e.path(), e.group(), null, e.depth()));
            for (var c : e.group().cubes()) {
                out.add(new Row(e.path(), e.group(), c, e.depth() + 1));
            }
        }
        return out;
    }

    /**
     * 一次大纲选择解出来的结果。
     *
     * <p>把"选中的是 cube 还是文件夹行"这件事从渲染层搬出来，是因为它被
     * <b>两个不同用途</b>共用过：{@code groupContext} 是"当前组上下文"（点 cube 时
     * 就是它所属的组，供「+ 组」和计数用），而 {@code renameTarget} 只该在
     * <b>用户亲手点了一个文件夹行</b>时非空。混成一个字段就会出现
     * "选中 cube 却在改组名"。</p>
     *
     * @param cubes        选中的 cube（保持点击顺序）
     * @param primary      最后点的那个 cube（3D 高亮 / 变换面板用的"主选中"）
     * @param groupContext 当前组上下文（cube → 它所属的组；文件夹行 → 它自己）
     * @param renameTarget 可改名的文件夹行；<b>选中了 cube 或同时选了文件夹时是 null</b>
     */
    public record Selection(java.util.Set<MachineDefinition.Cube> cubes,
                            MachineDefinition.Cube primary,
                            MachineStructureSnapshot.GroupPath groupContext,
                            MachineStructureSnapshot.GroupPath renameTarget) {
        public boolean canRename() {
            return renameTarget != null;
        }
    }

    /**
     * 把"大纲选中了哪几行"解成 {@link Selection}。
     *
     * @param selected 选中的行（顺序 = 点击顺序）
     * @param snapshot 用来把 cube 映射回它所在的组
     */
    public static Selection resolveSelect(java.util.Collection<Row> selected,
                                         MachineStructureSnapshot snapshot) {
        var cubes = new java.util.LinkedHashSet<MachineDefinition.Cube>();
        MachineDefinition.Cube primary = null;
        for (var r : selected) {
            if (r.cube() == null) continue;
            cubes.add(r.cube());
            primary = r.cube();
        }

        // 只有"恰好选中一行、且它是文件夹行"才允许改名；
        // 混选（文件夹 + cube）一律不允许 —— 否则又会变成"以为在改 cube、其实改了组"
        MachineStructureSnapshot.GroupPath renameTarget = null;
        if (selected.size() == 1) {
            var only = selected.iterator().next();
            if (only.isGroup()) renameTarget = only.path();
        }

        MachineStructureSnapshot.GroupPath groupContext = primary != null
                ? snapshot.cubePath(primary)
                : renameTarget;

        return new Selection(cubes, primary, groupContext, renameTarget);
    }

    /**
     * 大纲一行显示的文字。
     *
     * <p><b>组头行显示组名，cube 行显示 "cube"（盒子显示 "cube (盒)"）</b> —— 绝不能显示属主组名。
     * 这个判断原来写在渲染层里、用「所属组 != null」当「是不是组头」，而 cube 行也带着所属组
     * （取上下文用），结果每一行都显示组名：改一个 cube 的名字看着像整组被改名。
     * 抽成纯函数就是为了让 M20 能直接断言「cube 行不叫组名」。</p>
     */
    public static String rowLabel(MachineDefinition.Group group, MachineDefinition.Cube cube) {
        if (cube != null) {
            // schema 4 起 cube 可以起名；没名字时退回按形状显示
            if (cube.hasName()) return cube.name();
            return cube.isUnit() ? "cube" : "cube (盒)";
        }
        return group == null ? "" : group.name();
    }

    /**
     * 「+ 组」的计划：新组挂在哪、它自己第几层、哪些 cube 会因为移交而<b>换层</b>。
     *
     * @param parent     新组的父路径（{@link MachineStructureSnapshot.GroupPath#ROOT} = 顶层）
     * @param childDepth 新组自己的层级（= {@code parent.depth() + 1}）
     * @param conflicts  移交后会改变层级的 cube（空 = 可以静默执行）
     * @param cubes      要移交的 cube（保持传入顺序）
     */
    public record NewGroupPlan(MachineStructureSnapshot.GroupPath parent,
                               int childDepth,
                               List<MachineDefinition.Cube> conflicts,
                               List<MachineDefinition.Cube> cubes) {
        public boolean needsConfirm() {
            return !conflicts.isEmpty();
        }
    }

    /**
     * 算出「+ 组」的落点。
     *
     * <h3>落在哪一层</h3>
     * <ul>
     *   <li>选了 cube → 建在<b>最浅那个 cube 所在组的同级</b>（父级 = 那个组的父级）；</li>
     *   <li>只选了文件夹 → 建在它<b>里面</b>（子组，这是造嵌套组的入口）；</li>
     *   <li>什么都没选 → 顶层。</li>
     * </ul>
     *
     * <p>"最浅"是为了让结果符合直觉：从不同层各选一个 cube 时，新组不会沉到最深那一层去。</p>
     *
     * @return {@code null} 表示选中的 cube 已经不在结构里了（调用方应提示并放弃）
     */
    public static NewGroupPlan planNewGroup(MachineStructureSnapshot snapshot,
                                            Collection<MachineDefinition.Cube> selectedCubes,
                                            MachineStructureSnapshot.GroupPath selectedGroup) {
        var cubes = new ArrayList<>(selectedCubes);
        MachineStructureSnapshot.GroupPath parent;

        if (!cubes.isEmpty()) {
            int shallowest = Integer.MAX_VALUE;
            for (var c : cubes) {
                var p = snapshot.cubePath(c);
                if (p != null) shallowest = Math.min(shallowest, p.depth());
            }
            if (shallowest == Integer.MAX_VALUE) return null;
            MachineStructureSnapshot.GroupPath at = null;
            for (var c : cubes) {
                var p = snapshot.cubePath(c);
                if (p != null && p.depth() == shallowest) {
                    at = p;
                    break;
                }
            }
            parent = at == null ? MachineStructureSnapshot.GroupPath.ROOT : at.parent();
        } else if (selectedGroup != null) {
            parent = selectedGroup;
        } else {
            parent = MachineStructureSnapshot.GroupPath.ROOT;
        }

        int childDepth = parent.depth() + 1;
        var conflicts = new ArrayList<MachineDefinition.Cube>();
        for (var c : cubes) {
            var p = snapshot.cubePath(c);
            if (p != null && p.depth() != childDepth) conflicts.add(c);
        }
        return new NewGroupPlan(parent, childDepth, conflicts, cubes);
    }
}
