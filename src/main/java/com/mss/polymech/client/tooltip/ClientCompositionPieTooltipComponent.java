package com.mss.polymech.client.tooltip;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.tooltip.CompositionPieTooltipComponent;
import com.mss.polymech.tooltip.CompositionPieTooltipComponent.Slice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 成分饼图tooltip组件的客户端渲染实现（GregTech风格）。
 * <p>
 * 布局：饼图居中，各元素标签按其扇区位置分布在饼图<b>左侧或右侧</b>，
 * 每个标签用一条白色引线连接到对应扇区的弧边中点，直观指示归属。
 * 扇区从正上方开始按质量占比降序顺时针排列。
 * </p>
 */
public class ClientCompositionPieTooltipComponent implements ClientTooltipComponent {

    /** 饼图直径（像素） */
    private static final int PIE_DIAMETER = 60;
    /** 标签列与饼图之间的间距（含引线空间） */
    private static final int SIDE_GAP = 12;
    /** 标签单行高度 */
    private static final int ROW_HEIGHT = 10;
    /** 引线终点所在的半径比例（扇面半径的90%处） */
    private static final double END_RADIUS_RATIO = 0.9;
    /** 引线颜色（白色） */
    private static final int LINE_COLOR = 0xFFFFFF;

    /** 单个切片的布局信息（扇区中点角度、所在侧、标签宽度、解算后的标签垂直中心） */
    private record Layout(Slice slice, double midAngle, boolean rightSide, int labelWidth, float labelCenterYRel) {
    }

    private final List<Layout> layouts;
    private final int leftLabelWidth;
    private final int width;
    private final int height;

    public ClientCompositionPieTooltipComponent(CompositionPieTooltipComponent data) {
        Font font = Minecraft.getInstance().font;
        List<Layout> tmp = new ArrayList<>();
        int leftW = 0, rightW = 0;
        double angle = -Math.PI / 2; // 从正上方开始，顺时针
        for (Slice slice : data.slices()) {
            double sweep = slice.pct() / 100.0 * Math.PI * 2;
            double mid = angle + sweep / 2;
            boolean right = Math.cos(mid) >= 0; // 扇区中点位于右半平面则标签放右侧
            int lw = font.width(labelText(slice));
            if (right) rightW = Math.max(rightW, lw);
            else leftW = Math.max(leftW, lw);
            tmp.add(new Layout(slice, mid, right, lw, 0));
            angle += sweep;
        }
        this.leftLabelWidth = leftW;
        this.width = leftW + SIDE_GAP + PIE_DIAMETER + SIDE_GAP + rightW;
        // 以饼图圆心为原点解算标签垂直位置（相对坐标），
        // 再按最大扩展范围决定组件高度，保证被推开的标签也放得下
        double radius = PIE_DIAMETER / 2.0;
        float[] relY = new float[tmp.size()];
        double maxExtent = Math.max(resolveSide(tmp, relY, true, radius),
                resolveSide(tmp, relY, false, radius));
        this.height = Math.max(PIE_DIAMETER, (int) Math.ceil(2 * maxExtent) + ROW_HEIGHT);
        List<Layout> resolved = new ArrayList<>();
        for (int i = 0; i < tmp.size(); i++) {
            Layout l = tmp.get(i);
            resolved.add(new Layout(l.slice(), l.midAngle(), l.rightSide(), l.labelWidth(),
                    height / 2.0f + relY[i]));
        }
        this.layouts = resolved;
    }

    /**
     * 单侧标签垂直位置解算（相对饼图圆心，向下为正）。
     * <p>
     * 目标高度=引线终点y（对齐时为直连水平线）；标签因避让被推开时，
     * 沿远离圆心的方向推到圆外：要求 |y| > sqrt(r² - endX偏移²)，
     * 即L形拐点(endX, lineY)落在饼图圆外。同侧按目标高度排序推开，
     * 保证标签顺序与扇区一致、引线互不相交。
     * </p>
     *
     * @return 该侧最大垂直扩展范围（含半行高），用于确定组件高度
     */
    private double resolveSide(List<Layout> tmp, float[] relY, boolean rightSide, double radius) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < tmp.size(); i++) {
            if (tmp.get(i).rightSide() == rightSide) idx.add(i);
        }
        int n = idx.size();
        if (n == 0) return 0;
        // 按目标高度自上而下排序，保证标签顺序与扇区位置一致
        idx.sort(Comparator.comparingDouble(k -> Math.sin(tmp.get(k).midAngle())));
        double[] target = new double[n];
        double[] clear = new double[n]; // 圆心到“终点x处圆上弦”的垂直距离（拐点出圆的门槛）
        for (int k = 0; k < n; k++) {
            double mid = tmp.get(idx.get(k)).midAngle();
            target[k] = Math.sin(mid) * radius * END_RADIUS_RATIO;
            double dx = Math.cos(mid) * radius * END_RADIUS_RATIO;
            clear[k] = Math.sqrt(Math.max(0, radius * radius - dx * dx));
        }
        double[] ys = new double[n];
        // 下半区标签：自上而下处理，避让时向下（远离圆心）推开
        double prev = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < n; k++) {
            if (target[k] <= 0) continue;
            double yv = Math.max(target[k], prev + ROW_HEIGHT);
            if (yv - target[k] > 0.5) yv = Math.max(yv, clear[k] + 1); // 被推开→拐点必须在圆外
            ys[k] = yv;
            prev = yv;
        }
        // 上半区标签：自下而上处理（先离圆心近的），避让时向上推开
        prev = Double.POSITIVE_INFINITY;
        for (int k = n - 1; k >= 0; k--) {
            if (target[k] > 0) continue;
            double yv = Math.min(target[k], prev - ROW_HEIGHT);
            if (target[k] - yv > 0.5) yv = Math.min(yv, -(clear[k] + 1)); // 被推开→拐点必须在圆外
            ys[k] = yv;
            prev = yv;
        }
        double extent = 0;
        for (int k = 0; k < n; k++) {
            relY[idx.get(k)] = (float) ys[k];
            extent = Math.max(extent, Math.abs(ys[k]) + ROW_HEIGHT / 2.0);
        }
        return extent;
    }

    /** 标签文本："百分比 + 元素符号"，如"66.9% Fe" */
    private static String labelText(Slice slice) {
        return String.format(Locale.ROOT, "%.1f%% %s", slice.pct(), slice.symbol());
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
        float radius = PIE_DIAMETER / 2.0f;
        float cx = x + leftLabelWidth + SIDE_GAP + radius;
        float cy = y + height / 2.0f;

        guiGraphics.flush();
        drawPie(guiGraphics, cx, cy, radius);
        drawLabelsAndLines(font, guiGraphics, cx, cy, radius, x, y);
    }

    /** 按切片占比绘制扇区（每片一个TRIANGLE_FAN，避免顶点颜色插值串色） */
    private void drawPie(GuiGraphics guiGraphics, float cx, float cy, float radius) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // 屏幕空间顺时针绕序会被判定为背面，必须禁用剔除，否则扇区整体不可见
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        Matrix4f matrix = guiGraphics.pose().last().pose();
        double angle = -Math.PI / 2; // 从正上方开始，顺时针
        for (Layout layout : layouts) {
            Slice slice = layout.slice();
            double sweep = slice.pct() / 100.0 * Math.PI * 2;
            int segments = Math.max(2, (int) Math.ceil(sweep / (Math.PI / 16)));
            int r = (slice.color() >> 16) & 0xFF;
            int g = (slice.color() >> 8) & 0xFF;
            int b = slice.color() & 0xFF;
            BufferBuilder builder = Tesselator.getInstance()
                    .begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            builder.addVertex(matrix, cx, cy, 0).setColor(r, g, b, 255);
            for (int s = 0; s <= segments; s++) {
                double a = angle + sweep * s / segments;
                float vx = (float) (cx + Math.cos(a) * radius);
                float vy = (float) (cy + Math.sin(a) * radius);
                builder.addVertex(matrix, vx, vy, 0).setColor(r, g, b, 255);
            }
            BufferUploader.drawWithShader(builder.buildOrThrow());
            angle += sweep;
        }
        // 恢复默认GUI渲染状态，避免影响后续tooltip绘制
        RenderSystem.enableCull();
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
    }

    /** 绘制左右两侧标签及指向对应扇区弧边中点的白色正交引线 */
    private void drawLabelsAndLines(Font font, GuiGraphics guiGraphics, float cx, float cy,
                                    float radius, int x, int y) {
        Matrix4f matrix = guiGraphics.pose().last().pose();
        // 用细长四边形画引线（与饼图同一渲染路径，GL_LINES在GUI中不可靠）
        BufferBuilder lines = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (Layout layout : layouts) {
            String text = labelText(layout.slice());
            // 引线终点：扇面半径90%处沿扇区中点角度方向的弧边中点
            float endX = (float) (cx + Math.cos(layout.midAngle()) * radius * END_RADIUS_RATIO);
            float endY = (float) (cy + Math.sin(layout.midAngle()) * radius * END_RADIUS_RATIO);
            // 标签垂直中心：目标即引线终点高度（重叠时已推开）
            float lineY = y + layout.labelCenterYRel();

            if (layout.rightSide()) {
                int tx = x + leftLabelWidth + SIDE_GAP + PIE_DIAMETER + SIDE_GAP;
                guiGraphics.drawString(font, text, tx,
                        (int) (lineY - font.lineHeight / 2.0f), LINE_COLOR);
                drawLeaderLine(lines, matrix, tx - 2, lineY, endX, endY);
            } else {
                int tx = x + leftLabelWidth - layout.labelWidth(); // 右对齐
                guiGraphics.drawString(font, text, tx,
                        (int) (lineY - font.lineHeight / 2.0f), LINE_COLOR);
                drawLeaderLine(lines, matrix, x + leftLabelWidth + 2, lineY, endX, endY);
            }
        }

        // 先刷出文字，再画引线（引线压在文字上层无遮挡问题）
        guiGraphics.flush();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(lines.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
    }

    /**
     * 绘制单条引线（严格L形，最多拐一次弯，先横后竖）：
     * 与终点同高时直连（纯水平线，无拐弯）；
     * 标签因避让被推开时：先水平延伸到终点正上方/下方的x处，
     * 再垂直穿入扇面到达终点——拐点(endX, lineY)已由位置解算推到饼图圆外，
     * 水平段与标签同高不穿过圆，全程只拐一次弯。
     */
    private void drawLeaderLine(BufferBuilder lines, Matrix4f matrix,
                                float startX, float lineY, float endX, float endY) {
        if (Math.abs(lineY - endY) < 0.5f) {
            addQuadLine(lines, matrix, startX, lineY, endX, endY);
            return;
        }
        // L形：先横后竖，拐点(endX, lineY)在圆外
        addQuadLine(lines, matrix, startX, lineY, endX, lineY);
        addQuadLine(lines, matrix, endX, lineY, endX, endY);
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
        builder.addVertex(matrix, x1 + px, y1 + py, 0).setColor(255, 255, 255, 255);
        builder.addVertex(matrix, x1 - px, y1 - py, 0).setColor(255, 255, 255, 255);
        builder.addVertex(matrix, x2 - px, y2 - py, 0).setColor(255, 255, 255, 255);
        builder.addVertex(matrix, x2 + px, y2 + py, 0).setColor(255, 255, 255, 255);
    }
}
