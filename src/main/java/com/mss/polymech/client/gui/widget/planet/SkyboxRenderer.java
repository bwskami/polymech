package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/**
 * 共享天空盒渲染器：直接复用 GUI 星图同款 4x3 Unity cubemap 绘制逻辑，避免重复实现。
 */
public final class SkyboxRenderer {

    private SkyboxRenderer() {
    }

    public static void drawCubemap(Matrix4f mat, ResourceLocation texture) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        float r = 1000.0f;
        float cu = 1f / 4f;
        float cv = 1f / 3f;
        drawFace(mat, texture, cu, cv, cu * 2f, cv * 2f,
                -r, r, -r, r, r, -r, r, -r, -r, -r, -r, -r); // front
        drawFace(mat, texture, cu * 3f, cv, cu * 4f, cv * 2f,
                r, r, r, -r, r, r, -r, -r, r, r, -r, r); // back
        drawFace(mat, texture, 0f, cv, cu, cv * 2f,
                -r, r, r, -r, r, -r, -r, -r, -r, -r, -r, r); // left
        drawFace(mat, texture, cu * 2f, cv, cu * 3f, cv * 2f,
                r, r, -r, r, r, r, r, -r, r, r, -r, -r); // right
        drawFace(mat, texture, cu, 0f, cu * 2f, cv,
                -r, r, r, r, r, r, r, r, -r, -r, r, -r); // top
        drawFace(mat, texture, cu, cv * 2f, cu * 2f, cv * 3f,
                -r, -r, -r, r, -r, -r, r, -r, r, -r, -r, r); // bottom

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    /**
     * 调试纯色天空盒：用六种不同颜色替换 cubemap 的六个面。
     * 顶点布局与 {@link #drawCubemap} 完全一致，方便确认相机上下左右方向。
     * faceColors 顺序：front(+Z), back(-Z), left(-X), right(+X), top(+Y), bottom(-Y)
     * 每个元素为 int 0xRRGGBB 或 0xAARRGGBB。
     */
    public static void drawCubemapSolid(Matrix4f mat, int[] faceColors) {
        if (faceColors.length < 6) faceColors = new int[]{0xFF444444, 0xFF444444, 0xFF444444, 0xFF444444, 0xFF444444, 0xFF444444};
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        float r = 1000.0f;
        drawFaceColor(mat, faceColors[0], -r, r, -r, r, r, -r, r, -r, -r, -r, -r, -r); // front +Z 绿
        drawFaceColor(mat, faceColors[1], r, r, r, -r, r, r, -r, -r, r, r, -r, r); // back -Z 黄
        drawFaceColor(mat, faceColors[2], -r, r, r, -r, r, -r, -r, -r, -r, -r, -r, r); // left -X 品红
        drawFaceColor(mat, faceColors[3], r, r, -r, r, r, r, r, -r, r, r, -r, -r); // right +X 青
        drawFaceColor(mat, faceColors[4], -r, r, r, r, r, r, r, r, -r, -r, r, -r); // top +Y 红
        drawFaceColor(mat, faceColors[5], -r, -r, -r, r, -r, -r, r, -r, r, -r, -r, r); // bottom -Y 蓝

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
    }

    private static void drawFaceColor(Matrix4f mat, int color,
                                 float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 float dx, float dy, float dz) {
        float a = ((color >>> 24) & 0xFF) / 255f;
        float r = ((color >>> 16) & 0xFF) / 255f;
        float g = ((color >>> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(mat, ax, ay, az).setColor(r, g, b, a);
        bb.addVertex(mat, bx, by, bz).setColor(r, g, b, a);
        bb.addVertex(mat, cx, cy, cz).setColor(r, g, b, a);
        bb.addVertex(mat, ax, ay, az).setColor(r, g, b, a);
        bb.addVertex(mat, cx, cy, cz).setColor(r, g, b, a);
        bb.addVertex(mat, dx, dy, dz).setColor(r, g, b, a);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    private static void drawFace(Matrix4f mat, ResourceLocation texture,
                                 float u0, float v0, float u1, float v1,
                                 float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 float dx, float dy, float dz) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(mat, ax, ay, az).setUv(u0, v0);
        bb.addVertex(mat, bx, by, bz).setUv(u1, v0);
        bb.addVertex(mat, cx, cy, cz).setUv(u1, v1);
        bb.addVertex(mat, ax, ay, az).setUv(u0, v0);
        bb.addVertex(mat, cx, cy, cz).setUv(u1, v1);
        bb.addVertex(mat, dx, dy, dz).setUv(u0, v1);
        RenderSystem.setShaderTexture(0, texture);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }
}
