package com.mss.polymech.client.physics;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mss.polymech.Polymech;
import com.mss.polymech.physics.PlayerPhysicsBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * 把玩家的**双刚体**画成线框（照 space 0.1.3 的 {@code PhysicalPlayerColliderRender}，55 行）。
 *
 * <p><b>为什么需要它</b>：玩家在 Rapier 里其实是<b>两个</b>盒子（主 + 兄弟，组 {@code (2,5)}/{@code (5,5)}），
 * 但两者同位置、互不相撞，而且 Rapier 碰撞体尺寸就是从原版碰撞箱取的 —— 从外面看永远是**一个**盒子。
 * 没有这个渲染器时，"我的双刚体呢"只能靠日志里的句柄（`主 5 / 兄弟 6`）回答；有了它就能直接看。</p>
 *
 * <p><b>两个盒子各自是什么</b>：</p>
 * <ul>
 *   <li><b>主刚体（青蓝）</b>：真正承载玩家位置的那个，每子步被写成 {@code sibling + own}；</li>
 *   <li><b>兄弟刚体（橙）</b>：同尺寸同位置，职责是<b>量出"被环境带着走"的速度</b>
 *       （站在移动的船上时船面推着它走，于是 {@code main = sibling + own} 把玩家带走）。
 *       它<b>不是</b>第二个碰撞箱 —— 它与主刚体互不相撞（{@code 2 & 5 == 0}），
 *       所以正常时它永远和主刚体<b>完全重合</b>；只有在接触/被带走的那一子步里才会与主刚体错开一点。</li>
 * </ul>
 *
 * <p><b>与 space 的唯一必要差异（几何约定）</b>：space 的刚体原点在<b>脚底</b>、碰撞体带
 * {@code (0, halfHeight, 0)} 的局部平移，所以它画的是 {@code (-h.x, 0, -h.x, h.x, 2h.y, h.x)}；
 * 我们的原生 {@code colliderAttachCuboid*} 没有平移参数，碰撞体就在刚体原点
 * ⇒ 我们的刚体原点<b>在碰撞箱中心</b>，盒子必须关于原点对称，否则会整体抬高半个身高。
 * 两者把盒子放在世界里的位置<b>完全相同</b>（见 docs/mps-clone-plan.md 第 22 节的"等价约定"）。</p>
 *
 * <p>渲染发生在本项目物理世界存在时（即玩家被物理接管）；未接管（旁观/飞行/无物理体）时不画。</p>
 *
 * <p><b>位姿消费（2026-10-04，两次修）</b>：基准走原版实体插值（与第三人称渲染的玩家同源），
 * <b>姿态</b>乘 {@code bodyQuatForRender}（slerp 平滑、与模型同一个来源），
 * 超人姿态再叠 Rx(+90) 趴平 —— 逐项对齐 {@code SpaceBodyTiltMixin}，否则模型转了框不转。
 * 兄弟差分已丢弃：{@code afterStep} 每子步 {@code sibling.pos = main.pos}，
 * 帧级读到的只是子步中间态，每帧都不同 ⇒ 橙框相对主框抽动（那信息只在子步内部存在）。
 * 详见方法内注释与 {@code docs/render-interpolation.md} §7.9/§7.11。</p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PlayerColliderRender {

    private PlayerColliderRender() {
    }

    /**
     * 框自己的**丝滑判据**（通用件 {@link com.mss.polymech.util.FrameJitterMeter}）：
     * 按**绝对世界坐标**逐帧采样框中心，攒够一个窗口就程序判定 PASS/FAIL —— 与人眼、与相机都无关。
     *
     * <p>2026-10-05 加：框"随移动抽搐 / 位置不对"被连报三轮，前两轮只能靠人眼复述症状，
     * 结论来回摇摆。这次让程序给数：框只在 F3 下渲染 ⇒ 这个读数天然只在"你正看着框"时跑，
     * 不会在正常游戏里刷屏。</p>
     */
    private static final com.mss.polymech.util.FrameJitterMeter BOX_METER =
            new com.mss.polymech.util.FrameJitterMeter("[玩家框·丝滑判据]",
                    com.mss.polymech.util.FrameJitterMeter.DEFAULT_WINDOW_SECONDS);

    /**
     * **模型侧**基准的丝滑判据（同一个窗口、同一个 pt，只是把 `xo` 换成 `xOld`）。
     *
     * <p>为什么要并排量：2026-10-05 用户明确回答"**只有框抽、人很稳**"。但代码上
     * 相机（{@code SpaceCameraMixin}）与框都用 {@code lerp(pt, xo, x)}，模型（{@code LevelRenderer}）
     * 用 {@code lerp(pt, xOld, x)} —— 若 {@code xo == xOld} 且三者 pt 相同，三者必须**一起动**，
     * 不可能只有框抽。所以三者里必有一个假设是错的，这一对读数就是用来钉死是哪一个的：
     * 两条判据的抖动若接近 ⇒ 人其实也在抽（只是线框更显眼）；若模型那条明显更平 ⇒ {@code xo ≠ xOld}。</p>
     */
    private static final com.mss.polymech.util.FrameJitterMeter MODEL_METER =
            new com.mss.polymech.util.FrameJitterMeter("[模型基准·丝滑判据]",
                    com.mss.polymech.util.FrameJitterMeter.DEFAULT_WINDOW_SECONDS);

    /** 模型侧实际拿到的 partialTick（由 {@code SpaceBodyTiltMixin} 每帧写入）。 */
    public static volatile float lastModelPartialTick = Float.NaN;

    /** 上一次播报的 PASS/FAIL；只在状态**翻转**时进聊天栏（否则每 1.2 秒刷一条）。 */
    private static Boolean boxMeterLastPass;

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // ── 阶段：AFTER_ENTITIES（2026-10-05 第七轮改；原先是 AFTER_SOLID_BLOCKS）──
        //
        // ★★ 为什么不能用 AFTER_SOLID_BLOCKS（这是"框位置不对"的根因）：
        //   NeoForge 把带 RenderType 的阶段从 `LevelRenderer.renderSectionLayer` 末尾发出，
        //   那条重载**传的 poseStack 是 null**（事件类替我们 new 了一个**单位矩阵**）。
        //   更要命的是时机：`renderLevel` 的时间线是
        //     :972  renderSectionLayer(solid)   ← AFTER_SOLID_BLOCKS 在这里触发
        //     :1002 modelViewStack.pushMatrix().mul(frustumMatrix)  ← 相机矩阵**才在这里**压栈
        //     :1009 实体循环（玩家模型在这里画，带着相机矩阵）
        //     :1052 dispatchRenderStage(AFTER_ENTITIES)             ← 同一个矩阵框
        //     :1242 popMatrix()
        //   ⇒ 在 AFTER_SOLID_BLOCKS 阶段 flush 的线框，着色器拿到的 modelView 是**陈旧的**，
        //     而地形/实体各自重设矩阵所以画面看不出异常 —— **只有我们这一个框会画歪**。
        //     表现：框的世界坐标明明正确（[框·插值自检] PASS），但屏幕位置随相机姿态乱漂，
        //     "第几人称都不对"、第一人称满屏大线。
        //   挪到 AFTER_ENTITIES 后，框与玩家模型在**同一个 modelView 帧**里 flush。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        PlayerPhysicsBody rig = ClientPhysics.playerRig();
        if (rig == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        var player = minecraft.player;
        // 只在调试屏（F3）打开时画 —— 否则第一人称下那两个盒子正裹着相机，会像画面出了 bug。
        // （space 是开发期常开；我们放进 F3，验收时按一下即可。）
        if (!minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        // ── 位姿消费（2026-10-04 第二次修）：基准 + 姿态都要与第三人称**模型**同源 ──
        //
        // 上一版只把位置基准换成了实体插值，但漏了**姿态**。第三人称玩家模型走
        // SpaceBodyTiltMixin：绕 pivot = bodyCenterOffset 旋转、用 bodyQuatForRender（slerp 平滑）、
        // 超人姿态再叠 Rx(+90) 趴平。框若不乘同一个姿态四元数，模型一转框不转 ——
        // 视觉上就是"框不在人身上"（滚转 / 超人姿态尤其明显，用户原话"位置有问题"）。
        //
        // 修法与模型逐项对齐：
        //   ① 位置基准 = 实体插值 lerp(xo, x, pt)（与模型的 getX(pt) 同源）；
        //   ② 中心偏移取 **rig.centerOffset()**（建体时锁死、姿态变化会同步更新的那个值），
        //      不是每帧重算 bodyCenterOffset —— 框必须描述**刚体**，两者可能在姿态切换瞬间不一致；
        //   ③ 乘 bodyQuatForRender(pt)（slerp 平滑的渲染姿态，与模型同一个来源；直接用
        //      物理真实姿态会暴露 100Hz 采样率 ⇒ 每 2~3 帧才动一次，看着就是抽搐）；
        //   ④ 超人姿态叠 Rx(+SUPERMAN_PRONE_PITCH_DEG) —— 与模型的"趴平"一致；
        //   ⑤ **丢掉兄弟差分**（上一版的 rawSibling − rawMain）：afterStep 每个子步都
        //      sibling.pos = main.pos，帧级读到的是子步**中间态**（主刚体已积分、兄弟还没重合），
        //      每帧都不同 ⇒ 兄弟橙框相对主框抽动。这个"错开"信息只在子步内部存在，
        //      帧级渲染显示不出来，只会带来噪声 —— 两盒用同一基准。
        // ⑥ pt 必须取"部分刻"（deltaTickResidual），**不是** getGameTimeDeltaTicks()（那是本帧步长≈0.04）。
        //    取错 ⇒ pt≈0 ⇒ 框画在上一刻的位置、只在 tick 边界跳 ⇒ 移动时抽搐 + 比人慢一整刻。
        //    见 com.mss.polymech.util.PartialTicks 的类注释（§7.12，附运行时字节码）。
        float pt = com.mss.polymech.util.PartialTicks.of(event);
        double baseX = net.minecraft.util.Mth.lerp(pt, player.xo, player.getX());
        double baseY = net.minecraft.util.Mth.lerp(pt, player.yo, player.getY());
        double baseZ = net.minecraft.util.Mth.lerp(pt, player.zo, player.getZ());

        // 旋转必须从 rig 存的 centerOffset 走（与建体同源）；姿态四元数与模型同源。
        com.mss.polymech.space.SpacePlayerData data = com.mss.polymech.space.SpacePlayerData.get(player);
        org.joml.Quaternionf bodyQuat = data != null
                ? data.bodyQuatForRender(pt, new org.joml.Quaternionf())
                : new org.joml.Quaternionf();
        boolean superman = com.mss.polymech.space.SpacePlayerData.isSuperman(player);

        // 主/兄弟两盒同一基准（见 ⑤）
        double[] mainPos = {baseX, baseY + rig.centerOffset(), baseZ};
        double[] siblingPos = {mainPos[0], mainPos[1], mainPos[2]};

        // ★ 丝滑判据：按**绝对世界坐标**逐帧采样框中心（不含相机、不含屏幕），攒够窗口即判定。
        //   同一窗口、同一个 pt 再采一条**模型侧基准**（xOld）—— 用来判定"是只有框抽，还是人也在抽"。
        double[] modelPos = {
                net.minecraft.util.Mth.lerp(pt, player.xOld, player.getX()),
                net.minecraft.util.Mth.lerp(pt, player.yOld, player.getY()) + rig.centerOffset(),
                net.minecraft.util.Mth.lerp(pt, player.zOld, player.getZ())};
        // 诊断：flush 那一刻"自然"的模型视图栈顶（没被我们显式改写前的值）——
        //   若它的对角≈(1,1,1,1) 即单位矩阵，就坐实了"框在没有相机旋转的矩阵里画"。
        Vec3 camera = event.getCamera().getPosition();
        //（jOML 的 Matrix4fStack 继承自 Matrix4f，对象本身即**栈顶**那张矩阵。）
        org.joml.Matrix4f naturalMv = new org.joml.Matrix4f(RenderSystem.getModelViewStack());
        reportBoxMeter(minecraft, player, pt, event.getCamera().getPartialTickTime(),
                mainPos, modelPos, camera, naturalMv);
        // ⚠️ **必须用 event 的 poseStack + camera**（单位矩阵 + 相机世界坐标 —— 世界渲染的约定：
        //    相机旋转在 modelView 矩阵里，不在 poseStack 里）。
        // ⚠️⚠️ 但 flush 的那一刻着色器吃的是 **RenderSystem 模型视图栈顶**，不是 poseStack！
        //    所以这里显式把 modelView 设成 `event.getModelViewMatrix()`（= frustumMatrix，
        //    与实体渲染同一帧）再 flush —— 让框与玩家模型**构造上逐位同框**，
        //    不依赖任何阶段"碰巧"留下的栈状态（AFTER_SOLID_BLOCKS 就是没碰巧，见上面的阶段注释）。
        PoseStack pose = event.getPoseStack();
        org.joml.Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        try {
            VertexConsumer lines = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
            pose.pushPose();
            drawBox(pose, lines, mainPos, rig.halfWidth(), rig.halfHeight(), camera,
                    bodyQuat, superman, 0.2F, 0.9F, 1.0F);
            drawBox(pose, lines, siblingPos, rig.halfWidth(), rig.halfHeight(), camera,
                    bodyQuat, superman, 1.0F, 0.6F, 0.2F);
            pose.popPose();
            // 显式 flush：本项目画线框的既有约定（见 PhysicsBodyRenderer 的 endBatch 注释）
            minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
        } finally {
            mvs.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    /**
     * 画一个盒子：中心 = 刚体中心，姿态 = 身体四元数（+ 超人趴平），绕刚体中心旋转。
     *
     * <p>与第三人称模型（{@code SpaceBodyTiltMixin}）逐项对齐：模型也是绕
     * {@code bodyCenterOffset} 这个 pivot 转、用同一个 {@code bodyQuatForRender}、
     * 超人姿态叠同一个 Rx(+90)。两者不同源就会"框不在人身上"。</p>
     */
    private static void drawBox(PoseStack pose, VertexConsumer lines, double[] pos,
                                double halfWidth, double halfHeight, Vec3 camera,
                                org.joml.Quaternionf bodyQuat, boolean superman,
                                float r, float g, float b) {
        pose.pushPose();
        pose.translate(pos[0] - camera.x, pos[1] - camera.y, pos[2] - camera.z);
        // 绕刚体中心旋转（与模型的 pivot 旋转一致）
        pose.mulPose(bodyQuat);
        if (superman) {
            // 超人姿态：再叠一个局部 +90° 俯仰把身体"趴平"（与 SpaceBodyTiltMixin 一致）
            pose.mulPose(new org.joml.Quaternionf().rotationX(
                    (float) Math.toRadians(com.mss.polymech.space.SpacePlayerData.SUPERMAN_PRONE_PITCH_DEG)));
        }
        // 关于原点对称 —— 我们的刚体原点 = 碰撞箱中心（space 的在脚底，所以它写的是 0..2h.y）
        AABB box = new AABB(-halfWidth, -halfHeight, -halfWidth, halfWidth, halfHeight, halfWidth);
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1.0F);
        pose.popPose();
    }

    /**
     * 采样框中心与模型基准（都是**绝对世界坐标**）并在窗口攒满时给结论。
     *
     * <p>判据本身在通用件里（逐帧速度 = 位移 ÷ 帧间隔，抖动 = 最大/均，停帧只在明显在动时计数），
     * 这里只负责"喂绝对坐标 + 播报"。日志：有结论就记，并**并排**记一行原始值
     * （三个 pt + xo/x/xOld + 两侧基准差），用来钉死"框和模型到底哪里不同源"；
     * 聊天栏：只在 PASS/FAIL 翻转时打一次，免得刷屏。</p>
     */
    private static void reportBoxMeter(Minecraft minecraft, net.minecraft.world.entity.player.Player player,
                                       float pt, float cameraPt, double[] boxCenter, double[] modelCenter,
                                       Vec3 camera, org.joml.Matrix4f naturalMv) {
        BOX_METER.sample(boxCenter[0], boxCenter[1], boxCenter[2]);
        MODEL_METER.sample(modelCenter[0], modelCenter[1], modelCenter[2]);
        String boxVerdict = BOX_METER.verdict();
        String modelVerdict = MODEL_METER.verdict();
        if (boxVerdict == null) {
            return; // 窗口还没攒够
        }
        BOX_METER.reset();
        MODEL_METER.reset();

        // ① 原始值自检：三个 pt 是否相同、xo 与 xOld 是否同源（阈值 1cm —— 两者本就该逐位相同）
        double dx = boxCenter[0] - modelCenter[0];
        double dy = boxCenter[1] - modelCenter[1];
        double dz = boxCenter[2] - modelCenter[2];
        double diverge = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Polymech.LOGGER.info(String.format(java.util.Locale.ROOT,
                "[框·插值自检] pt(框)=%.4f pt(模型)=%.4f pt(相机)=%.4f | xo=%.4f x=%.4f xOld=%.4f | "
                        + "两侧基准差=%.4f 格 ⇒ %s",
                pt, lastModelPartialTick, cameraPt,
                player.xo, player.getX(), player.xOld, diverge,
                diverge <= 0.01 ? "PASS（框与模型同源）" : "FAIL（框与模型基准不同源）"));

        // ② 坐标系自检：框中心相对相机的偏移 + flush 那一刻"自然"的模型视图矩阵对角。
        //   modelView 对角≈(1,1,1,1) ⇒ 框是在**没有相机旋转**的矩阵里画的（§7.13 根因）。
        Polymech.LOGGER.info(String.format(java.util.Locale.ROOT,
                "[框·坐标系] cam=(%.3f,%.3f,%.3f) 框中心=(%.3f,%.3f,%.3f) Δ(相机-框)=(%.3f,%.3f,%.3f)"
                        + " | 自然modelView对角=(%.4f,%.4f,%.4f,%.4f)",
                camera.x, camera.y, camera.z,
                boxCenter[0], boxCenter[1], boxCenter[2],
                camera.x - boxCenter[0], camera.y - boxCenter[1], camera.z - boxCenter[2],
                naturalMv.m00(), naturalMv.m11(), naturalMv.m22(), naturalMv.m33()));

        // ② 两条丝滑判据并排：模型那条若明显更平 ⇒ 人其实很稳、是框的基准错了
        if (boxVerdict.contains("不可判")) {
            return;
        }
        Polymech.LOGGER.info(boxVerdict);
        Polymech.LOGGER.info(modelVerdict == null ? "[模型基准·丝滑判据] （无样本）" : modelVerdict);
        boolean pass = boxVerdict.endsWith("PASS");
        if (boxMeterLastPass != null && boxMeterLastPass == pass) {
            return; // 状态没翻转 ⇒ 不打扰
        }
        boxMeterLastPass = pass;
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(boxVerdict), false);
        }
    }
}
