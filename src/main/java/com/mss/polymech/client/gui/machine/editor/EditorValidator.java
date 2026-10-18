package com.mss.polymech.client.gui.machine.editor;

import com.mss.polymech.machine.definition.MachineDefinition;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构定义的<strong>体检</strong>：把"能写但会出事"的写法提前挑出来。
 *
 * <h3>为什么整合包作者需要它</h3>
 * 手写 / 编辑 JSON 时，最容易犯的错是<strong>静默失效</strong>：
 * 舱室写在结构外（根本不会放置）、舱室没写槽位下标（代理空转）、
 * 把控制器格当舱室（主方块不接受代理）。这些都不会崩游戏，只是"功能没生效"，
 * 对不懂代码的人来说无从排查。所以编辑器常驻一个校验面板，把这些直接写成中文。
 *
 * <p>纯逻辑：输入只有 {@link MachineStructureSnapshot}，输出只有问题列表，
 * 所以每条判据都能离线断言（见 {@code verifyMachineEditorModel} 的 M9）。</p>
 */
public final class EditorValidator {

    public enum Severity {
        /** 会导致功能不生效，必须修。 */
        ERROR,
        /** 能用但不规范 / 容易踩坑，建议修。 */
        WARN
    }

    public record Issue(Severity severity, String message) {
        public boolean isError() {
            return severity == Severity.ERROR;
        }

        @Override
        public String toString() {
            return (severity == Severity.ERROR ? "✖ " : "⚠ ") + message;
        }
    }

    private EditorValidator() {
    }

    /**
     * 体检。返回顺序稳定（先 ERROR 后 WARN），便于界面显示与断言。
     */
    public static List<Issue> check(MachineStructureSnapshot s) {
        MachineDefinition def = s.toDefinition();
        List<Issue> errors = new ArrayList<>();
        List<Issue> warns = new ArrayList<>();

        // ---------- ERROR：静默失效类 ----------

        // 控制器格被声明成舱室：主方块自身就是控制器，代理挂在它身上没有意义
        if (def.itemProxies().containsKey(Vec3i.ZERO) || def.fluidProxies().containsKey(Vec3i.ZERO)) {
            errors.add(new Issue(Severity.ERROR,
                    "控制器格 " + fmt(Vec3i.ZERO) + " 被声明成舱室——它就是主方块本身，不会另放代理"));
        }

        // 舱室不在结构里：那一格根本不会被放置，代理永远不生效
        for (Vec3i at : def.itemProxies().keySet()) {
            if (!s.hasCell(at)) {
                errors.add(new Issue(Severity.ERROR,
                        "物品舱室 " + fmt(at) + " 不在结构里（那一格不会被放置，代理不会生效）"));
            }
        }
        for (Vec3i at : def.fluidProxies().keySet()) {
            if (!s.hasCell(at)) {
                errors.add(new Issue(Severity.ERROR,
                        "流体舱室 " + fmt(at) + " 不在结构里（那一格不会被放置，代理不会生效）"));
            }
        }

        // 舱室没有槽位/储罐下标：代理暴露了个空集合
        for (var e : def.itemProxies().entrySet()) {
            if (e.getValue().slots().length == 0) {
                errors.add(new Issue(Severity.ERROR,
                        "物品舱室 " + fmt(e.getKey()) + " 没有填槽位下标（如 2 或 0,1）"));
            }
        }
        for (var e : def.fluidProxies().entrySet()) {
            if (e.getValue().tanks().length == 0) {
                errors.add(new Issue(Severity.ERROR,
                        "流体舱室 " + fmt(e.getKey()) + " 没有填储罐下标（如 0 或 0,1）"));
            }
        }

        // ---------- WARN：能用但不规范 ----------

        if (s.cellCount() <= 1) {
            warns.add(new Issue(Severity.WARN, "结构里只有控制器，没有任何外壳格"));
        }

        // 离散格重复声明（几何层不去重，所以无害，但说明手写时有冗余）
        Set<Vec3i> seen = new HashSet<>();
        for (Vec3i v : def.sideOffsets()) {
            if (!seen.add(v)) {
                warns.add(new Issue(Severity.WARN, "side_offsets 里 " + fmt(v) + " 重复声明"));
            }
        }

        // 离散格落在填充区间里：重复声明（大机器手改后很常见）
        for (Vec3i v : def.sideOffsets()) {
            if (s.isInFillRegion(v)) {
                warns.add(new Issue(Severity.WARN,
                        "离散格 " + fmt(v) + " 落在填充区间内（重复声明，无害但冗余）"));
            }
        }

        // 有舱室但没声明开口面：管道自动铺设会吸附到"首个声明面"，全通时无从判断
        List<String> allOpen = new ArrayList<>();
        for (var e : def.itemProxies().entrySet()) {
            if (ProxyFaces.isAllOpen(e.getValue().faces())) allOpen.add("物品 " + fmt(e.getKey()));
        }
        for (var e : def.fluidProxies().entrySet()) {
            if (ProxyFaces.isAllOpen(e.getValue().faces())) allOpen.add("流体 " + fmt(e.getKey()));
        }
        if (!allOpen.isEmpty()) {
            warns.add(new Issue(Severity.WARN,
                    "这些舱室没声明开口面（六面全通）：" + String.join("、", allOpen)
                            + "——管道自动铺设需要「首个声明面」，建议指定"));
        }

        List<Issue> out = new ArrayList<>(errors.size() + warns.size());
        out.addAll(errors);
        out.addAll(warns);
        return out;
    }

    /** 只要错误（保存前拦截用）。 */
    public static List<Issue> errorsOf(List<Issue> issues) {
        List<Issue> out = new ArrayList<>();
        for (Issue i : issues) {
            if (i.isError()) out.add(i);
        }
        return out;
    }

    /** 面板文字：没有问题就说"没问题"，否则逐行列出。 */
    public static String render(List<Issue> issues) {
        if (issues.isEmpty()) return "✔ 体检通过：没有发现问题";
        StringBuilder sb = new StringBuilder();
        for (Issue i : issues) {
            sb.append(i).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String fmt(Vec3i v) {
        return "(" + v.getX() + "," + v.getY() + "," + v.getZ() + ")";
    }
}
