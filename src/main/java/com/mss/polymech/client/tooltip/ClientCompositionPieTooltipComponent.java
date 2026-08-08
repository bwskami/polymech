package com.mss.polymech.client.tooltip;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.tooltip.CompositionPieTooltipComponent;
import com.mss.polymech.tooltip.CompositionPieTooltipComponent.Slice;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * 成分饼图tooltip组件的客户端渲染实现（参考GregTech的成分饼图样式）。
 * <p>
 * 布局：左侧为图例列表（"百分比 + 彩色元素符号"，按质量占比降序），
 * 右侧为按相同比例绘制的饼图，扇区颜色与图例一一对应；
 * 占比足够大的扇区内部额外绘制"百分比 + 元素符号"标签。
 * </p>
 */
public class ClientCompositionPieTooltipComponent implements ClientTooltipComponent {

    /** 饼图直径（像素） */
    private static final int PIE_DIAMETER = 60;
    /** 图例与饼图之间的间距 */
    private static final int LEGEND_GAP = 8;
    /** 图例单行高度 */
    private static final int ROW_HEIGHT = 10;
    /** 扇区内绘制标签的最小占比阈值（%） */
    private static final double LABEL_THRESHOLD = 5.0;

    private final List<Slice> slices;
    private final int legendWidth;
    private final int height;

    public ClientCompositionPieTooltipComponent(CompositionPieTooltipComponent data) {
        this.slices = data.slices();
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (Slice slice : slices) {
            width = Math.max(width, font.width(legendLine(slice)));
        }
        this.legendWidth = width;
        this.height = Math.max(slices.size() * ROW_HEIGHT, PIE_DIAMETER);
    }

    /** 图例行文本："百分比"灰色 + "元素符号"元素颜色 */
    private static Component legendLine(Slice slice) {
        MutableComponent line = Component.literal(String.format(Locale.ROOT, "%.1f%%", slice.pct()))
                .withStyle(ChatFormatting.GRAY);
        line.append(Component.literal(" " + slice.symbol())
                .withStyle(style -> style.withColor(slice.color())));
        return line;
    }

    @Override
    public int getWidth(Font font) {
        return legendWidth + LEGEND_GAP + PIE_DIAMETER;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics guiGraphics) {
        // 左侧图例（垂直居中于饼图）
        int legendTop = y + (height - slices.size() * ROW_HEIGHT) / 2;
        for (int i = 0; i < slices.size(); i++) {
            guiGraphics.drawString(font, legendLine(slices.get(i)), x, legendTop + i * ROW_HEIGHT + 1, 0xFFFFFF);
        }
        // 右侧饼图
        float radius = PIE_DIAMETER / 2.0f;
        float cx = x + legendWidth + LEGEND_GAP + radius;
        float cy = y + height / 2.0f;
        guiGraphics.flush();
        drawPie(guiGraphics, cx, cy, radius);
        drawLabels(font, guiGraphics, cx, cy, radius);
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
        for (Slice slice : slices) {
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

    /** 大扇区（占比≥阈值）内部绘制"百分比 + 元素符号"标签 */
    private void drawLabels(Font font, GuiGraphics guiGraphics, float cx, float cy, float radius) {
        double angle = -Math.PI / 2;
        for (Slice slice : slices) {
            double sweep = slice.pct() / 100.0 * Math.PI * 2;
            if (slice.pct() >= LABEL_THRESHOLD) {
                double mid = angle + sweep / 2;
                String text = String.format(Locale.ROOT, "%.1f%% %s", slice.pct(), slice.symbol());
                float lx = (float) (cx + Math.cos(mid) * radius * 0.6) - font.width(text) / 2.0f;
                float ly = (float) (cy + Math.sin(mid) * radius * 0.6) - font.lineHeight / 2.0f;
                guiGraphics.drawString(font, text, (int) lx, (int) ly, 0xFFFFFF);
            }
            angle += sweep;
        }
    }
}
