package com.mss.polymech.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * 丝滑动画选择框（全模组标准化，仿 Create outliner）。
 * <p>
 * 一个实例 = 一个框：位置带 chase 指数平滑追赶（帧率无关，Create outliner 的
 * chaseAABB 手感）、新框 0.4s easeOutCubic 淡入；描边统一走
 * {@link BoxOutlineRenderer} 实心方条棱 + 角点立方体（任何视角完全闭合），
 * 可叠加半透明填充面。颜色/线宽/面透明度可通过 {@link #chase} 实时更新。
 * </p>
 * <p>
 * 入参 {@link VertexConsumer} 兼容两条提交通道：Tesselator 的 BufferBuilder
 * （配合 drawWithShader 直接绘制）与 MultiBufferSource 的 RenderType buffer
 * （如 {@code RenderType.debugQuads()}，用于方块实体渲染阶段）。
 * </p>
 * <p>
 * 使用模式：每帧为目标框调用 {@link #chase} 更新目标与样式，渲染前对每个框
 * 调用一次 {@link #tickChase} 推进动画，然后分两轮提交（先 {@link #appendFaces}
 * 半透明面、再 {@link #appendEdges} 亮边线，保证边线清晰可读）。
 * </p>
 */
public final class AnimatedOutline {

    /** 淡入时长（tick，0.4s） */
    public static final int FADE_IN_TICKS = 8;

    /** chase 追赶时间常数（秒）：框从旧位置滑向新目标的速度（越小越快，Create 手感约 0.1s） */
    public static final float CHASE_TIME_CONSTANT = 0.10F;

    /** 描边基准透明度（淡入完成后边线的恒定 alpha） */
    public static final float EDGE_ALPHA = 0.9F;

    /** 当前显示位置（chase 追赶中，渲染用） */
    private AABB box;
    /** 目标位置（每帧向此指数平滑追赶） */
    private AABB targetBox;
    /** 框颜色（ARGB，取 RGB 通道；A 通道由基准透明度与淡入共同决定） */
    private int color;
    private float lineWidth;
    private float faceAlpha;
    /** 出生 tick：新建时触发淡入 */
    private final long bornTick;
    /** 上次追赶更新的纳秒时间戳（帧率无关计时） */
    private long lastNano;

    public AnimatedOutline(AABB box, int color, float lineWidth, float faceAlpha, long bornTick) {
        this.box = box;
        this.targetBox = box;
        this.color = color;
        this.lineWidth = lineWidth;
        this.faceAlpha = faceAlpha;
        this.bornTick = bornTick;
        this.lastNano = System.nanoTime();
    }

    /** 更新追赶目标与样式：框从当前位置平滑滑向新目标（新建时淡入只触发一次） */
    public void chase(AABB target, int color, float lineWidth, float faceAlpha) {
        targetBox = target;
        this.color = color;
        this.lineWidth = lineWidth;
        this.faceAlpha = faceAlpha;
    }

    /** 每帧向目标指数平滑追赶一次（时间常数 {@link #CHASE_TIME_CONSTANT}，帧率无关） */
    public void tickChase() {
        if (box.equals(targetBox))
            return;
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1.0e9F;
        lastNano = now;
        if (dt <= 0)
            return;
        if (dt > 0.05F)
            dt = 0.05F; // 卡顿/暂停保护：超长间隔按 0.05s 算，避免瞬移
        float f = 1.0F - (float) Math.exp(-dt / CHASE_TIME_CONSTANT);
        if (f >= 0.999F) {
            box = targetBox;
            return;
        }
        box = new AABB(
                box.minX + (targetBox.minX - box.minX) * f,
                box.minY + (targetBox.minY - box.minY) * f,
                box.minZ + (targetBox.minZ - box.minZ) * f,
                box.maxX + (targetBox.maxX - box.maxX) * f,
                box.maxY + (targetBox.maxY - box.maxY) * f,
                box.maxZ + (targetBox.maxZ - box.maxZ) * f);
    }

    /** 当前透明度倍率（0.4s easeOutCubic 淡入动画） */
    public float alphaMul(long tick) {
        float t = Mth.clamp((tick - bornTick) / (float) FADE_IN_TICKS, 0, 1);
        return easeOutCubic(t);
    }

    /** easeOutCubic 缓动：0.4s 内透明度从 0 平滑升至目标值（Create outliner 同款感觉） */
    public static float easeOutCubic(float t) {
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** 追加 6 个半透明面（QUADS，逆时针绕序，背面剔除已关闭；faceAlpha<=0 时跳过） */
    public void appendFaces(VertexConsumer buf, Matrix4f matrix, long tick) {
        float a = faceAlpha * alphaMul(tick);
        if (a <= 0)
            return;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float minX = (float) box.minX, minY = (float) box.minY, minZ = (float) box.minZ;
        float maxX = (float) box.maxX, maxY = (float) box.maxY, maxZ = (float) box.maxZ;

        // +Z
        quad(buf, matrix, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);
        // -Z
        quad(buf, matrix, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, minX, minY, minZ, r, g, b, a);
        // +X
        quad(buf, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, a);
        // -X
        quad(buf, matrix, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, minX, minY, maxZ, r, g, b, a);
        // +Y
        quad(buf, matrix, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
        // -Y
        quad(buf, matrix, minX, minY, maxZ, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
    }

    /** 追加 12 条实心方条棱 + 8 个角点立方体（任何视角完全闭合，无十字截面穿帮） */
    public void appendEdges(VertexConsumer buf, Matrix4f matrix, long tick) {
        float a = EDGE_ALPHA * alphaMul(tick);
        if (a <= 0)
            return;
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float x1 = (float) box.minX, y1 = (float) box.minY, z1 = (float) box.minZ;
        float x2 = (float) box.maxX, y2 = (float) box.maxY, z2 = (float) box.maxZ;
        BoxOutlineRenderer.appendBox(buf, matrix, x1, y1, z1, x2, y2, z2, lineWidth, r, g, b, a);
    }

    /** 提交一个可能为空的 BufferBuilder：淡入第一帧 alpha=0 无顶点时安全跳过，避免 buildOrThrow 崩溃 */
    public static void drawIfNotEmpty(BufferBuilder buf) {
        var rendered = buf.build();
        if (rendered != null)
            BufferUploader.drawWithShader(rendered);
    }

    /** 提交一个四边形（POSITION_COLOR） */
    private static void quad(VertexConsumer buf, Matrix4f matrix,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float x4, float y4, float z4,
                             float r, float g, float b, float a) {
        buf.addVertex(matrix, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(matrix, x2, y2, z2).setColor(r, g, b, a);
        buf.addVertex(matrix, x3, y3, z3).setColor(r, g, b, a);
        buf.addVertex(matrix, x4, y4, z4).setColor(r, g, b, a);
    }
}
