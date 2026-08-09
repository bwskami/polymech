package com.mss.polymech.tooltip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 分子结构式数据模型（GT风格键线式）。
 * <p>
 * 以网格坐标描述原子位置（渲染时乘以键长缩放），键分为单键/双键，
 * 芳香环采用凯库勒式画法（交替环内双键短线，以{@link RingLine}描述）。
 * 结构本身不可变，通过{@link Builder}构建；实际结构由CDK从 SMILES 自动生成
 * （见{@code SmilesStructures}），不再手工摆坐标。
 * </p>
 */
public final class MoleculeStructure {

    /** 键类型 */
    public enum BondType {
        SINGLE, DOUBLE
    }

    /**
     * 单个原子（标签可以是基团文本，如"CH3"、"OH"）。
     *
     * @param label 显示文本
     * @param x     网格横坐标
     * @param y     网格纵坐标（向下为正）
     */
    public record Atom(String label, float x, float y) {
    }

    /**
     * 两个原子索引之间的化学键。
     */
    public record Bond(int a, int b, BondType type) {
    }

    /**
     * 环内双键示意线（凯库勒式：沿交替环边内侧绘制的平行短线）。
     */
    public record RingLine(float x1, float y1, float x2, float y2) {
    }

    /** 链方向：聚合物重复单元上穿出括号的链延续键方向（左右穿出侧括号，上下穿出顶/底边，如GTM硅橡胶） */
    public enum Direction {
        LEFT, RIGHT, UP, DOWN
    }

    /**
     * 聚合物链延续键锚点：从指定原子向指定方向画一条穿出括号的短键，
     * 表示高分子链在重复单元外继续延伸。
     *
     * @param atom 原子索引
     * @param dir  链延伸方向（LEFT/RIGHT=穿出侧括号，UP/DOWN=穿出顶/底边）
     */
    public record Anchor(int atom, Direction dir) {
    }

    private final List<Atom> atoms;
    private final List<Bond> bonds;
    private final List<RingLine> ringLines;
    private final List<Anchor> anchors;
    private final float minX;
    private final float minY;
    private final float maxX;
    private final float maxY;

    private MoleculeStructure(List<Atom> atoms, List<Bond> bonds, List<RingLine> ringLines, List<Anchor> anchors) {
        this.atoms = Collections.unmodifiableList(atoms);
        this.bonds = Collections.unmodifiableList(bonds);
        this.ringLines = Collections.unmodifiableList(ringLines);
        this.anchors = Collections.unmodifiableList(anchors);
        float mnx = Float.MAX_VALUE, mny = Float.MAX_VALUE, mxx = -Float.MAX_VALUE, mxy = -Float.MAX_VALUE;
        for (Atom atom : atoms) {
            mnx = Math.min(mnx, atom.x());
            mny = Math.min(mny, atom.y());
            mxx = Math.max(mxx, atom.x());
            mxy = Math.max(mxy, atom.y());
        }
        this.minX = atoms.isEmpty() ? 0 : mnx;
        this.minY = atoms.isEmpty() ? 0 : mny;
        this.maxX = atoms.isEmpty() ? 0 : mxx;
        this.maxY = atoms.isEmpty() ? 0 : mxy;
    }

    public List<Atom> atoms() {
        return atoms;
    }

    public List<Bond> bonds() {
        return bonds;
    }

    public List<RingLine> ringLines() {
        return ringLines;
    }

    /** 聚合物链延续键锚点列表（非聚合物为空） */
    public List<Anchor> anchors() {
        return anchors;
    }

    public float minX() {
        return minX;
    }

    public float minY() {
        return minY;
    }

    public float maxX() {
        return maxX;
    }

    public float maxY() {
        return maxY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 结构构建器：坐标为网格单位（1个单位≈1条键长） */
    public static final class Builder {

        private final List<Atom> atoms = new ArrayList<>();
        private final List<Bond> bonds = new ArrayList<>();
        private final List<RingLine> ringLines = new ArrayList<>();
        private final List<Anchor> anchors = new ArrayList<>();

        private Builder() {
        }

        /** 添加原子并返回其索引 */
        public int atom(String label, float x, float y) {
            atoms.add(new Atom(label, x, y));
            return atoms.size() - 1;
        }

        /** 添加单键 */
        public Builder bond(int a, int b) {
            bonds.add(new Bond(a, b, BondType.SINGLE));
            return this;
        }

        /** 添加双键 */
        public Builder doubleBond(int a, int b) {
            bonds.add(new Bond(a, b, BondType.DOUBLE));
            return this;
        }

        /** 添加环内双键示意线（凯库勒式内侧平行短线，坐标为网格单位） */
        public Builder ringLine(float x1, float y1, float x2, float y2) {
            ringLines.add(new RingLine(x1, y1, x2, y2));
            return this;
        }

        /** 添加聚合物链延续键锚点（从指定原子穿出离子括号） */
        public Builder anchor(int atom, Direction dir) {
            anchors.add(new Anchor(atom, dir));
            return this;
        }

        public MoleculeStructure build() {
            return new MoleculeStructure(atoms, bonds, ringLines, anchors);
        }
    }
}
