package com.mss.polymech.client.physics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mss.polymech.Polymech;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.item.PhysgunItem;
import com.mss.polymech.physics.PhysgunBeamShape;
import com.mss.polymech.physics.RayBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 牵引枪的视觉：机械动力航空学 / GMod 那种「能量绳 + 棋盘高亮框」。
 *
 * <h2>第二版为什么整个重写</h2>
 * 第一版是 {@code RenderType.lines()} 的 1px 直线 + 一个线框盒。用户的判词是
 * <b>「你这显示的完全不对啊，不像，太廉价了」</b> —— 对的，因为它缺了参考实现里
 * 让光束"有生命"的四样东西（{@code Simulated-Project-main} 的
 * {@code physics_staff/PhysicsStaffClientHandler.PhysicsBeam} 与
 * {@code PhysicsStaffRenderHandler}）：
 * <ol>
 *   <li><b>节点链抖动</b>：光束不是直线，是 ~1.5 格一段、每段带均值回复随机游走偏移的折线
 *       （振幅 ≈0.18 格）⇒ 看起来像在"呼吸"的电浆，而不是一根铁丝；</li>
 *   <li><b>真实线宽</b>：参考自造 {@code LineOutline} 就是因为核心 profile 把
 *       {@code glLineWidth} 钳到 1。这里用<b>相机朝向的十字双四边形</b>（两个正交面）
 *       画出真的厚度，任何视角都不会细成一条线；</li>
 *   <li><b>端点光斑 + 淡出</b>：抓点处一个正对相机的亮核；松手后强度按 0.6 衰减
 *       （1.0→0.6→消失）而不是硬切；</li>
 *   <li><b>棋盘悬停框</b>：参考用 {@code AllSpecialTextures.CHECKERED} 贴图 + {@code lineWidth(1/32)}；
 *       本项目没有那个资源，于是<b>用几何生成棋盘</b>（每面细分成 3×3，只填一半）
 *       —— 效果一样，还少一样资产。</li>
 * </ol>
 *
 * <h2>别人也看得见</h2>
 * 光束来自 {@link PhysgunClientState}（按玩家一张表，服务端 {@code PhysgunBeamPacket} 广播），
 * 所以不是"只有自己能看见的特效"。
 *
 * <h2>渲染通道</h2>
 * {@code RenderLevelStageEvent} 的 {@code AFTER_TRANSLUCENT_BLOCKS}，用
 * {@code event.getPoseStack()} + {@code event.getCamera().getPosition()}：
 * <b>顶点给"相机相对"坐标</b>（世界坐标减去相机位置）—— 裸 {@code new PoseStack()} 会缺相机旋转，
 * 这是本项目记录过的坑。两个自定义通道见 {@link PhysgunRenderTypes}。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PhysgunBeamRenderer {

    // ---------- 颜色 ----------
    // 光束（加色混合）：核心偏白、外晕偏蓝，叠起来才有"电浆"感
    private static final float[] CORE_RGB = {0.80F, 0.94F, 1.00F};
    private static final float[] GLOW_RGB = {0.12F, 0.46F, 0.98F};
    private static final float[] ANCHOR_RGB = {1.00F, 0.74F, 0.28F};
    /**
     * 悬停框颜色：<b>浅灰 0xBFBFBF</b> —— 照参考
     * （{@code PhysicsStaffRenderHandler:76} 是 {@code new Color(191/255f, 191/255f, 191/255f, 1f)}）。
     *
     * <p>参考在这一点上很克制：高亮用中性灰、不去抢画面，彩色只留给光束。
     * 我第一版用亮青色 + 框住<b>整个体</b>，所以用户看到的是一个巨大的青框，判词是"太廉价"。</p>
     */
    private static final float[] HOVER_RGB = {191.0F / 255.0F, 191.0F / 255.0F, 191.0F / 255.0F};
    /** lock 标记：白色（照参考 {@code renderAllLocks} 的 {@code 0xffffffff} + FULL_BRIGHT）。 */
    private static final float[] LOCK_RGB = {1.0F, 1.0F, 1.0F};

    // ---------- 线宽（格）。参考：光束 0.6/16 = 0.0375，悬停框 1/32 = 0.031 ----------
    private static final double BEAM_CORE_WIDTH = 0.030;
    private static final double BEAM_GLOW_WIDTH = 0.080;
    /** 悬停框线宽：照参考的 {@code lineWidth(1/32f)} = 0.03125。 */
    private static final double CELL_LINE_WIDTH = 1.0 / 32.0;
    /** 棋盘面往格内缩一点，避免与方块自身的面 z-fight。 */
    private static final double CELL_FACE_INSET = 0.005;
    /** 线框往外胀一点，让"厚线"骑在棱上而不是埋进方块里。 */
    private static final double CELL_OUTLINE_GROW = 0.002;

    /** 立方体 12 条棱（角编号 = xi*4 + yi*2 + zi）。 */
    private static final int[][] CUBE_EDGES = {
            {0, 1}, {0, 2}, {0, 4}, {1, 3}, {1, 5}, {2, 3},
            {2, 6}, {3, 7}, {4, 5}, {4, 6}, {5, 7}, {6, 7}};

    /** 正对相机的四边形（billboard）用的 UV，顺序与 {@link PhysgunBeamShape#billboard} 的角点一致。 */
    private static final double[] UV_QUAD = {0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0};

    /** 共用的临时数组（渲染在客户端主线程单线程跑，不存在重入）。 */
    private static final double[] CAM = new double[3];
    private static final double[] S = new double[3];
    private static final double[] E = new double[3];
    private static final double[] P0 = new double[3];
    private static final double[] P1 = new double[3];
    private static final double[] SIDE = new double[3];
    private static final double[] UP = new double[3];
    private static final double[] QUAD = new double[12];
    private static final double[] OUT_XYZ = new double[12];
    /** 贝塞尔控制点（"受力弯曲"那条曲线的中点偏移，见 {@link PhysgunBeamShape#control}）。 */
    private static final double[] CTRL = new double[3];
    /** 插值后的"虚拟竿尖"。 */
    private static final double[] TIP = new double[3];
    private static final Vector3d TMP = new Vector3d();
    private static final Quaterniond ROT = new Quaterniond();
    private static final Vector3d POS = new Vector3d();

    private PhysgunBeamRenderer() {
    }

    /** 客户端 tick：推进节点抖动与淡出（照参考：节点/强度按 tick 走，渲染按帧插值）。 */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        PhysgunClientState.tick();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        if (mc.options.hideGui) {
            // 照参考 PhysicsStaffRenderHandler:52：F1 隐藏界面时不画高亮（否则截图/录屏里会留一根光束）
            return;
        }
        boolean holding = isHoldingPhysgun(player);
        boolean anyBeam = PhysgunClientState.size() > 0;
        if (!holding && !anyBeam) {
            return;
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
        Vec3 cam = event.getCamera().getPosition();
        CAM[0] = cam.x;
        CAM[1] = cam.y;
        CAM[2] = cam.z;

        PoseStack pose = event.getPoseStack();
        Matrix4f mat = pose.last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // ⚠️⚠️ 必须**一个通道一趟**：`BufferSource.getBuffer(type)` 对"非 fixed"类型（我们这两个就是）
        //   会先把 lastSharedType 那个 builder 提前 `endBatch` 掉 —— 也就是说**取第二个通道会把第一个 flush**。
        //   2026-09-29 实机就是这么崩的：我在这里同时 `getBuffer(BEAM)` + `getBuffer(OVERLAY)`，
        //   随后往 BEAM 写第一个顶点就抛
        //   `IllegalStateException: Not building!`（BufferBuilder.ensureBuilding ← emit ← segment ← drawBeam）。
        //   证据（1.21.1 反编译源 `MultiBufferSource.BufferSource.getBuffer`）：
        //     `if (this.lastSharedType != null) this.endBatch(this.lastSharedType);`
        //   因为 `sharedBuffer` 是共享的，同一时刻只允许一个非 fixed 的 builder 在写。
        //   旧版只用一个 `RenderType.lines()` ⇒ 从来没踩到；一旦有两个自定义通道就必炸。
        //   所以下面每一趟都是 getBuffer → 写完 → endBatch，**绝不跨趟持有消费者**。

        // ---------- 第 1 趟：光束通道（自己的 + 别人的，都是服务端权威广播来的） ----------
        {
            VertexConsumer beam = PhysgunRenderTypes.beginPass(buffers, PhysgunRenderTypes.BEAM);
            List<UUID> gone = null;
            for (PhysgunClientState.Beam b : PhysgunClientState.beams()) {
                UUID owner = b.playerId();
                Player ownerPlayer = level.getPlayerByUUID(owner);
                if (ownerPlayer == null && !owner.equals(player.getUUID())) {
                    // 玩家不在了（退出/换维度）：他的光束不能永远挂着
                    if (gone == null) {
                        gone = new ArrayList<>();
                    }
                    gone.add(owner);
                    continue;
                }
                if (!b.hasEndpoints()) {
                    continue;
                }
                float alpha = (float) Mth.clamp(b.intensity(), 0.0, 1.0);
                if (alpha <= 0.01F) {
                    continue;
                }
                b.renderStart(partialTick, S);
                b.renderEnd(partialTick, E);
                b.renderTip(partialTick, TIP);
                if (owner.equals(player.getUUID())) {
                    // 自己的光束：起点用枪口近似，第一人称才像是从枪口出来的
                    muzzle(player, partialTick, S);
                }
                drawBeam(beam, mat, S, E, TIP, b, partialTick, alpha);
                drawAnchor(beam, mat, E, alpha);
            }
            if (gone != null) {
                for (UUID id : gone) {
                    PhysgunClientState.remove(id);
                }
            }
            // 落点标记（弹簧的目标点：眼 + 视线 × holdDistance）也在光束通道上（加色发光）
            if (holding && PhysgunClientState.isGrabbed(player.getUUID())) {
                drawTargetMarker(beam, mat, player, partialTick,
                        PhysgunClientState.holdDistance(player.getUUID()));
            }
            PhysgunRenderTypes.endPass(buffers, PhysgunRenderTypes.BEAM);
        }
        // ---------- 第 2/3 趟：悬停高亮（照参考：**只框准星那一格**）----------
        // 参考 PhysicsStaffRenderHandler:78 是
        //   Outliner.showCluster("physicsStaffSelection", List.of(hoverBlockPos))
        //       .colored(191,191,191) .disableLineNormals() .lineWidth(1/32f) .withFaceTexture(CHECKERED)
        // 也就是：**一个方块** + 浅灰 + 细线 + 棋盘贴图面；而且抓住时框会移到"抓点所在那一格"
        // （:95-100 用 dragSession.dragLocalAnchor()）。我第一版框的是**整个物理体**，
        // 于是屏幕上出现一个十几格长的大青框 —— 那既不是参考的做法，也遮视线。
        UUID self = player.getUUID();
        boolean grabbed = PhysgunClientState.isGrabbed(self);
        ClientPhysicsWorld.ClientBody aimBody = null;
        int[] cell = null;
        double[] lockCenter = null;
        float alpha = 0.95F;
        if (holding) {
            if (grabbed) {
                alpha = 0.55F;
                PhysgunClientState.Beam own = PhysgunClientState.beam(self);
                if (own != null && own.hasEndpoints()) {
                    // 抓点 = 光束终点（服务端按体局部抓点算出来的世界点，就在物体上）
                    own.renderEnd(partialTick, E);
                    lockCenter = E;
                    long id = PhysgunClientState.grabbedBodyId(self);
                    aimBody = id > 0L ? ClientPhysicsWorld.body(id) : null;
                    if (aimBody != null) {
                        poseOf(aimBody, partialTick, ROT, POS);
                        Vector3d local = RayBox.toLocalPoint(POS, ROT, E[0], E[1], E[2]);
                        cell = new int[]{(int) Math.floor(local.x), (int) Math.floor(local.y),
                                (int) Math.floor(local.z)};
                    }
                }
            } else {
                PhysgunTarget.Hit hit = PhysgunTarget.find(PhysgunItem.RANGE);
                if (hit != null) {
                    ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(hit.bodyId());
                    if (body != null) {
                        aimBody = body;
                        // 命中点（体局部）→ 所在格：块占 [x, x+1) ⇒ floor（见 RayBox.blockBounds）
                        cell = new int[]{(int) Math.floor(hit.localX()), (int) Math.floor(hit.localY()),
                                (int) Math.floor(hit.localZ())};
                    }
                }
            }
        }
        if (aimBody != null && cell != null) {
            // 这一格真的属于这个体吗？擦着包围盒打进去时可能落在空处 —— 那就退回最近的实心格，
            // 绝不把棋盘画在空气上。
            cell = nearestBlock(aimBody, cell[0], cell[1], cell[2]);
        }
        if (cell != null) {
            // 通道 2：棋盘贴图面；通道 3：细灰线框。**一个通道一趟**（见 RenderPassGuard）
            VertexConsumer checker = PhysgunRenderTypes.beginPass(buffers, PhysgunRenderTypes.CHECKER);
            drawCellFaces(checker, mat, aimBody, cell, partialTick, alpha);
            if (lockCenter != null) {
                drawLockFaces(checker, mat, lockCenter, alpha);
            }
            PhysgunRenderTypes.endPass(buffers, PhysgunRenderTypes.CHECKER);

            VertexConsumer overlay = PhysgunRenderTypes.beginPass(buffers, PhysgunRenderTypes.OVERLAY);
            drawCellEdges(overlay, mat, aimBody, cell, partialTick, alpha);
            if (lockCenter != null) {
                drawLockEdges(overlay, mat, lockCenter, alpha);
            }
            PhysgunRenderTypes.endPass(buffers, PhysgunRenderTypes.OVERLAY);
        }
    }

    /** 离开世界：清抓取状态，避免下次进来还画着一条指向旧体的光束。 */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            PhysgunClientState.clear();
        }
    }

    // ==================== 光束 ====================

    /**
     * 一条光束 = 一条<b>平滑曲线</b>（二次贝塞尔）逐段画十字四边形。
     *
     * <p>2026-09-29 用户定调：不要航空学那种"酷似闪电"的节点抖动（辨识度太高、会撞车），
     * 要"一般曲线"，而且**会随你移动画面而改变、像钓鱼竿受力弯曲**。于是：
     * 控制点 = 中点 + 受力向量 × {@link PhysgunBeamShape#BEND_GAIN} + 一点基础弓形
     * （见 {@link PhysgunBeamShape#control}），受力向量 = 真实抓点 − 软弹簧追出来的"虚拟竿尖"
     * ⇒ 甩视角时弯曲变大、停下后慢慢回直。</p>
     *
     * <p>两端<b>始终咬住</b>枪口与物体（弯的只有中间），所以不会出现"光束脱开物体"；
     * 线宽沿曲线收细（{@link PhysgunBeamShape#taper}）—— 竿形。</p>
     */
    private static void drawBeam(VertexConsumer vc, Matrix4f mat, double[] start, double[] end,
                                 double[] tip, PhysgunClientState.Beam b, float partialTick, float alpha) {
        double length = b.length();
        int segs = PhysgunBeamShape.segments(length);
        PhysgunBeamShape.control(start, end, tip, length, CTRL);
        System.arraycopy(start, 0, P0, 0, 3);
        for (int i = 1; i <= segs; i++) {
            double t = (double) i / segs;
            PhysgunBeamShape.bezier(t, start, CTRL, end, P1);
            double w = PhysgunBeamShape.taper(t);
            segment(vc, mat, P0, P1, BEAM_GLOW_WIDTH * w, GLOW_RGB, alpha * 0.30F);
            segment(vc, mat, P0, P1, BEAM_CORE_WIDTH * w, CORE_RGB, alpha);
            System.arraycopy(P1, 0, P0, 0, 3);
        }
    }

    /** 一段：相机朝向的十字（两个正交面）⇒ 任何视角都有厚度。 */
    private static void segment(VertexConsumer vc, Matrix4f mat, double[] a, double[] b,
                                double halfWidth, float[] rgb, float alpha) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double dz = b[2] - a[2];
        if (dx * dx + dy * dy + dz * dz < 1.0e-12) {
            return;
        }
        PhysgunBeamShape.frame(a, b, CAM, SIDE, UP);
        PhysgunBeamShape.quad(a, b, SIDE, halfWidth, QUAD);
        emit(vc, mat, QUAD, rgb, alpha);
        PhysgunBeamShape.quad(a, b, UP, halfWidth, QUAD);
        emit(vc, mat, QUAD, rgb, alpha);
    }

    /** 抓点光斑：正对相机的两层方块（内亮核 + 外晕）。 */
    private static void drawAnchor(VertexConsumer vc, Matrix4f mat, double[] center, float alpha) {
        PhysgunBeamShape.billboard(center, CAM, 0.075 + 0.035 * alpha, QUAD);
        emit(vc, mat, QUAD, CORE_RGB, alpha);
        PhysgunBeamShape.billboard(center, CAM, 0.180 + 0.060 * alpha, QUAD);
        emit(vc, mat, QUAD, ANCHOR_RGB, alpha * 0.35F);
    }

    /** 弹簧目标点的小十字标记（"拉力正把这个体拽向这里"）。 */
    private static void drawTargetMarker(VertexConsumer vc, Matrix4f mat, LocalPlayer player,
                                         float partialTick, double holdDistance) {
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 look = player.getViewVector(partialTick);
        double[] c = {(eye.x + look.x * holdDistance), (eye.y + look.y * holdDistance),
                (eye.z + look.z * holdDistance)};
        PhysgunBeamShape.billboard(c, CAM, 0.055, QUAD);
        emit(vc, mat, QUAD, ANCHOR_RGB, 0.30F);
    }

    // ==================== 悬停高亮（照参考：一格 + 棋盘面 + 细框 + lock） ====================

    /**
     * 那一格的 6 个棋盘<b>贴图面</b>。
     *
     * <p>面的几何来自 {@link PhysgunBeamShape#cellFace}（纯函数、离线可判；<b>格占 {@code [x, x+1)}</b>）。
     * 2026-09-29 之前这里是手写的"格心 ± 0.495"（中心约定），于是棋盘整体平移半格、飘到体轮廓之外 ——
     * 用户截图里"框外面那几个蓝方块"就是它。</p>
     */
    private static void drawCellFaces(VertexConsumer vc, Matrix4f mat, ClientPhysicsWorld.ClientBody body,
                                      int[] cell, float partialTick, float alpha) {
        poseOf(body, partialTick, ROT, POS);
        double[] cellD = {cell[0], cell[1], cell[2]};
        double[] xyz = new double[12];
        double[] uv = new double[8];
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                PhysgunBeamShape.cellFace(axis, sign, cellD, CELL_FACE_INSET, xyz, uv);
                for (int k = 0; k < 4; k++) {
                    TMP.set(xyz[k * 3], xyz[k * 3 + 1], xyz[k * 3 + 2]);
                    ROT.transform(TMP);
                    OUT_XYZ[k * 3] = TMP.x + POS.x;
                    OUT_XYZ[k * 3 + 1] = TMP.y + POS.y;
                    OUT_XYZ[k * 3 + 2] = TMP.z + POS.z;
                }
                emitTextured(vc, mat, OUT_XYZ, uv, HOVER_RGB, alpha);
            }
        }
    }

    /**
     * 那一格的 12 条细灰棱（照参考的 {@code lineWidth(1/32)}）。
     *
     * <p>用四边形做厚度：{@code glLineWidth} 在核心 profile 下被钳到 1，这正是参考自造
     * {@code LineOutline} 的原因。</p>
     */
    private static void drawCellEdges(VertexConsumer vc, Matrix4f mat, ClientPhysicsWorld.ClientBody body,
                                      int[] cell, float partialTick, float alpha) {
        poseOf(body, partialTick, ROT, POS);
        double[][] c = new double[8][3];
        int idx = 0;
        for (int xi = 0; xi < 2; xi++) {
            for (int yi = 0; yi < 2; yi++) {
                for (int zi = 0; zi < 2; zi++) {
                    localToWorld(ROT, POS,
                            cell[0] + (xi == 0 ? -CELL_OUTLINE_GROW : 1.0 + CELL_OUTLINE_GROW),
                            cell[1] + (yi == 0 ? -CELL_OUTLINE_GROW : 1.0 + CELL_OUTLINE_GROW),
                            cell[2] + (zi == 0 ? -CELL_OUTLINE_GROW : 1.0 + CELL_OUTLINE_GROW),
                            c[idx]);
                    idx++;
                }
            }
        }
        for (int[] e : CUBE_EDGES) {
            segment(vc, mat, c[e[0]], c[e[1]], CELL_LINE_WIDTH, HOVER_RGB, alpha);
        }
    }

    /**
     * 抓住时：在抓点画一个<b>正对相机的 1×1 棋盘方块</b>。
     *
     * <p>照参考 {@code renderAllLocks}：那里对每个被锁定的 sub-level 取它的渲染位置，旋转到相机朝向，
     * 画一个从 {@code -0.5} 到 {@code +0.5}（即 1×1 格）的 {@code SimRenderTypes.lock()} 四边形，
     * 颜色 {@code 0xffffffff}、{@code LightTexture.FULL_BRIGHT}。我们用同一张棋盘贴图代替它的 lock 图标。</p>
     */
    private static void drawLockFaces(VertexConsumer vc, Matrix4f mat, double[] center, float alpha) {
        PhysgunBeamShape.billboard(center, CAM, 0.5, QUAD);
        emitTextured(vc, mat, QUAD, UV_QUAD, LOCK_RGB, alpha);
    }

    /** lock 的外框：4 条细棱围成 1×1（远处也看得出"这个体被锁定"）。 */
    private static void drawLockEdges(VertexConsumer vc, Matrix4f mat, double[] center, float alpha) {
        PhysgunBeamShape.billboard(center, CAM, 0.5, QUAD);
        double[][] c = new double[4][3];
        for (int i = 0; i < 4; i++) {
            c[i][0] = QUAD[i * 3];
            c[i][1] = QUAD[i * 3 + 1];
            c[i][2] = QUAD[i * 3 + 2];
        }
        for (int i = 0; i < 4; i++) {
            segment(vc, mat, c[i], c[(i + 1) % 4], CELL_LINE_WIDTH, LOCK_RGB, alpha);
        }
    }

    /** 找这一格（或最近的实心格）—— 体局部格坐标（块占 {@code [x, x+1)}，即最小角）。 */
    private static int[] nearestBlock(ClientPhysicsWorld.ClientBody body, int cx, int cy, int cz) {
        int[] best = null;
        long bestD = Long.MAX_VALUE;
        for (ClientPhysicsWorld.BlockEntry e : body.blocks()) {
            if (e.dx() == cx && e.dy() == cy && e.dz() == cz) {
                return new int[]{cx, cy, cz};
            }
            long dx = e.dx() - cx;
            long dy = e.dy() - cy;
            long dz = e.dz() - cz;
            long d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = new int[]{e.dx(), e.dy(), e.dz()};
            }
        }
        return best;
    }

    // ==================== 位姿 / 顶点小工具 ====================

    /** 体的当前世界位姿：优先 100Hz 的本地刚体状态，退回按网络包插值。 */
    private static void poseOf(ClientPhysicsWorld.ClientBody body, float partialTick,
                               Quaterniond rotOut, Vector3d posOut) {
        double[] livePos = new double[3];
        float[] liveRot = new float[4];
        if (ClientPhysics.liveTransform(body.id(), livePos, liveRot)) {
            posOut.set(livePos[0], livePos[1], livePos[2]);
            rotOut.set(liveRot[0], liveRot[1], liveRot[2], liveRot[3]);
            return;
        }
        posOut.set(body.renderX(partialTick), body.renderY(partialTick), body.renderZ(partialTick));
        float[] r = new float[4];
        body.renderRotation(partialTick, r);
        rotOut.set(r[0], r[1], r[2], r[3]);
    }

    /** 体局部点 → 世界点。 */
    private static void localToWorld(Quaterniond rot, Vector3d pos, double x, double y, double z, double[] out) {
        TMP.set(x, y, z);
        rot.transform(TMP);
        out[0] = TMP.x + pos.x;
        out[1] = TMP.y + pos.y;
        out[2] = TMP.z + pos.z;
    }

    /** 顶点给"相机相对坐标"（世界 − 相机），交给 {@code event.getPoseStack()} 补相机旋转。 */
    private static void emit(VertexConsumer vc, Matrix4f mat, double[] quad, float[] rgb, float alpha) {
        for (int i = 0; i < 4; i++) {
            int o = i * 3;
            vc.addVertex(mat, (float) (quad[o] - CAM[0]), (float) (quad[o + 1] - CAM[1]),
                            (float) (quad[o + 2] - CAM[2]))
                    .setColor(rgb[0], rgb[1], rgb[2], alpha);
        }
    }

    /**
     * 带贴图的顶点（{@code CHECKER} 通道：{@code POSITION_COLOR_TEX_LIGHTMAP}）。
     *
     * <p>灯照度给 {@link LightTexture#FULL_BRIGHT}：棋盘是"界面式"的指示物，不该在洞穴/太空暗处变黑
     * —— 参考的 lock 四边形也是 {@code FULL_BRIGHT}。</p>
     */
    private static void emitTextured(VertexConsumer vc, Matrix4f mat, double[] xyz12, double[] uv8,
                                     float[] rgb, float alpha) {
        for (int i = 0; i < 4; i++) {
            int o = i * 3;
            vc.addVertex(mat, (float) (xyz12[o] - CAM[0]), (float) (xyz12[o + 1] - CAM[1]),
                            (float) (xyz12[o + 2] - CAM[2]))
                    .setColor(rgb[0], rgb[1], rgb[2], alpha)
                    .setUv((float) uv8[i * 2], (float) uv8[i * 2 + 1])
                    .setLight(LightTexture.FULL_BRIGHT);
        }
    }

    /** 近似枪口（第一人称）：眼 + 视线×0.35 + 右×0.18 − 上×0.12。 */
    private static void muzzle(LocalPlayer player, float partialTick, double[] out) {
        Vec3 look = player.getViewVector(partialTick);
        Vec3 eye = player.getEyePosition(partialTick);
        Vec3 right = look.cross(new Vec3(0.0, 1.0, 0.0));
        if (right.lengthSqr() < 1.0e-6) {
            right = new Vec3(1.0, 0.0, 0.0);              // 正上/正下看时的退化
        }
        right = right.normalize();
        Vec3 up = right.cross(look).normalize();
        Vec3 m = eye.add(look.scale(PhysgunItem.MUZZLE_FORWARD))
                .add(right.scale(0.18)).subtract(up.scale(0.12));
        out[0] = m.x;
        out[1] = m.y;
        out[2] = m.z;
    }

    private static boolean isHoldingPhysgun(LocalPlayer player) {
        return player.getItemInHand(InteractionHand.MAIN_HAND).is(ModItems.PHYSGUN.get())
                || player.getItemInHand(InteractionHand.OFF_HAND).is(ModItems.PHYSGUN.get());
    }

    /** 供调试/诊断：当前视觉命中的体 id（-1 表示没有）。 */
    public static long aimedBodyId() {
        PhysgunTarget.Hit hit = PhysgunTarget.find(PhysgunItem.RANGE);
        return hit == null ? -1L : hit.bodyId();
    }
}
