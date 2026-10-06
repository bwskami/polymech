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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * 物理体方块的<b>瞄准反馈</b>：准星压在哪一格、压在哪一面、右键会把方块放到哪一格 —— 点之前全部看得见。
 *
 * <p><b>为什么要有它</b>：原版对方块永远画黑色瞄准框，玩家"点之前"就知道自己在哪个方块、哪个面上；
 * 物理体方块不是真方块、原版不给画，于是太空里瞄准结构时准星下<b>什么反馈都没有</b>，
 * 玩家只能靠眼睛猜"我在哪个面上"。三轮实机争执（"瞄的是底面还是侧面"）都卡在这块缺失的反馈上。</p>
 *
 * <p><b>三层信息（2026-10-06 第二轮：只画整块框时用户反馈"无法得知到底是哪个面"）</b>：</p>
 * <ol>
 *   <li><b>命中格</b>：金色细框 —— 回答"我在哪一格"（与原版黑框等价的信息）。</li>
 *   <li><b>命中面</b>：该面<b>填充 + 四面粗边框</b> —— 回答"我压在哪一面"。
 *       只画整块框时六面一样，玩家无法区分；面边框把被瞄的那一面单独圈出来，
 *       斜看也认得出（这正是三轮争执缺的那一条信息）。</li>
 *   <li><b>放置预览</b>：目标格幽灵框 —— 回答"方块会出现在哪"。
 *       目标格 = 命中格 + 局部面，与服务端 {@code useOrPlace} 里原版
 *       {@code BlockPlaceContext} 的 {@code clickedPos.relative(face)} <b>同式</b>。
 *       能放画青、被占画红：被占时原版 {@code canPlace()} 为假、右键<b>静默失败</b>，
 *       红幽灵把"点了没反应"变成看得见的原因。</li>
 * </ol>
 *
 * <p><b>两条铁律</b>：</p>
 * <ul>
 *   <li><b>与点击同一条判据路</b>：目标来自 {@link PhysicsBodyInteractionClient#aimTarget}
 *       （{@code currentHit} 的无日志版 —— 每帧渲染走带日志那条会每秒刷几千行诊断）。
 *       渲染不许自己另写一条射线：框指一处、点击放另一处，比没有框更伤。</li>
 *   <li><b>与拾取同一个位姿口径</b>：体的显示位姿走显示环（{@code renderX/Y/Z/renderRotation}），
 *       与方块渲染、拾取射线同一个口径。刚体实时位姿是 10ms 一跳的阶梯，画框读它就是
 *       2026-10-03 "框跳格"的老坑；射线起点那套同理（见 {@code ClientViewOrigin}）。</li>
 * </ul>
 *
 * <p>画法照 {@code NodeSelectionRenderer} 的多轮提交：先半透明填充、再亮边线；
 * 全部在<b>体的局部坐标系</b>里提交（pose 先平移体的显示位姿再乘它的旋转），
 * 所以体被牵引枪转过后框仍然套在那一格上、不会变成放大的外接盒。</p>
 *
 * <p><b>已知偏差（诚实说明）</b>：命中格若是<b>可替换方块</b>（草、雪层、水…），
 * 原版会把方块放进命中格本身而不是它的外侧；预览按常规满格块算 {@code relative(face)}，
 * 这种情况会差一格。目标是普通建筑方块时（绝大多数）预览与落点一致。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class BodyTargetOutlineRenderer {

    /** 瞄准（命中格 + 命中面）配色：与 {@code NodeSelectionRenderer} 的"瞄准预览"同款金黄。 */
    private static final int COLOR_AIM = 0xFFF0C75E;
    /** 放置预览·能放：青。 */
    private static final int COLOR_OK = 0xFF5BC8F5;
    /** 放置预览·被占：红（与 {@code NodeSelectionRenderer} 的"不可连接"同款）。 */
    private static final int COLOR_BAD = 0xFFC5564D;

    private static final float[] AIM_RGB = rgb(COLOR_AIM);
    private static final float[] OK_RGB = rgb(COLOR_OK);
    private static final float[] BAD_RGB = rgb(COLOR_BAD);

    /** 命中格细框的半宽（格）：比面边框细，让"哪一面"比"哪一格"更抢眼。 */
    private static final float HIT_BOX_HW = 1 / 48F;
    /** 面边框 / 幽灵框的半宽（格）。 */
    private static final float BOLD_HW = 1 / 32F;

    /** 命中面填充透明度（够看清是哪一面，又不遮住方块本身的颜色）。 */
    private static final float FACE_ALPHA = 0.45F;
    /** 命中格细框透明度（压低：它是背景信息，主角是面和预览）。 */
    private static final float HIT_BOX_ALPHA = 0.45F;
    /** 放置预览·能放的填充透明度。 */
    private static final float GHOST_FILL_ALPHA = 0.22F;
    /** 放置预览·被占的填充透明度（更实：警示）。 */
    private static final float GHOST_BAD_FILL_ALPHA = 0.32F;

    /** 面填充/边框沿法向外扩（格）：盖在方块表面之上、防 Z-fighting。 */
    private static final float FACE_OFFSET = 0.004F;
    /** 幽灵框外扩（格）：目标格被占时它的面与已有方块的面共面，外扩一点点避开闪烁。 */
    private static final float GHOST_INFLATE = 0.003F;

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

        // 瞄准目标：与点击判据完全同一条路（射线打空 / 原版更近都算没瞄上体，都不画）
        PhysicsBodyInteractionClient.Hit hit = PhysicsBodyInteractionClient.aimTarget(mc.player);
        if (hit == null) {
            return;
        }

        // 那一体的显示位姿（与拾取同一个口径：显示环 + 同一帧 alpha）
        float pt = PartialTicks.forDisplay();
        ClientPhysicsWorld.ClientBody body = null;
        for (ClientPhysicsWorld.ClientBody candidate : ClientPhysicsWorld.bodies()) {
            if (candidate.id() == hit.bodyId()) {
                body = candidate;
                break;
            }
        }
        if (body == null) {
            return; // 体刚被移除：这一帧没有框，下一帧 aimTarget 自然打空
        }
        float[] rotF = new float[4];
        body.renderRotation(pt, rotF);
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
        // 放置预览格 = 命中格 + 局部面 —— 与服务端 BlockItem.place 的落点同式
        BlockPos targetCell = new BlockPos(hit.dx(), hit.dy(), hit.dz()).relative(face);
        boolean placeable = PhysicsBodyInteractionClient.cellIsAir(
                body, targetCell.getX(), targetCell.getY(), targetCell.getZ());

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(body.renderX(pt) - cam.x, body.renderY(pt) - cam.y, body.renderZ(pt) - cam.z);
        pose.mulPose(rot);
        Matrix4f matrix = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        // ── 第一轮：瞄准（金）—— 命中面填充+粗边框（"哪一面"）+ 命中格细框（"哪一格"）──
        float ar = AIM_RGB[0], ag = AIM_RGB[1], ab = AIM_RGB[2];
        BufferBuilder aimBuf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        appendFaceMark(aimBuf, matrix, hit.dx(), hit.dy(), hit.dz(), face, ar, ag, ab);
        BoxOutlineRenderer.appendBox(aimBuf, matrix,
                hit.dx(), hit.dy(), hit.dz(),
                hit.dx() + 1, hit.dy() + 1, hit.dz() + 1,
                HIT_BOX_HW, ar, ag, ab, HIT_BOX_ALPHA);
        AnimatedOutline.drawIfNotEmpty(aimBuf);

        // ── 第二轮：放置预览幽灵（青=能放 / 红=被占）—— 预览在哪，右键方块就出现在哪 ──
        float[] ghost = placeable ? OK_RGB : BAD_RGB;
        float gr = ghost[0], gg = ghost[1], gb = ghost[2];
        float fillAlpha = placeable ? GHOST_FILL_ALPHA : GHOST_BAD_FILL_ALPHA;
        float gx1 = targetCell.getX() - GHOST_INFLATE, gy1 = targetCell.getY() - GHOST_INFLATE;
        float gz1 = targetCell.getZ() - GHOST_INFLATE;
        float gx2 = targetCell.getX() + 1 + GHOST_INFLATE, gy2 = targetCell.getY() + 1 + GHOST_INFLATE;
        float gz2 = targetCell.getZ() + 1 + GHOST_INFLATE;
        BufferBuilder ghostBuf = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        appendBoxFaces(ghostBuf, matrix, gx1, gy1, gz1, gx2, gy2, gz2, gr, gg, gb, fillAlpha);
        BoxOutlineRenderer.appendBox(ghostBuf, matrix, gx1, gy1, gz1, gx2, gy2, gz2,
                BOLD_HW, gr, gg, gb, AnimatedOutline.EDGE_ALPHA);
        AnimatedOutline.drawIfNotEmpty(ghostBuf);

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        pose.popPose();
    }

    /**
     * 命中面的填充 + 该面四条边的粗边框。
     *
     * <p>只画整块框时六面长得一样，"我压在哪一面"根本读不出来（用户 2026-10-06 原话：
     * "你刚才增加的框是整个方块的框，我无法得知到底是哪个面"）。面边框把被瞄的那一面
     * 单独圈出来，斜看也认得出。</p>
     */
    private static void appendFaceMark(VertexConsumer buf, Matrix4f matrix,
                                       int dx, int dy, int dz, Direction face,
                                       float r, float g, float b) {
        float x1 = dx, y1 = dy, z1 = dz;
        float x2 = dx + 1, y2 = dy + 1, z2 = dz + 1;
        float o = FACE_OFFSET;
        switch (face) {
            case DOWN -> {
                float y = y1 - o;
                quad(buf, matrix, x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z2, r, g, b, FACE_ALPHA);
                edgeLoop(buf, matrix, x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z2, r, g, b);
            }
            case UP -> {
                float y = y2 + o;
                quad(buf, matrix, x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z2, r, g, b, FACE_ALPHA);
                edgeLoop(buf, matrix, x1, y, z1, x2, y, z1, x2, y, z2, x1, y, z2, r, g, b);
            }
            case NORTH -> {
                float z = z1 - o;
                quad(buf, matrix, x1, y1, z, x1, y2, z, x2, y2, z, x2, y1, z, r, g, b, FACE_ALPHA);
                edgeLoop(buf, matrix, x1, y1, z, x1, y2, z, x2, y2, z, x2, y1, z, r, g, b);
            }
            case SOUTH -> {
                float z = z2 + o;
                quad(buf, matrix, x1, y1, z, x1, y2, z, x2, y2, z, x2, y1, z, r, g, b, FACE_ALPHA);
                edgeLoop(buf, matrix, x1, y1, z, x1, y2, z, x2, y2, z, x2, y1, z, r, g, b);
            }
            case WEST -> {
                float x = x1 - o;
                quad(buf, matrix, x, y1, z1, x, y2, z1, x, y2, z2, x, y1, z2, r, g, b, FACE_ALPHA);
                edgeLoop(buf, matrix, x, y1, z1, x, y2, z1, x, y2, z2, x, y1, z2, r, g, b);
            }
            case EAST -> {
                float x = x2 + o;
                quad(buf, matrix, x, y1, z1, x, y2, z1, x, y2, z2, x, y1, z2, r, g, b, FACE_ALPHA);
                edgeLoop(buf, matrix, x, y1, z1, x, y2, z1, x, y2, z2, x, y1, z2, r, g, b);
            }
        }
    }

    /** 沿四边形四条边各画一条粗方条（面边框；顶点顺序任意，闭合即可）。 */
    private static void edgeLoop(VertexConsumer buf, Matrix4f matrix,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float x4, float y4, float z4,
                                 float r, float g, float b) {
        float a = 1.0F;
        BoxOutlineRenderer.addBar(buf, matrix, x1, y1, z1, x2, y2, z2, BOLD_HW, r, g, b, a);
        BoxOutlineRenderer.addBar(buf, matrix, x2, y2, z2, x3, y3, z3, BOLD_HW, r, g, b, a);
        BoxOutlineRenderer.addBar(buf, matrix, x3, y3, z3, x4, y4, z4, BOLD_HW, r, g, b, a);
        BoxOutlineRenderer.addBar(buf, matrix, x4, y4, z4, x1, y1, z1, BOLD_HW, r, g, b, a);
    }

    /** 追加一个盒子的 6 个半透明面（QUADS；该 pass 已关背面剔除，绕序无所谓）。 */
    private static void appendBoxFaces(VertexConsumer buf, Matrix4f matrix,
                                       float x1, float y1, float z1,
                                       float x2, float y2, float z2,
                                       float r, float g, float b, float a) {
        // +Z
        quad(buf, matrix, x1, y1, z2, x1, y2, z2, x2, y2, z2, x2, y1, z2, r, g, b, a);
        // -Z
        quad(buf, matrix, x2, y1, z1, x2, y2, z1, x1, y2, z1, x1, y1, z1, r, g, b, a);
        // +X
        quad(buf, matrix, x2, y1, z2, x2, y2, z2, x2, y2, z1, x2, y1, z1, r, g, b, a);
        // -X
        quad(buf, matrix, x1, y1, z1, x1, y2, z1, x1, y2, z2, x1, y1, z2, r, g, b, a);
        // +Y
        quad(buf, matrix, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1, r, g, b, a);
        // -Y
        quad(buf, matrix, x1, y1, z2, x1, y1, z1, x2, y1, z1, x2, y1, z2, r, g, b, a);
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

    /** ARGB → 0..1 的 RGB 三元组（静态常量用，别在每帧里现算）。 */
    private static float[] rgb(int argb) {
        return new float[] {
                ((argb >> 16) & 0xFF) / 255.0F,
                ((argb >> 8) & 0xFF) / 255.0F,
                (argb & 0xFF) / 255.0F};
    }
}
