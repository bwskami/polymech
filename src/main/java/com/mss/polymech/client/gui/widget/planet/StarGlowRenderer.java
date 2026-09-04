package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

/**
 * 恒星辉光/日冕渲染器（GUI 星图与太空维度共用）。
 *
 * <p>采用 3D 公告板：以恒星为中心、在相机空间 XY 平面上展开的径向渐变圆盘。
 * 调用方需保证传入的 modelView 与顶点所处坐标一致（GUI 星图传 idMat + 相机空间坐标；
 * 太空维度同样 push identity 后传相机空间坐标）。</p>
 */
public final class StarGlowRenderer {

    private StarGlowRenderer() {
    }

    /** 单层连续日冕：从内缘最亮，按 (1-t)^2 平滑衰减到外缘透明。 */
    public static void drawGlowHalo(Matrix4f mat, float cx3d, float cy3d, float cz3d,
                                    float innerR, float outerR, float alpha,
                                    float cr, float cg, float cb) {
        if (innerR <= 0f || outerR <= innerR) return;
        int radialSteps = 24;
        int segs = 72;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < radialSteps; i++) {
            float t0 = (float) i / radialSteps, t1 = (float) (i + 1) / radialSteps;
            float r0 = innerR + (outerR - innerR) * t0;
            float r1 = innerR + (outerR - innerR) * t1;
            float a0 = alpha * (1f - t0) * (1f - t0);
            float a1 = alpha * (1f - t1) * (1f - t1);
            for (int j = 0; j < segs; j++) {
                float ang0 = (float) (Math.PI * 2 * j / segs);
                float ang1 = (float) (Math.PI * 2 * (j + 1) / segs);
                float c00 = (float) Math.cos(ang0), s00 = (float) Math.sin(ang0);
                float c01 = (float) Math.cos(ang1), s01 = (float) Math.sin(ang1);
                bb.addVertex(mat, cx3d + c00 * r0, cy3d + s00 * r0, cz3d).setColor(cr, cg, cb, a0);
                bb.addVertex(mat, cx3d + c00 * r1, cy3d + s00 * r1, cz3d).setColor(cr, cg, cb, a1);
                bb.addVertex(mat, cx3d + c01 * r1, cy3d + s01 * r1, cz3d).setColor(cr, cg, cb, a1);
                bb.addVertex(mat, cx3d + c00 * r0, cy3d + s00 * r0, cz3d).setColor(cr, cg, cb, a0);
                bb.addVertex(mat, cx3d + c01 * r1, cy3d + s01 * r1, cz3d).setColor(cr, cg, cb, a1);
                bb.addVertex(mat, cx3d + c01 * r0, cy3d + s01 * r0, cz3d).setColor(cr, cg, cb, a0);
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** 3D 公告板发光圆盘：中心最亮，向边缘线性渐隐到 0。 */
    public static void drawGlowBillboard(Matrix4f mat, float cx3d, float cy3d, float cz3d,
                                         float worldR, float alpha,
                                         float cr, float cg, float cb) {
        if (worldR <= 0f) return;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(mat, cx3d, cy3d, cz3d).setColor(cr, cg, cb, alpha);
        int seg = 56;
        for (int j = 0; j <= seg; j++) {
            float a = (float) (Math.PI * 2 * j / seg);
            bb.addVertex(mat, cx3d + (float) Math.cos(a) * worldR,
                            cy3d + (float) Math.sin(a) * worldR, cz3d)
                    .setColor(cr, cg, cb, 0f);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }
}
