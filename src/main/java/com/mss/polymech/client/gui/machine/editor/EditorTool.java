package com.mss.polymech.client.gui.machine.editor;

/**
 * 编辑器的<b>工具</b> —— 像 Blockbench 那样先选工具、再在 3D 里点，而不是让左键身兼数职。
 *
 * <h3>为什么必须有工具切换</h3>
 * 3D 视口里"点一下"能表达的意思太多了：选中、加格、删格、改朝向面、定区间、改角色。
 * 如果全绑在左键上（再靠 Shift/Ctrl 区分），作者根本记不住，也容易误操作。
 * Blockbench 的做法是顶部一排工具按钮，选中哪个、点下去就是哪个行为——
 * 这里照同一套：工具决定"点击语义"，界面顶部一排按钮随时切换。
 *
 * <p>纯枚举（不含任何 MC 类型），所以"每个工具的点语义唯一、提示文案齐全、
 * 循环切换不漏项"都能离线断言。</p>
 */
public enum EditorTool {

    /** 选中格子（后续在右侧面板编辑它的舱室/朝向面/说明）。 */
    SELECT("选择", "点格子选中它"),
    /** 在点击的那一面外侧加一格（Blockbench 的 extrude 手感）。 */
    ADD("添加", "点某个面，在那一面外侧加一格"),
    /** 删掉点到的格子。 */
    REMOVE("删除", "点格子删掉它（控制器删不掉）"),
    /** 改舱室的朝向面：点九宫格设为该面，Shift+点再加一个面。 */
    FACE("朝向面", "点九宫格＝把朝向面设为这一面；Shift+点＝再加一个面"),
    /** 区间工具：点起点 A、点终点 B，再按「创建区间」。 */
    REGION("区间", "点两下定出长方体区间（起点 A → 终点 B），再按「创建区间」"),
    /** 循环切换格子的舱室角色：外壳 → 物品 → 流体 → 物品+流体。 */
    ROLE("舱室角色", "点格子循环切换：外壳 → 物品舱室 → 流体舱室 → 物品+流体");

    private final String label;
    private final String hint;

    EditorTool(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    /** 按钮上显示的中文名。 */
    public String label() {
        return label;
    }

    /** 状态栏提示（告诉作者"现在点下去会发生什么"）。 */
    public String hint() {
        return hint;
    }

    /** 下一个工具（键盘/按钮循环切换用）。 */
    public EditorTool next() {
        EditorTool[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** 这个工具是否需要在悬停面上画九宫格。 */
    public boolean usesFaceGrid() {
        return this == FACE;
    }

    /** 这个工具是否会在 3D 里改结构（决定要不要记一次撤销）。 */
    public boolean mutatesStructure() {
        return this == ADD || this == REMOVE || this == REGION;
    }

    /** 解析（命令/配置里用的英文 id）；不认识返回 null。 */
    public static EditorTool parse(String s) {
        if (s == null) return null;
        String k = s.trim().toUpperCase(java.util.Locale.ROOT);
        for (EditorTool t : values()) {
            if (t.name().equals(k)) return t;
        }
        return null;
    }
}
