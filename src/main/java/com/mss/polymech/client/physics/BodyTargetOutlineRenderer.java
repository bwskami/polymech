package com.mss.polymech.client.physics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.AnimatedOutline;
import com.mss.polymech.client.BoxOutlineRenderer;
import com.mss.polymech.util.PartialTicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 物理体方块的<b>瞄准框</b>：准星瞄中体的哪一格，就把那格画金框、命中面填金色。
 *
 * <p><b>为什么要有它（2026-10-06 三轮实机争执的教训）</b>：原版对方块永远画黑色瞄准线框，
 * 玩家"点之前"就知道自己在哪个方块、哪个面上；物理体方块不是真方块、原版不给画，
 * 于是太空里瞄准结构时准星下<b>什么反馈都没有</b> —— 用户连着三轮把"贴脸那一面的下沿"
 * 看成"底面"（相机在底面上方 0.183 格、几乎水平，见 BodyVoxelRaycastProbe 的 H 组复算），
 * 争执其实卡在"看不见自己瞄的是哪面"。这个框一上，框亮哪面右键就沿哪面放，
 * "瞄的是底面还是侧面"在点之前就看得见。</p>
 *
 * <p><b>两条铁律</b>：</p>
 * <ul>
 *   <li><b>与点击同一条判据路</b>：目标来自 {@link PhysicsBodyInteractionClient#aimTarget}
 *       （{@code currentHit} 的无日志版 —— 每帧渲染走带日志那条会每秒刷几千行诊断）。
 *       渲染不许自己另写一条射线：框指一处、点击放另一处，比没有框更伤。</li>
 *   <li><b>与拾取同一个位姿口径</b>：体的显示位姿走显示环（{@code renderX/Y/Z/renderRotation}），
 *       与方块渲染、拾取射线同一个口径。刚体实时位姿是 10ms 一跳的阶梯，
 *       画框读它就是 2026-10-03 "框跳格"的老坑；射线起点那套同理（见 {@code ClientViewOrigin}）。</li>
 * </ul>
 *
 * <p>画法照 {@code NodeSelectionRenderer} 的两轮提交：先半透明面、再亮边线；
 * 盒框在<b>体的局部坐标系</b>里提交（pose 先平移体的显示位姿再乘它的旋转），
 * 所以体被牵引枪转过后框仍然套在那一格上、不会变成放大的外接盒。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class BodyTargetOutlineRenderer {

    /** 框与命中面配色（ARGB）：与 {@code NodeSelectionRenderer} 的"瞄准预览"同款金黄。 */
    private static final int COLOR = 0xFFF0C75E;
    private static final float R = ((COLOR >> 16) & 0xFF) / 255.0F;
    private static final float G = ((COLOR >> 8) & 0xFF) / 255.0F;
    private static final float B = (COLOR & 0xFF) / 255.0F;

    /** 棱宽（格）：全模组选择框统一的实心方条棱。 */
    private static final float LINE_WIDTH = 1 / 16F;

    /** 命中面填充透明度（边线沿用全模组基准 {@link AnimatedOutline#EDGE_ALPHA}）。 */
    private static final float FACE_ALPHA = 0.30F;

    /** 命中面沿法向外扩（格）：盖在面表面之上、防 Z-fighting。 */
    private static final float FACE_OFFSET = 0.0025F;

    private BodyTargetOutlineRenderer() {
    }

    /** 世界渲染阶段：与 {@code NodeSelectionRenderer}/{@code PhysicsBodyRenderer} 同层叠加。 */
    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        if (mc.options.hideGui || mc.player.isSpectator()) {
            return;
        }
        // 记下当帧部分刻：本渲染器与 PhysicsBodyRenderer 谁先跑都不许让 forDisplay()
        // 拿到上一帧的值，否则体移动时框比方块慢一帧、两者互相错开。
        PartialTicks.recordFrame(PartialTicks.of(event));

        // 瞄准目标：与点击判据完全同一条路（射线打空 / 原版更近都算没瞄上体，都不画框）
        PhysicsBodyInteractionClient.Hit hit = PhysicsBodyInteractionClient.aimTarget(mc.player);
        if (hit == null) {
            return;
        }

        // 那一体的显示位姿（与拾取同一个口径：显示环 + 同一帧 alpha）
        float pt = PartialTicks.forDisplay();
        ClientPhysicsWorld.ClientBody target = null;
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            if (body.id() == hit.bodyId()) {
                target = body;
                break;
            }
        }
        if (target == null) {
            return; // 体刚被移除：这一帧没有框，下一帧 aimTarget 自然打空
        }
        float[] rotF = new float[4];
        target.renderRotation(pt, rotF);
        Quaternionf rot = new Quaternionf(rotF[0], rotF[1], rotF[2], rotF[3]);
        if (!rot.isFinite() || rot.lengthSquared() == 0.0F) {
            return; // 与 castAgainstBodies 同一守卫：非法位姿不画
        }
        rot.normalize();

        // 命中面：包同款的**体局部**面（useOrPlace 就是在局部空间沿它取相邻格）
        Direction face = Direction.getNearest(hit.localFaceX(), hit.localFaceY(), hit.localFaceZ());
        if (face == null) {
            face = Direction.UP; // 与 useOrPlace 同一兜底
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(target.renderX(pt) - cam.x, target.renderY(pt) - cam.y, target.renderZ(pt) - cam.z);
        pose.mulPose(rot);
        Matrix4f matrix = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // 第一轮：命中面填充 —— "框亮哪面，右键就沿哪面放"的可视答案
        BufferBuilder faceBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        appendFaceQuad(faceBuf, matrix, hit.dx(), hit.dy(), hit.dz(), face, R, G, B, FACE_ALPHA);
        AnimatedOutline.drawIfNotEmpty(faceBuf);

        // 第二轮：那一格的亮边框（12 条实心方条棱 + 8 个角点，任何视角闭合）
        BufferBuilder edgeBuf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        BoxOutlineRenderer.appendBox(edgeBuf, matrix,
                hit.dx(), hit.dy(), hit.dz(),
                hit.dx() + 1, hit.dy() + 1, hit.dz() + 1,
                LINE_WIDTH, R, G, B, AnimatedOutline.EDGE_ALPHA);
        AnimatedOutline.drawIfNotEmpty(edgeBuf);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /**
     * 提交命中面的填充四边形（体局部坐标，沿面法向外扩 {@link #FACE_OFFSET}）。
     * 该 pass 已关背面剔除，绕序无所谓。
     */
    private static void appendFaceQuad(VertexConsumer buf, Matrix4f matrix,
                                       int dx, int dy, int dz, Direction face,
                                       float r, float g, float b, float a) {
        float x1 = dx, y1 = dy, z1 = dz;
        float x2 = dx + 1, y2 = dy + 1, z2 = dz + 1;
        float o = FACE_OFFSET;
        switch (face) {
            case DOWN -> quad(buf, matrix,
                    x1, y1 - o, z1, x2, y1 - o, z1, x2, y1 - o, z2, x1, y1 - o, z2, r, g, b, a);
            case UP -> quad(buf, matrix,
                    x1, y2 + o, z1, x2, y2 + o, z1, x2, y2 + o, z2, x1, y2 + o, z2, r, g, b, a);
            case NORTH -> quad(buf, matrix,
                    x1, y1, z1 - o, x1, y2, z1 - o, x2, y2, z1 - o, x2, y1, z1 - o, r, g, b, a);
            case SOUTH -> quad(buf, matrix,
                    x1, y1, z2 + o, x1, y2, z2 + o, x2, y2, z2 + o, x2, y1, z2 + o, r, g, b, a);
            case WEST -> quad(buf, matrix,
                    x1 - o, y1, z1, x1 - o, y2, z1, x1 - o, y2, z2, x1 - o, y1, z2, r, g, b, a);
            case EAST -> quad(buf, matrix,
                    x2 + o, y1, z1, x2 + o, y2, z1, x2 + o, y2, z2, x2 + o, y1, z2, r, g, b, a);
        }
    }

    /** 提交一个四边形（POSITION_COLOR）。 */
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
