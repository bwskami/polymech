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

import java.util.List;

/**
 * 分子结构式tooltip组件的客户端渲染实现（GT风格键线式）。
 * <p>
 * 键用黄色细线绘制（双键为两条平行线，苯环为凯库勒式交替环内双键），
 * 键的两端自动收缩到原子标签边缘；原子标签按元素周期表配色染色。
 * 先画键线（立即上传），后绘制文字压在键线上方。
 * </p>
 */
public class ClientMoleculeStructureTooltipComponent implements ClientTooltipComponent {

    /** 单个网格单位的像素尺寸（≈键长） */
    private static final int CELL = 12;
    /** 水平/垂直内边距（为边缘标签的文字宽度留白） */
    private static final int PAD_H = 20;
    private static final int PAD_V = 14;
    /** 键线颜色（GT黄） */
    private static final int BOND_R = 200, BOND_G = 180, BOND_B = 0;
    /** 双键两条线的间距一半 */
    private static final float DOUBLE_BOND_OFFSET = 1.5f;

    private final MoleculeStructure structure;
    private final Font font;
    private final int width;
    private final int height;
    private final int[] labelWidths;
    private final int[] labelColors;
    /** 键线裁剪用的标签半宽/半高（骨架式无标签顶点用极小值） */
    private final float[] clipHalfW;
    private final float[] clipHalfH;

    public ClientMoleculeStructureTooltipComponent(CompositionStructureTooltipComponent data) {
        this.structure = data.structure();
        this.font = Minecraft.getInstance().font;
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
        this.width = (int) Math.ceil((structure.maxX() - structure.minX()) * CELL) + PAD_H * 2;
        this.height = (int) Math.ceil((structure.maxY() - structure.minY()) * CELL) + PAD_V * 2;
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
        List<Atom> atoms = structure.atoms();
        if (atoms.isEmpty()) return;
        // 网格坐标 → 像素坐标（组件左上角偏移）
        float ox = x + PAD_H - structure.minX() * CELL;
        float oy = y + PAD_V - structure.minY() * CELL;
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
            float[] seg = clipBond(pxs[bond.a()], pys[bond.a()], pxs[bond.b()], pys[bond.b()],
                    bond.a(), bond.b());
            if (seg == null) continue;
            if (bond.type() == BondType.DOUBLE) {
                double dx = seg[2] - seg[0], dy = seg[3] - seg[1];
                double len = Math.sqrt(dx * dx + dy * dy);
                if (len < 1.0e-4) continue;
                float nx = (float) (-dy / len * DOUBLE_BOND_OFFSET);
                float ny = (float) (dx / len * DOUBLE_BOND_OFFSET);
                addQuadLine(lines, matrix, seg[0] + nx, seg[1] + ny, seg[2] + nx, seg[3] + ny);
                addQuadLine(lines, matrix, seg[0] - nx, seg[1] - ny, seg[2] - nx, seg[3] - ny);
            } else {
                addQuadLine(lines, matrix, seg[0], seg[1], seg[2], seg[3]);
            }
        }
        // 苯环凯库勒式环内双键（交替三条内侧平行短线）
        for (RingLine line : structure.ringLines()) {
            addQuadLine(lines, matrix,
                    ox + line.x1() * CELL, oy + line.y1() * CELL,
                    ox + line.x2() * CELL, oy + line.y2() * CELL);
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
                    (int) (pxs[i] - labelWidths[i] / 2.0f),
                    (int) (pys[i] - font.lineHeight / 2.0f), labelColors[i]);
        }
    }

    /**
     * 将键线段两端收缩到标签矩形边缘（避免键线穿过文字）。
     * 标签以原子坐标为中心，半宽=文字宽/2+2，半高=行高/2+1。
     *
     * @return 收缩后的{x1,y1,x2,y2}；两端几乎相接时返回null
     */
    private float[] clipBond(float ax, float ay, float bx, float by, int ia, int ib) {
        float dx = bx - ax, dy = by - ay;
        float tStart = exitParam(dx, dy, clipHalfW[ia], clipHalfH[ia]);
        float tEnd = 1 - exitParam(-dx, -dy, clipHalfW[ib], clipHalfH[ib]);
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

    /** 用细长四边形（宽约1px）绘制一条引线，两端点任意 */
    private static void addQuadLine(BufferBuilder builder, Matrix4f matrix,
                                    float x1, float y1, float x2, float y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0e-4) return;
        float half = 0.5f; // 线宽一半（约1px细线）
        float px = (float) (-dy / len * half);
        float py = (float) (dx / len * half);
        builder.addVertex(matrix, x1 + px, y1 + py, 0).setColor(BOND_R, BOND_G, BOND_B, 255);
        builder.addVertex(matrix, x1 - px, y1 - py, 0).setColor(BOND_R, BOND_G, BOND_B, 255);
        builder.addVertex(matrix, x2 - px, y2 - py, 0).setColor(BOND_R, BOND_G, BOND_B, 255);
        builder.addVertex(matrix, x2 + px, y2 + py, 0).setColor(BOND_R, BOND_G, BOND_B, 255);
    }
}
