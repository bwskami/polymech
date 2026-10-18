package com.mss.polymech.client.gui.machine.editor;

/**
 * 每一格在 3D 视口里"长什么样"：分类 + 颜色 + 中文名。
 *
 * <h3>为什么需要它</h3>
 * 之前编辑器把<strong>所有</strong>非控制器格都画成同一种白色线框，于是
 * <strong>舱室和普通外壳格完全分不出来</strong>，朝向面更是只存在于右侧文字里。
 * 对整合包作者来说，"哪一格是舱室、它朝哪边开"必须一眼看见。
 *
 * <p>颜色约定（同时是界面图例的文字来源）：</p>
 * <ul>
 *   <li>控制器 —— 绿（显示机器原本的样子）</li>
 *   <li>外壳   —— 白</li>
 *   <li>物品舱室 —— 橙</li>
 *   <li>流体舱室 —— 蓝</li>
 *   <li>物品+流体 —— 紫</li>
 * </ul>
 * 纯逻辑（只碰枚举与颜色常量），所以分类判据与图例文字都能离线断言。
 */
public final class EditorCellVisuals {

    /** 格子的视觉分类。 */
    public enum Kind {
        CONTROLLER("控制器", 0xFF3CFF3C, 0xFF3CFF3C),
        STRUCTURE("外壳", 0x9FFFFFFF, 0x9FFFFFFF),
        ITEM_PROXY("物品舱室", 0xFFFFA028, 0xFFFFC864),
        FLUID_PROXY("流体舱室", 0xFF40A8FF, 0xFF7FD0FF),
        BOTH_PROXY("物品+流体舱室", 0xFFC060FF, 0xFFD9A0FF);

        private final String label;
        private final int outline;
        private final int face;

        Kind(String label, int outline, int face) {
            this.label = label;
            this.outline = outline;
            this.face = face;
        }

        public String label() {
            return label;
        }

        /** 立方体线框颜色（ARGB）。 */
        public int outlineColor() {
            return outline;
        }

        /** 开口面片颜色（ARGB），比线框更亮以便一眼看出朝向。 */
        public int faceColor() {
            return face;
        }

        public boolean isProxy() {
            return this == ITEM_PROXY || this == FLUID_PROXY || this == BOTH_PROXY;
        }
    }

    private EditorCellVisuals() {
    }

    /** 分类判据（控制器优先：控制器格不放代理）。 */
    public static Kind kindOf(boolean controller, boolean item, boolean fluid) {
        if (controller) return Kind.CONTROLLER;
        if (item && fluid) return Kind.BOTH_PROXY;
        if (item) return Kind.ITEM_PROXY;
        if (fluid) return Kind.FLUID_PROXY;
        return Kind.STRUCTURE;
    }

    public static Kind kindOf(MachineStructureSnapshot.Cell cell) {
        return kindOf(cell.controller(), cell.itemProxy() != null, cell.fluidProxy() != null);
    }

    /** 图例全文（界面"显示"页直接用，也是断言的对象）。 */
    public static String legendText() {
        StringBuilder sb = new StringBuilder("颜色图例：\n");
        for (Kind k : Kind.values()) {
            sb.append("  ").append(k.label()).append("：").append(colorName(k)).append("\n");
        }
        sb.append("  开口面：贴在格子那一面上的彩色面片 + 箭头（六面全通时六面都贴）");
        return sb.toString();
    }

    /** 颜色的中文说法（图例里写给整合包作者看）。 */
    public static String colorName(Kind k) {
        return switch (k) {
            case CONTROLLER -> "绿框";
            case STRUCTURE -> "白框";
            case ITEM_PROXY -> "橙框";
            case FLUID_PROXY -> "蓝框";
            case BOTH_PROXY -> "紫框";
        };
    }
}
