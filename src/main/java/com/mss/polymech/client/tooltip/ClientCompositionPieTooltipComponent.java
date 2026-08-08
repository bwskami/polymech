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
        int leftW = 0, rightW = 0, leftCount = 0, rightCount = 0;
        double angle = -Math.PI / 2; // 从正上方开始，顺时针
        for (Slice slice : data.slices()) {
            double sweep = slice.pct() / 100.0 * Math.PI * 2;
            double mid = angle + sweep / 2;
            boolean right = Math.cos(mid) >= 0; // 扇区中点位于右半平面则标签放右侧
            int lw = font.width(labelText(slice));
            if (right) {
                rightW = Math.max(rightW, lw);
                rightCount++;
            } else {
                leftW = Math.max(leftW, lw);
                leftCount++;
            }
            tmp.add(new Layout(slice, mid, right, lw, 0));
            angle += sweep;
        }
        this.leftLabelWidth = leftW;
        this.width = leftW + SIDE_GAP + PIE_DIAMETER + SIDE_GAP + rightW;
        this.height = Math.max(PIE_DIAMETER, Math.max(leftCount, rightCount) * ROW_HEIGHT);
        this.layouts = resolveLabelPositions(tmp);
    }

    /**
     * 解算标签垂直位置：目标高度为对应扇区弧边中点的高度（与图二一致），
     * 同侧标签若重叠则按最小行高推开，保证引线不共线、不交叉。
     */
    private List<Layout> resolveLabelPositions(List<Layout> tmp) {
        float top = ROW_HEIGHT / 2.0f;
        float bottom = height - ROW_HEIGHT / 2.0f;
        float[] resolved = new float[tmp.size()];
        resolveSide(tmp, resolved, true, top, bottom);
        resolveSide(tmp, resolved, false, top, bottom);
        List<Layout> result = new ArrayList<>();
        for (int i = 0; i < tmp.size(); i++) {
            Layout l = tmp.get(i);
            result.add(new Layout(l.slice(), l.midAngle(), l.rightSide(), l.labelWidth(), resolved[i]));
        }
        return result;
    }

    /** 单侧标签位置解算：目标y=弧边中点y，正向推开保证行距，越界时反向回收 */
    private void resolveSide(List<Layout> tmp, float[] resolved, boolean rightSide,
                             float top, float bottom) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < tmp.size(); i++) {
            if (tmp.get(i).rightSide() == rightSide) idx.add(i);
        }
        // 按目标高度自上而下排序，保证标签顺序与扇区位置一致
        idx.sort(Comparator.comparingDouble(k -> Math.sin(tmp.get(k).midAngle())));
        int n = idx.size();
        if (n == 0) return;
        double radius = PIE_DIAMETER / 2.0;
        double[] ys = new double[n];
        for (int k = 0; k < n; k++) {
            ys[k] = height / 2.0 + Math.sin(tmp.get(idx.get(k)).midAngle()) * radius;
        }
        // 正向推开：与上一个标签至少间隔一行
        for (int k = 0; k < n; k++) {
            double minY = k == 0 ? top : ys[k - 1] + ROW_HEIGHT;
            if (ys[k] < minY) ys[k] = minY;
        }
        // 越过下边界时反向回收
        if (ys[n - 1] > bottom) {
            ys[n - 1] = bottom;
            for (int k = n - 2; k >= 0; k--) {
                double maxY = ys[k + 1] - ROW_HEIGHT;
                if (ys[k] > maxY) ys[k] = maxY;
            }
        }
        for (int k = 0; k < n; k++) {
            resolved[idx.get(k)] = (float) ys[k];
        }
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
            // 引线终点：扇面半径70%处沿扇区中点角度方向的点
            float endX = (float) (cx + Math.cos(layout.midAngle()) * radius * 0.7);
            float endY = (float) (cy + Math.sin(layout.midAngle()) * radius * 0.7);
            // 标签垂直中心：目标即弧边中点高度，重叠时已推开
            float lineY = y + layout.labelCenterYRel();

            if (layout.rightSide()) {
                int tx = x + leftLabelWidth + SIDE_GAP + PIE_DIAMETER + SIDE_GAP;
                guiGraphics.drawString(font, text, tx,
                        (int) (lineY - font.lineHeight / 2.0f), LINE_COLOR);
                // L形引线：先水平后垂直，只拐一次弯
                addQuadLine(lines, matrix, tx - 2, lineY, endX, lineY);
                addQuadLine(lines, matrix, endX, lineY, endX, endY);
            } else {
                int tx = x + leftLabelWidth - layout.labelWidth(); // 右对齐
                guiGraphics.drawString(font, text, tx,
                        (int) (lineY - font.lineHeight / 2.0f), LINE_COLOR);
                // L形引线：先水平后垂直，只拐一次弯
                float labelEnd = x + leftLabelWidth + 2;
                addQuadLine(lines, matrix, labelEnd, lineY, endX, lineY);
                addQuadLine(lines, matrix, endX, lineY, endX, endY);
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

    /** 用细长四边形（宽约1.5px）绘制一条引线，两端点任意 */
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
