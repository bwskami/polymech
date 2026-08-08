package com.mss.polymech.client.tooltip;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.tooltip.CompositionStructureTooltipComponent;
import com.mss.polymech.tooltip.ElementColors;
import com.mss.polymech.tooltip.MoleculeStructure;
import com.mss.polymech.tooltip.MoleculeStructure.Atom;
import com.mss.polymech.tooltip.MoleculeStructure.Bond;
import com.mss.polymech.tooltip.MoleculeStructure.BondType;
import com.mss.polymech.tooltip.MoleculeStructure.RingLine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 分子/离子结构式tooltip组件的客户端渲染实现（GT风格键线式）。
 * <p>
 * 键用黄色细线绘制（双键为两条平行线，苯环为凯库勒式交替环内双键），
 * 键的两端自动收缩到原子标签边缘；原子标签按元素周期表配色染色。
 * 先画键线（立即上传），后绘制文字压在键线上方。
 * 支持多结构式（离子化合物的各离子结构）：水平并排、垂直居中对齐。
 * 离子结构（电荷非0）外围绘制黄色离子括号"[ ]"，"]"右上角标注电荷上标
 * （如⁺、²⁻），与GTM离子式画法一致。
 * </p>
 */
public class ClientMoleculeStructureTooltipComponent implements ClientTooltipComponent {

    /** 单个网格单位的像素尺寸（≈键长） */
    private static final int CELL = 12;
    /** 离子括号占位（竖线到内容起始的水平距离） */
    private static final int BRACKET_INSET = 5;
    /** 括号上下端横向短划线长度 */
    private static final int BRACKET_TICK = 3;
    /** 键线颜色（GT黄） */
    private static final int BOND_COLOR = 0xC8B400;
    /** 离子括号与电荷上标颜色（亮黄，同化学式行的括号色） */
    private static final int BRACKET_COLOR = 0xFFFF55;
    /** 双键两条线的间距一半 */
    private static final float DOUBLE_BOND_OFFSET = 1.5f;
    /** 多结构式并排时的间距 */
    private static final int STRUCTURE_GAP = 4;

    /** 电荷上标字符表：0-9 ⁺ ⁻（如 "2-" -> "²⁻"、"+" -> "⁺"） */
    private static final Map<Character, Character> SUPERSCRIPT_CHARS = Map.ofEntries(
            Map.entry('0', '\u2070'), Map.entry('1', '\u00B9'), Map.entry('2', '\u00B2'),
            Map.entry('3', '\u00B3'), Map.entry('4', '\u2074'), Map.entry('5', '\u2075'),
            Map.entry('6', '\u2076'), Map.entry('7', '\u2077'), Map.entry('8', '\u2078'),
            Map.entry('9', '\u2079'), Map.entry('+', '\u207A'), Map.entry('-', '\u207B'));

    /** 电荷数值转Unicode上标（1省略倍数）：+1 -> "⁺"、-2 -> "²⁻" */
    private static String chargeSuperscript(int charge) {
        int mag = Math.abs(charge);
        String raw = (mag == 1 ? "" : String.valueOf(mag)) + (charge > 0 ? "+" : "-");
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) sb.append(SUPERSCRIPT_CHARS.getOrDefault(c, c));
        return sb.toString();
    }

    /** 单个结构式条目的预计算布局数据（标签宽度/颜色/裁剪半宽、自身宽高、离子括号与电荷） */
    private final class Entry {
        final MoleculeStructure structure;
        /** 离子电荷（0=中性分子，不画括号） */
        final int charge;
        /** 结构本体宽高（不含括号） */
        final int width;
        final int height;
        /** 总占宽（离子结构含两侧括号与右上角电荷上标） */
        final int totalWidth;
        /** 内容动态内边距（按边缘原子标签半宽收紧，使括号紧贴内容） */
        final int leftPad;
        final int rightPad;
        final int topPad;
        final int bottomPad;
        /** 电荷上标文本（如"⁺"、"²⁻"）及其宽度 */
        final String chargeText;
        final int chargeWidth;
        final int[] labelWidths;
        final int[] labelColors;
        /** 键线裁剪用的标签半宽/半高（骨架式无标签顶点用极小值） */
        final float[] clipHalfW;
        final float[] clipHalfH;

        Entry(CompositionStructureTooltipComponent.StructureEntry data, Font font) {
            this.structure = data.structure();
            this.charge = data.charge();
            List<Atom> atoms = structure.atoms();
            this.labelWidths = new int[atoms.size()];
            this.labelColors = new int[atoms.size()];
            this.clipHalfW = new float[atoms.size()];
            this.clipHalfH = new float[atoms.size()];
            for (int i = 0; i < atoms.size(); i++) {
                String label = atoms.get(i).label();
                if (label.isEmpty()) {
                    // 骨架式顶点：无文字，键线直达顶点（裁剪量为0，保证环等结构闭合）
                    clipHalfW[i] = 0;
                    clipHalfH[i] = 0;
                } else {
                    labelWidths[i] = font.width(label);
                    labelColors[i] = ElementColors.getColor(colorSymbol(label));
                    clipHalfW[i] = labelWidths[i] / 2.0f + 2;
                    clipHalfH[i] = font.lineHeight / 2.0f + 1;
                }
            }
            // 动态内边距：取边缘原子标签半宽+1px余量，括号紧贴内容不留大空隙
            float lp = 1, rp = 1, tp = 1, bp = 1;
            for (int i = 0; i < atoms.size(); i++) {
                Atom atom = atoms.get(i);
                if (atom.x() == structure.minX()) lp = Math.max(lp, clipHalfW[i]);
                if (atom.x() == structure.maxX()) rp = Math.max(rp, clipHalfW[i]);
                if (atom.y() == structure.minY()) tp = Math.max(tp, clipHalfH[i]);
                if (atom.y() == structure.maxY()) bp = Math.max(bp, clipHalfH[i]);
            }
            this.leftPad = (int) Math.ceil(lp) + 1;
            this.rightPad = (int) Math.ceil(rp) + 1;
            this.topPad = (int) Math.ceil(tp) + 1;
            this.bottomPad = (int) Math.ceil(bp) + 1;
            int spanW = (int) Math.ceil((structure.maxX() - structure.minX()) * CELL);
            int spanH = (int) Math.ceil((structure.maxY() - structure.minY()) * CELL);
            this.height = spanH + topPad + bottomPad;
            if (charge != 0) {
                // 布局：[括号] + 内边距 + 结构 + 内边距 + [括号] + 右上角电荷上标
                this.chargeText = chargeSuperscript(charge);
                this.chargeWidth = font.width(chargeText);
                this.width = BRACKET_INSET * 2 + leftPad + spanW + rightPad;
                this.totalWidth = width + chargeWidth + 1;
            } else {
                this.chargeText = "";
                this.chargeWidth = 0;
                this.width = leftPad + spanW + rightPad;
                this.totalWidth = width;
            }
        }
    }

    private final List<Entry> entries;
    private final int width;
    private final int height;

    public ClientMoleculeStructureTooltipComponent(CompositionStructureTooltipComponent data) {
        Font font = Minecraft.getInstance().font;
        this.entries = new ArrayList<>();
        int totalW = 0;
        int maxH = 0;
        for (CompositionStructureTooltipComponent.StructureEntry entryData : data.structures()) {
            Entry entry = new Entry(entryData, font);
            entries.add(entry);
            totalW += entry.totalWidth;
            maxH = Math.max(maxH, entry.height);
        }
        this.width = totalW + Math.max(0, entries.size() - 1) * STRUCTURE_GAP;
        this.height = maxH;
    }

    /** 从标签文本提取首个元素符号（大写+可选小写）作为染色依据，如"CH3"→"C"、"OH"→"O" */
    private static String colorSymbol(String label) {
        if (label.isEmpty()) return label;
        int end = 1;
        if (label.length() > 1 && Character.isLowerCase(label.charAt(1))) end = 2;
        return label.substring(0, end);
    }

    @Override
    public int getWidth(Font font) {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics guiGraphics) {
        // 多结构式水平并排，各自在组件高度内垂直居中
        int cursorX = x;
        for (Entry entry : entries) {
            renderStructure(entry, font, cursorX, y + (height - entry.height) / 2, guiGraphics);
            cursorX += entry.totalWidth + STRUCTURE_GAP;
        }
    }

    /** 在指定左上角绘制单个结构式（离子结构绘制通高黄色"[ ]"括号与右上角电荷上标） */
    private void renderStructure(Entry entry, Font font, int x, int y, GuiGraphics guiGraphics) {
        MoleculeStructure structure = entry.structure;
        List<Atom> atoms = structure.atoms();
        if (atoms.isEmpty()) return;
        // 结构本体水平起始位置（离子结构在左括号之后）
        int contentX = entry.charge != 0 ? x + BRACKET_INSET : x;
        // 网格坐标 → 像素坐标（结构式左上角偏移）
        float ox = contentX + entry.leftPad - structure.minX() * CELL;
        float oy = y + entry.topPad - structure.minY() * CELL;
        float[] pxs = new float[atoms.size()];
        float[] pys = new float[atoms.size()];
        for (int i = 0; i < atoms.size(); i++) {
            pxs[i] = ox + atoms.get(i).x() * CELL;
            pys[i] = oy + atoms.get(i).y() * CELL;
        }

        Matrix4f matrix = guiGraphics.pose().last().pose();
        // 用细长四边形画键线（GL_LINES在GUI中不可靠）
        BufferBuilder lines = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (Bond bond : structure.bonds()) {
            float[] seg = clipBond(entry, pxs[bond.a()], pys[bond.a()], pxs[bond.b()], pys[bond.b()],
                    bond.a(), bond.b());
            if (seg == null) continue;
            if (bond.type() == BondType.DOUBLE) {
                double dx = seg[2] - seg[0], dy = seg[3] - seg[1];
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len < 1.0e-4) continue;
                float nx = (float) (-dy / len * DOUBLE_BOND_OFFSET);
                float ny = (float) (dx / len * DOUBLE_BOND_OFFSET);
                addQuadLine(lines, matrix, seg[0] + nx, seg[1] + ny, seg[2] + nx, seg[3] + ny, BOND_COLOR);
                addQuadLine(lines, matrix, seg[0] - nx, seg[1] - ny, seg[2] - nx, seg[3] - ny, BOND_COLOR);
            } else {
                addQuadLine(lines, matrix, seg[0], seg[1], seg[2], seg[3], BOND_COLOR);
            }
        }
        // 苯环凯库勒式环内双键（交替三条内侧平行短线）
        for (RingLine line : structure.ringLines()) {
            addQuadLine(lines, matrix,
                    ox + line.x1() * CELL, oy + line.y1() * CELL,
                    ox + line.x2() * CELL, oy + line.y2() * CELL, BOND_COLOR);
        }

        // 离子括号：与内容等高的黄色"[ ]"（竖线+上下短划线，与键线同一渲染路径）
        if (entry.charge != 0) {
            float top = y + 0.5f, bottom = y + entry.height - 1.5f;
            float lx = x + 0.5f, rx = x + entry.width - 1.5f;
            addQuadLine(lines, matrix, lx, top, lx, bottom, BRACKET_COLOR);
            addQuadLine(lines, matrix, lx, top, lx + BRACKET_TICK, top, BRACKET_COLOR);
            addQuadLine(lines, matrix, lx, bottom, lx + BRACKET_TICK, bottom, BRACKET_COLOR);
            addQuadLine(lines, matrix, rx, top, rx, bottom, BRACKET_COLOR);
            addQuadLine(lines, matrix, rx - BRACKET_TICK, top, rx, top, BRACKET_COLOR);
            addQuadLine(lines, matrix, rx - BRACKET_TICK, bottom, rx, bottom, BRACKET_COLOR);
        }

        // 上传键线，再绘制原子标签（文字后渲染，压在键线上方）
        // 无键线顶点时 build() 返回 null，跳过上传避免 BufferBuilder was empty 崩溃
        var mesh = lines.build();
        if (mesh != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferUploader.drawWithShader(mesh);
            RenderSystem.enableCull();
        }

        for (int i = 0; i < atoms.size(); i++) {
            String label = atoms.get(i).label();
            if (label.isEmpty()) continue; // 骨架式顶点无标签
            guiGraphics.drawString(font, label,
                    (int) (pxs[i] - entry.labelWidths[i] / 2.0f),
                    (int) (pys[i] - font.lineHeight / 2.0f), entry.labelColors[i]);
        }
        // 电荷上标：右括号上角外侧（略高于括号顶端，呈上标样式）
        if (entry.charge != 0) {
            guiGraphics.drawString(font, entry.chargeText,
                    x + entry.width, y - font.lineHeight / 2 + 2, BRACKET_COLOR);
        }
    }

    /**
     * 将键线段两端收缩到标签矩形边缘（避免键线穿过文字）。
     * 标签以原子坐标为中心，半宽=文字宽/2+2，半高=行高/2+1。
     *
     * @return 收缩后的{x1,y1,x2,y2}；两端几乎相接时返回null
     */
    private float[] clipBond(Entry entry, float ax, float ay, float bx, float by, int ia, int ib) {
        float dx = bx - ax, dy = by - ay;
        float tStart = exitParam(dx, dy, entry.clipHalfW[ia], entry.clipHalfH[ia]);
        float tEnd = 1 - exitParam(-dx, -dy, entry.clipHalfW[ib], entry.clipHalfH[ib]);
        if (tEnd - tStart < 0.05f) return null;
        return new float[]{ax + dx * tStart, ay + dy * tStart, ax + dx * tEnd, ay + dy * tEnd};
    }

    /** 从中心出发沿方向(dx,dy)离开"半宽hx、半高hy"矩形的参数t（中心为t=0） */
    private static float exitParam(float dx, float dy, float hx, float hy) {
        float t = Float.MAX_VALUE;
        if (dx > 1.0e-6f) t = Math.min(t, hx / dx);
        else if (dx < -1.0e-6f) t = Math.min(t, hx / -dx);
        if (dy > 1.0e-6f) t = Math.min(t, hy / dy);
        else if (dy < -1.0e-6f) t = Math.min(t, hy / -dy);
        return t == Float.MAX_VALUE ? 0 : t;
    }

    /** 用细长四边形（宽约1px）绘制一条引线，两端点任意，颜色可指定 */
    private static void addQuadLine(BufferBuilder builder, Matrix4f matrix,
                                    float x1, float y1, float x2, float y2, int color) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0e-4) return;
        float half = 0.5f; // 线宽一半（约1px细线）
        float px = (float) (-dy / len * half);
        float py = (float) (dx / len * half);
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        builder.addVertex(matrix, x1 + px, y1 + py, 0).setColor(r, g, b, 255);
        builder.addVertex(matrix, x1 - px, y1 - py, 0).setColor(r, g, b, 255);
        builder.addVertex(matrix, x2 - px, y2 - py, 0).setColor(r, g, b, 255);
        builder.addVertex(matrix, x2 + px, y2 + py, 0).setColor(r, g, b, 255);
    }
}
