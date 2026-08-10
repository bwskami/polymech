package com.mss.polymech.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mss.polymech.Polymech;
import com.mss.polymech.powergrid.GridConnection;
import com.mss.polymech.powergrid.GridNodes;
import com.mss.polymech.powergrid.GridWireType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 电线世界渲染器。
 * <p>
 * 在世界渲染的AFTER_TRANSLUCENT_BLOCKS阶段，把客户端缓存中的电网连接
 * 渲染为带弧垂的圆柱棱柱线段（顶点手写提交，不依赖Flywheel等第三方库），
 * 按电线类型区分粗细与颜色。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public class WireRenderer {

    /** 圆柱横截面顶点数（越大越圆润） */
    private static final int CROSS_SECTION_SEGMENTS = 8;

    /** 路径细分步长（格，越小曲线越平滑） */
    private static final float CABLE_DETAIL = 0.5f;

    /** 连接两端均距相机超过该距离（格）时跳过渲染 */
    private static final double CULL_DISTANCE = 64.0;

    /**
     * 电线纹理模板（用户制作的 8×4 单贴图）：
     * 上 8×2（V∈[0,0.5]）为裸线灰度绞线带，下 8×2（V∈[0.5,1.0]）为绝缘外皮基底，
     * 两者颜色均由顶点色tint提供，裸线/绝缘线按类型选择各自的V分区采样。
     */
    private static final ResourceLocation WIRE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/powergrid/wire_template.png");

    /**
     * 纹理平铺率：每 1 格电线长度对应的纹理 U 跨度。
     * <p>模板宽8 texel、绞线水平周期为4 texel；取1.0即每格平铺一整幅模板（2个绞线纹/格），
     * 保证横向绞线细节在电线上清晰可见。此前的1/16把1 texel拉成1格，
     * 绞线细节被拉伸再经mipmap模糊后完全丢失（电线看起来光滑无细节）。</p>
     */
    private static final float UV_PER_BLOCK = 1.0F;

    /**
     * U 按模板宽 [0,1] 循环（绞线水平周期4整除8，可无缝平铺）；
     * V 在对应半区内沿圆柱圆周扫过：裸线 [0,0.5]、绝缘 [0.5,1.0]。
     */

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        // 维度切换时清空旧缓存（等待服务端重新全量同步）
        ClientWireCache.ensureLevel((net.minecraft.client.multiplayer.ClientLevel) mc.level);
        List<GridConnection> connections = ClientWireCache.getAll();
        if (connections.isEmpty())
            return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = poseStack.last().pose();

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // 注意：实体类RenderType均为QUADS模式，必须按四边形提交顶点（此前按三角形提交导致
        // 顶点流每4个一组错位拼成破面，即“两条平行线+横档”鬼样子的真正根源）；
        // NoCull避免绕序/背面剔除问题，参考mod的wire_segment模型本身也走cutout系渲染；
        // 裸线/绝缘线按类型选不同模板贴图（buffer按连接逐条获取，同类型复用同一builder）
        for (GridConnection connection : connections) {
            renderConnection(mc, buffers, matrix, connection, cam);
        }

        poseStack.popPose();
        buffers.endBatch();
    }

    /** 渲染一条电线连接（抛物线弧垂路径 + 圆柱棱柱段；裸线/绝缘线按类型选模板） */
    private static void renderConnection(Minecraft mc, MultiBufferSource.BufferSource buffers, Matrix4f matrix,
                                         GridConnection connection, Vec3 cam) {
        Vec3 p1 = GridNodes.getNodePosition(mc.level, connection.node1());
        Vec3 p2 = GridNodes.getNodePosition(mc.level, connection.node2());
        if (p1 == null || p2 == null)
            return;
        if (p1.distanceTo(cam) > CULL_DISTANCE && p2.distanceTo(cam) > CULL_DISTANCE)
            return;

        // 裸线/绝缘线共用同一模板贴图，按类型选择V分区（buffer按连接逐条获取，复用同一builder）
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(WIRE_TEXTURE));
        float vStart = connection.wireType().isInsulated() ? 0.5F : 0.0F;

        List<Vec3> points = GridNodes.cablePoints(p1, p2, connection.wireType().getSag(), CABLE_DETAIL);
        if (points.size() < 2)
            return;
        // cablePoints不生成终点（与参考mod的QuadraticWireHelper相同）；参考mod在createWire里把
        // 最后一个点显式连到pos2，这里补上终点，否则被连接端缺最后一段、视觉上电线“没连到电杆上”
        List<Vec3> path = new ArrayList<>(points);
        if (path.get(path.size() - 1).distanceToSqr(p2) > 1.0e-8)
            path.add(p2);

        // 电线颜色：模板×金属色顶点tint，各金属色取自GridWireType.getColor()（绝缘变体已加深）
        int color = connection.wireType().getColor();
        float r = ((color >> 16) & 0xFF) / 255.0F;
        float g = ((color >> 8) & 0xFF) / 255.0F;
        float b = (color & 0xFF) / 255.0F;
        float radius = connection.wireType().getThickness();

        float travelled = 0.0F;
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec3 a = path.get(i);
            Vec3 b2 = path.get(i + 1);
            renderSegment(buffer, matrix, a, b2, radius, r, g, b, travelled, vStart);
            travelled += (float) a.distanceTo(b2);
        }
    }

    /**
     * 渲染一段棱柱（相邻两个路径点之间）。
     * <p>
     * U在整幅模板宽内循环；段跨过整数U边界（纹理循环一圈）时按边界细分为
     * 多个子段，各子段U保持在 (u, 1.0] 内，避免回绕把纹理在一个四边形内
     * “压缩”丢失绞线细节。
     * </p>
     */
    private static void renderSegment(VertexConsumer buffer, Matrix4f matrix,
                                      Vec3 a, Vec3 b, float radius,
                                      float r, float g, float bColor, float travelled, float vStart) {
        double len = b.subtract(a).length();
        if (len < 1.0e-6)
            return;

        float u0 = travelled * UV_PER_BLOCK;
        float u1 = u0 + (float) len * UV_PER_BLOCK;
        float cursor = u0;
        while (cursor < u1 - 1.0e-6F) {
            float next = Math.min((float) Math.floor(cursor) + 1.0F, u1);
            float span = u1 - u0;
            Vec3 sa = lerp(a, b, (cursor - u0) / span);
            Vec3 sb = lerp(a, b, (next - u0) / span);
            renderSubSegment(buffer, matrix, sa, sb, radius, r, g, bColor,
                    cursor - (float) Math.floor(cursor), next - (float) Math.floor(cursor), vStart);
            cursor = next;
        }
    }

    /** 两点间按分量线性插值 */
    private static Vec3 lerp(Vec3 a, Vec3 b, float t) {
        return new Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    /**
     * 渲染一个棱柱子段（U不跨边界：ua∈[0,1)、ub∈(ua,1.0]）。
     * <p>
     * 以方向向量为轴构造垂直平面正交基，横截面取
     * {@link #CROSS_SECTION_SEGMENTS} 个顶点构成棱柱侧面，
     * 每面按4顶点四边形提交（实体类RenderType为QUADS模式）。
     * </p>
     */
    private static void renderSubSegment(VertexConsumer buffer, Matrix4f matrix,
                                         Vec3 a, Vec3 b, float radius,
                                         float r, float g, float bColor, float ua, float ub, float vStart) {
        Vec3 dir = b.subtract(a);
        if (dir.length() < 1.0e-6)
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

            // 面法线取两顶点法线平均（横截面顶点法线）
            double midAngle = 2 * Math.PI * (i + 0.5) / CROSS_SECTION_SEGMENTS;
            float nx = (float) (Math.cos(midAngle) * u.x + Math.sin(midAngle) * v.x);
            float ny = (float) (Math.cos(midAngle) * u.y + Math.sin(midAngle) * v.y);
            float nz = (float) (Math.cos(midAngle) * u.z + Math.sin(midAngle) * v.z);

            // V在对应半区内沿圆周扫过：裸线[0,0.5]、绝缘[0.5,1.0]（模板8×4，上下各8×2）
            float va = vStart + i / (float) CROSS_SECTION_SEGMENTS * 0.5F;
            float vb = vStart + j / (float) CROSS_SECTION_SEGMENTS * 0.5F;

            // 四边形 a_i -> a_j -> b_j -> b_i（QUADS模式每面4顶点；此前按两个三角形提交6顶点，
            // 在QUADS缓冲里顶点流错位拼出破面，是电线渲染成“双线+横档”鬼样子的真正根源）
            addVertex(buffer, matrix, ax[i], ay[i], az[i], ua, va, nx, ny, nz, r, g, bColor);
            addVertex(buffer, matrix, ax[j], ay[j], az[j], ua, vb, nx, ny, nz, r, g, bColor);
            addVertex(buffer, matrix, bx[j], by[j], bz[j], ub, vb, nx, ny, nz, r, g, bColor);
            addVertex(buffer, matrix, bx[i], by[i], bz[i], ub, va, nx, ny, nz, r, g, bColor);
        }
    }

    /** 提交一个实体格式顶点（POSITION_COLOR_TEX_LIGHTMAP_OVERLAY + NORMAL，颜色为金属色tint：灰度纹理×顶点色） */
    private static void addVertex(VertexConsumer buffer, Matrix4f matrix,
                                  float x, float y, float z,
                                  float u, float v, float nx, float ny, float nz,
                                  float r, float g, float b) {
        buffer.addVertex(matrix, x, y, z)
                .setColor(r, g, b, 1.0F)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT)
                .setNormal(nx, ny, nz);
    }
}
