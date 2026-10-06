package com.mss.polymech.client.physics;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mss.polymech.Polymech;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.item.PhysgunItem;
import com.mss.polymech.physics.PhysgunBeamShape;
import com.mss.polymech.physics.RayBox;
import com.mss.polymech.util.FrameJitterMeter;
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

    /**
     * <b>丝滑判据</b>：自己那条光束终点的逐帧速度抖动（通用件，见 {@link FrameJitterMeter}）。
     *
     * <p>为什么要有它：用户反复报过"线抽抽、框也抽抽"。肉眼看"好像不抽了"不是判据 ——
     * 本项目的实机判据必须程序判定 PASS/FAIL。窗口按<b>时间</b>开（1.2 秒，与帧率无关：
     * 原来的"120 帧"在 560fps 下只有 0.21 秒，不同机器量的根本不是同一段时间），
     * 攒够后<b>松手那一刻</b>把这一段的统计打一行出来（照 {@code [牵引枪·旋转判据]} 的做法，
     * 不刷屏）。口径与天空那条"太空顺滑"同源，避免同一种毛病两套说法。</p>
     */
    private static final FrameJitterMeter OWN_BEAM_METER =
            new FrameJitterMeter("[牵引枪·丝滑判据]", FrameJitterMeter.DEFAULT_WINDOW_SECONDS);
    /** 本段是否喂过样本（决定松手时要不要打读数）。 */
    private static boolean meterArmed;

    /**
     * <b>归因分表</b>：同一条线由三个量拼成（起点=枪口、终点=抓点、中间=贝塞尔竿尖），
     * 终点又有两条消费路径（首选镜子环、兜底网络环）。人站着不动线也偶尔抽时，
     * 主表（终点）一条读数区分不出是谁在抖 —— 所以松手时把四条并排打出来，
     * <b>哪条 FAIL 点名哪条链</b>（判读表见各自字段注释）：
     *
     * <ul>
     *   <li>枪口 FAIL ⇒ 玩家自己的物理接触微抖进了眼点（物理层联动 · 路 2）；</li>
     *   <li>竿尖 FAIL ⇒ TIP 弹簧链在摆（20Hz 弹簧 + 0.1s 环，贝塞尔中间放大）（路 3）；</li>
     *   <li>网络环 FAIL + 主表 PASS ⇒ 服务端信号真在动、镜子环消费把它抹了；</li>
     *   <li>主表 FAIL + 网络环 PASS ⇒ 镜像/同步链的"停—等—走"（路 1，到达相位）；</li>
     *   <li>主表与网络环都 FAIL ⇒ 服务端位姿本身在抖（接触锯齿/弹簧微动 = 物理层，路 1 信号侧）；</li>
     *   <li>四条全 PASS 而肉眼见抽 ⇒ 抽的是还没上表的东西，下一轮再拆。</li>
     * </ul>
     */
    private static final FrameJitterMeter DIAG_MUZZLE_METER =
            new FrameJitterMeter("[牵引枪·丝滑判据·枪口]", FrameJitterMeter.DEFAULT_WINDOW_SECONDS);
    private static final FrameJitterMeter DIAG_TIP_METER =
            new FrameJitterMeter("[牵引枪·丝滑判据·竿尖]", FrameJitterMeter.DEFAULT_WINDOW_SECONDS);
    private static final FrameJitterMeter DIAG_NET_METER =
            new FrameJitterMeter("[牵引枪·丝滑判据·网络环]", FrameJitterMeter.DEFAULT_WINDOW_SECONDS);
    /** 网络环路径的终点暂存（{@code renderEndNet} 的输出）。 */
    private static final double[] NET_E = new double[3];

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

        // pt = 部分刻（deltaTickResidual），不是 getGameTimeDeltaTicks()（本帧步长）。见 PartialTicks（§7.12）。
        float partialTick = com.mss.polymech.util.PartialTicks.of(event);
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
            boolean ownDrawn = false;
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
                if (!b.renderEnd(partialTick, E)) {
                    // 客户端还没有这个体（权威包先到、体还没同步）⇒ 这一帧不画它，
                    // 而不是拿上一次的旧世界点硬画（那会看到光束"钉在原地"）
                    continue;
                }
                b.renderTip(partialTick, TIP);
                if (owner.equals(player.getUUID())) {
                    // ★ 起点不再做渲染端二次覆盖（旧 muzzle() 手部偏移已删）：
                    //   S = renderStart（服务端起点环，0.1s）⇒ 与终点 E 同一时间线，
                    //   移动时两端同步、线不被拉扯；公式也与服务端广播/客户端预测三处统一。
                    // ★ 丝滑判据的输入：终点是"线"和"框"共同的来源，量它就等于两处一起量
                    OWN_BEAM_METER.sample(E[0], E[1], E[2]);
                    // ★ 归因分表（同一窗口、同一帧）：起点 / 竿尖 / 网络环消费各量一条，
                    //   松手时并排打印 —— 哪条 FAIL 点名哪条链（见诊断表字段注释）
                    DIAG_MUZZLE_METER.sample(S[0], S[1], S[2]);
                    DIAG_TIP_METER.sample(TIP[0], TIP[1], TIP[2]);
                    if (b.renderEndNet(partialTick, NET_E)) {
                        DIAG_NET_METER.sample(NET_E[0], NET_E[1], NET_E[2]);
                    }
                    meterArmed = true;
                    ownDrawn = true;
                }
                drawBeam(beam, mat, S, E, TIP, b, partialTick, alpha);
                drawAnchor(beam, mat, E, alpha);
            }
            // ★ 丝滑判据的读出：自己那条光束松手/消失时打一行（程序判定 PASS/FAIL）
            PhysgunClientState.Beam ownBeam = PhysgunClientState.beam(player.getUUID());
            if (meterArmed && (ownBeam == null || ownBeam.released())) {
                meterArmed = false;
                // 主表（终点·渲染口径）先打，随后三条归因分表 —— 哪条 FAIL 点名哪条链
                emitVerdict(player, OWN_BEAM_METER);
                emitVerdict(player, DIAG_MUZZLE_METER);
                emitVerdict(player, DIAG_TIP_METER);
                emitVerdict(player, DIAG_NET_METER);
                OWN_BEAM_METER.reset();
                DIAG_MUZZLE_METER.reset();
                DIAG_TIP_METER.reset();
                DIAG_NET_METER.reset();
            }
            if (gone != null) {
                for (UUID id : gone) {
                    PhysgunClientState.remove(id);
                }
            }
            // 落点标记（弹簧目标点）：基准 = 光束起点 S（服务端起点环，0.1s 时间线），
            // 不再逐帧现算眼点 —— 玩家物理体的 tick 级位置抖动不再打进标记（"准星黄点抽抽"，2026-10-04）。
            // ownDrawn：本帧自己的光束真的画出来了（S 才是新鲜值），否则不画，免得拿上一帧的旧 S。
            if (holding && ownDrawn && PhysgunClientState.isGrabbed(player.getUUID())) {
                drawTargetMarker(beam, mat, S, player, partialTick,
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
                if (own != null && own.hasEndpoints() && own.renderEnd(partialTick, E)) {
                    // 抓点 = 光束终点（由体局部抓点 + **客户端自己的**体位姿现算出来的世界点）
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

    /** 打一行读数（窗口没攒够 ⇒ {@code verdict()} 为 null ⇒ 这一条静默跳过）。 */
    private static void emitVerdict(LocalPlayer player, FrameJitterMeter meter) {
        String verdict = meter.verdict();
        if (verdict != null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(verdict), false);
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

    /**
     * 弹簧目标点的小十字标记（"拉力正把这个体拽向这里"）—— 投影在准星附近。
     *
     * <p><b>基准 = 光束起点 S（服务端起点环，0.1s），不是逐帧眼点</b>（2026-10-04 修）：
     * 旧写法 {@code 眼(pt) + 视线(pt)×holdDistance} 逐帧现算，玩家物理体每个 tick 的位置抖动
     * （接触/修正）直接打进标记 —— 实测枪口链站立时尖峰 5~15 格/秒、行走时更高，
     * 表现就是"准星上的黄点一直抽抽"。现在标记与光束两端<b>同一 0.1s 时间线</b>：
     * 位置项取环上的 S（= 眼+视线×{@link PhysgunItem#MUZZLE_FORWARD} 的延迟平滑值），
     * 沿视线补到 holdDistance；视线每帧取（旋转经 xRotO 逐 tick 推进 + 鼠标增量，本来就平滑）。
     * 代价 = 标记滞后 ~100ms —— 与线同步，正是想要的。</p>
     */
    private static void drawTargetMarker(VertexConsumer vc, Matrix4f mat, double[] start,
                                         LocalPlayer player, float partialTick, double holdDistance) {
        Vec3 look = player.getViewVector(partialTick);
        double ahead = holdDistance - PhysgunItem.MUZZLE_FORWARD;
        double[] c = {start[0] + look.x * ahead, start[1] + look.y * ahead, start[2] + look.z * ahead};
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

    /**
     * 体的当前世界位姿（渲染口径）—— 走 {@link ClientPhysics#renderTransform}
     * （被两拍包住的时间戳插值，与光束终点/方块渲染同一个口径）。
     *
     * <p>不直读刚体：那是 10ms 一跳的阶梯，高帧率下框会随步进"跳格"
     * （2026-10-03 读数 {@code 跳变帧=101/120} 的来源）。</p>
     */
    private static void poseOf(ClientPhysicsWorld.ClientBody body, float partialTick,
                               Quaterniond rotOut, Vector3d posOut) {
        double[] pos = new double[3];
        float[] rot = new float[4];
        if (ClientPhysics.renderTransform(body.id(), partialTick, pos, rot)) {
            posOut.set(pos[0], pos[1], pos[2]);
            rotOut.set(rot[0], rot[1], rot[2], rot[3]);
            return;
        }
        // 理论上到不了（循环遍历的就是 ClientBody）；保持兜底以免画出 NaN 位姿
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

    /**
     * 已删除（2026-10-04）：{@code muzzle(player, partialTick, S)} —— 渲染端对起点的<b>二次覆盖</b>。
     *
     * <p>旧实现给起点加"手部偏移"（右×0.18 − 上×0.12）让线更像从枪管出来。两个问题：
     * ① 起点成了<b>零延迟</b>的逐帧量，而终点走 0.1s 网络环 —— 移动时两端不同时间线，
     * 线被拉扯（2026-10-04 实测行走窗口起点尖峰 240 格/秒，而终点被延迟抹平）；
     * ② 渲染起点与服务端广播、客户端预测的公式（眼 + 视线×{@link PhysgunItem#MUZZLE_FORWARD}）
     * 不一致 —— 同一个概念三处各算一套。现在三处统一走 {@code renderStart}。</p>
     */

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
