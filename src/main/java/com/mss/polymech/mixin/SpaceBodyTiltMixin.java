package com.mss.polymech.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 太空 6DOF 的<b>身体渲染</b>：照 space 0.0.6 的 {@code MixinLivingEntityRenderer}。
 *
 * <p>两个注入点都在 {@code LivingEntityRenderer#render} 的调用现场：</p>
 * <ol>
 *   <li>{@code setupRotations} 调用被包起来 —— 太空里跳过原版的欧拉角 yaw/pitch，
 *       直接把<b>身体姿态四元数</b>压到 PoseStack 上。这是"只有朝上/朝下两个状态"的根治：
 *       欧拉角在 ±90° 附近有万向锁，四元数没有。</li>
 *   <li>{@code EntityModel#setupAnim} 的 headYaw/headPitch 参数被换成<b>头部相对身体的偏角</b>，
 *       于是"脖子先转、头先过去"能在模型上看出来。</li>
 * </ol>
 *
 * <p><b>为什么必须用 WrapOperation 而不是 @Inject 到 setupRotations：</b>
 * 玩家实际执行的是 {@code PlayerRenderer.setupRotations(AbstractClientPlayer, ...)}
 * （外加一个 {@code (LivingEntity,...)} 桥接方法），注入到 {@code LivingEntityRenderer}
 * 的那个方法体对玩家根本不会执行。包住<b>调用现场</b>才能覆盖所有重写。</p>
 *
 * <p><b>为什么传 yBodyRot = 0：</b>原版 {@code setupRotations} 内部第一步就是
 * {@code Axis.YP.rotationDegrees(180 - yBodyRot)}（且发生在 {@code scale(-1,-1,1)} 之前）。
 * 传 0 后它就退化成固定的 Ry(180)，而这个 180° 正好是 MC 模型空间→世界空间所需的镜像补偿：
 * 我们的四元数 Q = [left | up | forward]，总变换 Q·Ry(180) = [-left | up | -forward]，
 * 与 space 0.0.6 的约定一字不差。</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class SpaceBodyTiltMixin {

    private static final double TODEG = 180.0 / Math.PI;

    /** 包含两个调用现场的渲染循环：{@code render(T, float entityYaw, float partialTick, PoseStack, MultiBufferSource, int)}。 */
    private static final String RENDER =
            "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    /**
     * 玩家的身体姿态：欧拉角的替代品。直接绕<b>实体原点</b>旋转
     * （与物理刚体的原点一致；模型自身的 -1.501 偏移会一起被转过去）。
     */
    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/LivingEntityRenderer;setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V")
    )
    private void polymech$spaceBodyRotation(LivingEntityRenderer self, LivingEntity entity, PoseStack poseStack,
            float bob, float yBodyRot, float partialTick, float scale, Operation<Void> operation) {
        if (!(entity instanceof Player player)
                || !player.level().dimension().equals(PlanetDimensions.SPACE)) {
            operation.call(self, entity, poseStack, bob, yBodyRot, partialTick, scale);
            return;
        }
        SpacePlayerData data = SpacePlayerData.get(player);
        if (!data.isInitialized()) {
            operation.call(self, entity, poseStack, bob, yBodyRot, partialTick, scale);
            return;
        }

        // 绕"身体中心"（脚底 + 身高/2）旋转 —— **必须和物理碰撞箱绕同一点**：
        // Rapier 刚体的平移点就是脚底 + 身高/2，盒子挂在刚体原点上，所以碰撞箱绕身体中心转。
        // 模型若绕脚底转，身体一倾斜两者就错开最多半个身高 —— 表现就是"头/身体直接插进方块里"。
        // pivot 要除以 getScale()：此刻 PoseStack 里已经有 scale(getScale)，
        // 而 getBbHeight() 本身是缩放后的尺寸，除一次才是这一层坐标系里的长度。
        float pivot = player.getBbHeight() * 0.5F / Math.max(0.01F, player.getScale());
        poseStack.translate(0.0F, pivot, 0.0F);
        poseStack.mulPose(polymech$bodyQuat(data, partialTick));
        poseStack.translate(0.0F, -pivot, 0.0F);
        // yBodyRot = 0：让原版那步 Ry(180 - yBodyRot) 退化成固定的 Ry(180) 镜像补偿。
        operation.call(self, entity, poseStack, bob, 0.0F, partialTick, scale);
    }

    /**
     * 头部零件：用"头部相对身体的偏角"替换原版算出来的 headYaw/headPitch。
     * 符号与 space 0.0.6 一致（headYaw 取反、headPitch 不变）。
     */
    @WrapOperation(
            method = RENDER,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Lnet/minecraft/world/entity/Entity;FFFFF)V")
    )
    private void polymech$spaceHeadOffset(EntityModel model, Entity entity,
            float limbSwing, float limbSwingAmount, float age, float headYaw, float headPitch,
            Operation<Void> operation) {
        SpacePlayerData data = null;
        if (entity instanceof Player player
                && player.level().dimension().equals(PlanetDimensions.SPACE)) {
            SpacePlayerData d = SpacePlayerData.get(player);
            if (d.isInitialized()) {
                data = d;
                // 先按"头相对身体向右/低头"给一遍（正立时与下面完全一致，等价于 vanilla 语义）
                headYaw = (float) (d.headYaw() * TODEG);
                headPitch = (float) (d.headPitch() * TODEG);
            }
        }
        operation.call(model, entity, limbSwing, limbSwingAmount, age, headYaw, headPitch);

        // 头零件：用"身体⁻¹·视线"的**完整局部旋转**（含横滚）覆盖。
        // 领先量是绕世界竖轴/视线左轴定义的，身体一俯仰/倒挂，它在身体局部坐标里就带横滚
        // （身体平躺时几乎全是横滚）—— 只喂 yaw/pitch 会把头顶着画歪，这就是"颠倒时不对"的原因。
        // 模型空间是 Y 朝下、Z 朝后（渲染时才 scale(-1,-1,1)），故 y/z 分量取反：
        //   (xRot, yRot, zRot) = (x, -y, -z)   —— 正立时 x/y 与上面那两行完全等价
        if (data != null && model instanceof net.minecraft.client.model.HumanoidModel<?> humanoid) {
            org.joml.Vector3f e = new org.joml.Vector3f();
            data.headLocalEuler(e);
            humanoid.head.xRot = e.x;
            humanoid.head.yRot = -e.y;
            humanoid.head.zRot = -e.z;
            humanoid.hat.copyFrom(humanoid.head);
        }
    }

    /** 身体姿态四元数，用上一帧到本帧的 slerp 做插值（近反向时直接用本帧，避免扫过 180°）。 */
    private static Quaternionf polymech$bodyQuat(SpacePlayerData data, float partialTick) {
        Quaternionf now = data.orientation(new Quaternionf());
        Quaternionf old = data.orientationOld(new Quaternionf());
        if (old.dot(now) < -0.9999f) {
            return now;
        }
        return old.slerp(now, partialTick);
    }
}
