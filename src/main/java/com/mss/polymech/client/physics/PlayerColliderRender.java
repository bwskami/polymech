package com.mss.polymech.client.physics;

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
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class PlayerColliderRender {

    private PlayerColliderRender() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) {
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
        // 只在调试屏（F3）打开时画 —— 否则第一人称下那两个盒子正裹着相机，会像画面出了 bug。
        // （space 是开发期常开；我们放进 F3，验收时按一下即可。）
        if (!minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        double[] mainPos = new double[3];
        double[] siblingPos = new double[3];
        if (!rig.readPosition(mainPos) || !rig.readSiblingPosition(siblingPos)) {
            return;
        }
        // ⚠️ **必须用 event 的 poseStack + camera**，不能像 space 那样 `new PoseStack()` +
        //    `gameRenderer.getMainCamera()`（space 的写法在我们这里实测把盒子画到屏幕外）：
        //    世界渲染时 model-view 里已带相机旋转，只有 `event.getPoseStack()` 带着它；
        //    裸 PoseStack 是单位矩阵 ⇒ 盒子按世界轴对齐地落在错误位置。
        //    本项目的 `PhysicsBodyRenderer` 就是这套写法（:80/:94/:95），照它抄。
        PoseStack pose = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        VertexConsumer lines = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        pose.pushPose();
        drawBox(pose, lines, mainPos, rig.halfWidth(), rig.halfHeight(), camera, 0.2F, 0.9F, 1.0F);
        drawBox(pose, lines, siblingPos, rig.halfWidth(), rig.halfHeight(), camera, 1.0F, 0.6F, 0.2F);
        pose.popPose();
        // 显式 flush：本项目画线框的既有约定（见 PhysicsBodyRenderer 的 endBatch 注释）
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.lines());
    }

    private static void drawBox(PoseStack pose, VertexConsumer lines, double[] pos,
                                double halfWidth, double halfHeight, Vec3 camera,
                                float r, float g, float b) {
        pose.pushPose();
        pose.translate(pos[0] - camera.x, pos[1] - camera.y, pos[2] - camera.z);
        // 关于原点对称 —— 我们的刚体原点 = 碰撞箱中心（space 的在脚底，所以它写的是 0..2h.y）
        AABB box = new AABB(-halfWidth, -halfHeight, -halfWidth, halfWidth, halfHeight, halfWidth);
        LevelRenderer.renderLineBox(pose, lines, box, r, g, b, 1.0F);
        pose.popPose();
    }
}
