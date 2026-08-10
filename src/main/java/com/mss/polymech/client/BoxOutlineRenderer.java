package com.mss.polymech.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix4f;

/**
 * 实心闭合选择框描边渲染工具（全模组标准化）。
 * <p>
 * <b>棱（{@link #addBar}）</b>：以棱方向为轴，4 个侧面围成截面 hw×hw 的实心方条；
 * <b>顶点（{@link #addCorner}）</b>：8 个角点各放一个边长 2hw 的实心小立方体衔接。
 * 棱端实心正方形截面与角点立方体面完全对齐，从任何视角都看不到
 * 十字截面开口或顶点空洞（替代旧的十字双平面 quad 方案与 1px 细线框）。
 * 所有面均双面提交，任何绕序/剔除状态下都可见。
 * </p>
 * <p>
 * 入参 {@link VertexConsumer} 兼容两条提交通道：Tesselator 的 BufferBuilder
 * （配合 drawWithShader 直接绘制）与 MultiBufferSource 的 RenderType buffer
 * （如 {@code RenderType.debugQuads()}，用于方块实体渲染阶段）。
 * </p>
 */
public final class BoxOutlineRenderer {

    private BoxOutlineRenderer() {
    }

    /**
     * 提交一条实心方条棱：以棱方向为轴、截面为 hw×hw 正方形的 4 侧面柱体（每面双面提交）。
     */
    public static void addBar(VertexConsumer buf, Matrix4f matrix,
                              float x1, float y1, float z1,
                              float x2, float y2, float z2,
                              float hw, float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6F)
            return;
        // 单位方向向量
        float ux = dx / len, uy = dy / len, uz = dz / len;
        // 构造垂直于方向的截面正交基 v、w（v×w = 方向）
        float vx, vy, vz;
        if (Math.abs(uy) < 0.99F) {
            vx = 0;
            vy = 1;
            vz = 0; // 参考世界 Y
        } else {
            vx = 1;
            vy = 0;
            vz = 0; // 方向接近 Y 时参考 X
        }
        // v = normalize(dir × up)
        float tvx = uy * vz - uz * vy;
        float tvy = uz * vx - ux * vz;
        float tvz = ux * vy - uy * vx;
        float tl = (float) Math.sqrt(tvx * tvx + tvy * tvy + tvz * tvz);
        if (tl < 1.0e-6F)
            return;
        tvx /= tl;
        tvy /= tl;
        tvz /= tl;
        // w = dir × v
        float wx = uy * tvz - uz * tvy;
        float wy = uz * tvx - ux * tvz;
        float wz = ux * tvy - uy * tvx;

        // 截面四角（乘 hw 后才是实际半宽偏移）：c1=-v-w, c2=+v-w, c3=+v+w, c4=-v+w
        float c1x = (-tvx - wx) * hw, c1y = (-tvy - wy) * hw, c1z = (-tvz - wz) * hw;
        float c2x = (tvx - wx) * hw, c2y = (tvy - wy) * hw, c2z = (tvz - wz) * hw;
        float c3x = (tvx + wx) * hw, c3y = (tvy + wy) * hw, c3z = (tvz + wz) * hw;
        float c4x = (-tvx + wx) * hw, c4y = (-tvy + wy) * hw, c4z = (-tvz + wz) * hw;

        // 4 个侧面（双面提交，任何绕序/剔除状态下均可见）
        // 面 -w（c1-c2）
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        // 面 +w（c4-c3）
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
        // 面 -v（c1-c4）
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c4x, y1 + c4y, z1 + c4z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c1x, y1 + c1y, z1 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c1x, y2 + c1y, z2 + c1z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c4x, y2 + c4y, z2 + c4z).setColor(r, g, b, a);
        // 面 +v（c2-c3）
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c3x, y1 + c3y, z1 + c3z).setColor(r, g, b, a);
        buf.addVertex(matrix, x1 + c2x, y1 + c2y, z1 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c2x, y2 + c2y, z2 + c2z).setColor(r, g, b, a);
        buf.addVertex(matrix, x2 + c3x, y2 + c3y, z2 + c3z).setColor(r, g, b, a);
    }

    /**
     * 提交一个实心小立方体（6 面双面），用于衔接棱端、闭合框体顶点。
     */
    public static void addCorner(VertexConsumer buf, Matrix4f matrix,
                                 float cx, float cy, float cz,
                                 float hw, float r, float g, float b, float a) {
        float x1 = cx - hw, y1 = cy - hw, z1 = cz - hw;
        float x2 = cx + hw, y2 = cy + hw, z2 = cz + hw;
        // +Z
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        // -Z
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        // +X
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        // -X
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        // +Y
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        // -Y
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y1, z1).setColor(r, g, b, a);
    }

    /**
     * 一次提交整个盒子的 12 条实心方条棱 + 8 个角点立方体（框体完全闭合）。
     *
     * @param lineWidth 棱宽（棱截面边长 = 角点立方体边长 = lineWidth）
     */
    public static void appendBox(VertexConsumer buf, Matrix4f matrix,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float lineWidth, float r, float g, float b, float a) {
        float hw = lineWidth / 2.0F;
        // 底面
        addBar(buf, matrix, x1, y1, z1, x2, y1, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z1, x2, y1, z2, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z2, x1, y1, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y1, z2, x1, y1, z1, hw, r, g, b, a);
        // 顶面
        addBar(buf, matrix, x1, y2, z1, x2, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y2, z1, x2, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x2, y2, z2, x1, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y2, z2, x1, y2, z1, hw, r, g, b, a);
        // 垂直边
        addBar(buf, matrix, x1, y1, z1, x1, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z1, x2, y2, z1, hw, r, g, b, a);
        addBar(buf, matrix, x2, y1, z2, x2, y2, z2, hw, r, g, b, a);
        addBar(buf, matrix, x1, y1, z2, x1, y2, z2, hw, r, g, b, a);
        // 8 个角点实心立方体衔接
        addCorner(buf, matrix, x1, y1, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y1, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y1, z2, hw, r, g, b, a);
        addCorner(buf, matrix, x1, y1, z2, hw, r, g, b, a);
        addCorner(buf, matrix, x1, y2, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y2, z1, hw, r, g, b, a);
        addCorner(buf, matrix, x2, y2, z2, hw, r, g, b, a);
        addCorner(buf, matrix, x1, y2, z2, hw, r, g, b, a);
    }
}
