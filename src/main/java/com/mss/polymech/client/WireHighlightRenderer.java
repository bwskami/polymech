package com.mss.polymech.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.GridNodes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 电线描边高亮渲染器（剪线钳 / 钳形表共用）。
 * <p>
 * 在 {@link RenderLevelStageEvent.Stage#AFTER_PARTICLES} 阶段把目标电线
 * 渲染为外暗内亮的双层发光管。
 * </p>
 */
public final class WireHighlightRenderer {

    private static final int CROSS_SECTION_SEGMENTS = 8;

    private WireHighlightRenderer() {}

    public static void render(RenderLevelStageEvent event, GridConnection connection) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        Vec3 p1 = GridNodes.getNodePosition(mc.level, connection.node1());
        Vec3 p2 = GridNodes.getNodePosition(mc.level, connection.node2());
        if (p1 == null || p2 == null)
            return;

        List<Vec3> path = new ArrayList<>(GridNodes.cablePoints(
                p1, p2, connection.wireType().getSag(), 0.5F));
        if (path.isEmpty())
            return;
        if (path.get(path.size() - 1).distanceToSqr(p2) > 1.0e-8)
            path.add(p2);

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 外圈深色描边 + 内圈高亮，两层叠出“发光描边”感
        renderTube(matrix, path,
                connection.wireType().getThickness() + 0.05F, 0.35F, 0.05F, 0.05F, 0.55F);
        renderTube(matrix, path,
                connection.wireType().getThickness() + 0.02F, 1.0F, 0.78F, 0.35F, 0.70F);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    private static void renderTube(Matrix4f matrix, List<Vec3> path, float radius,
                                   float r, float g, float b, float a) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec3 aPos = path.get(i);
            Vec3 bPos = path.get(i + 1);
            appendSegment(buf, matrix, aPos, bPos, radius, r, g, b, a);
        }
        BufferUploader.drawWithShader(buf.buildOrThrow());
    }

    private static void appendSegment(BufferBuilder buf, Matrix4f matrix, Vec3 a, Vec3 b,
                                      float radius, float r, float g, float bl, float aAlpha) {
        Vec3 dir = b.subtract(a);
        if (dir.lengthSqr() < 1.0e-8)
            return;
        dir = dir.normalize();

        Vec3 up = Math.abs(dir.y) < 0.99 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 u = dir.cross(up).normalize();
        Vec3 v = u.cross(dir).normalize();

        float[] ax = new float[CROSS_SECTION_SEGMENTS];
        float[] ay = new float[CROSS_SECTION_SEGMENTS];
        float[] az = new float[CROSS_SECTION_SEGMENTS];
        float[] bx = new float[CROSS_SECTION_SEGMENTS];
        float[] by = new float[CROSS_SECTION_SEGMENTS];
        float[] bz = new float[CROSS_SECTION_SEGMENTS];

        for (int i = 0; i < CROSS_SECTION_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / CROSS_SECTION_SEGMENTS;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            ax[i] = (float) (a.x + radius * (cos * u.x + sin * v.x));
            ay[i] = (float) (a.y + radius * (cos * u.y + sin * v.y));
            az[i] = (float) (a.z + radius * (cos * u.z + sin * v.z));
            bx[i] = (float) (b.x + radius * (cos * u.x + sin * v.x));
            by[i] = (float) (b.y + radius * (cos * u.y + sin * v.y));
            bz[i] = (float) (b.z + radius * (cos * u.z + sin * v.z));
        }

        for (int i = 0; i < CROSS_SECTION_SEGMENTS; i++) {
            int j = (i + 1) % CROSS_SECTION_SEGMENTS;
            addVertex(buf, matrix, ax[i], ay[i], az[i], r, g, bl, aAlpha);
            addVertex(buf, matrix, ax[j], ay[j], az[j], r, g, bl, aAlpha);
            addVertex(buf, matrix, bx[j], by[j], bz[j], r, g, bl, aAlpha);
            addVertex(buf, matrix, bx[i], by[i], bz[i], r, g, bl, aAlpha);
        }
    }

    private static void addVertex(BufferBuilder buf, Matrix4f matrix,
                                  float x, float y, float z, float r, float g, float b, float a) {
        buf.addVertex(matrix, x, y, z).setColor(r, g, b, a);
    }
}
