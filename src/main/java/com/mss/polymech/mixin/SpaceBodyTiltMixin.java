package com.mss.polymech.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空自由旋转：渲染玩家身体时，从 facing/left 向量直接构建完整朝向四元数。
 *
 * <p>body 模型的 yaw/pitch 由 vanilla setupRotations 处理，
 * 我们只追加 roll 分量（与相机 roll 一致）。</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class SpaceBodyTiltMixin {

    private static final double TORAD = Math.PI / 180.0;
    private static final double TODEG = 180.0 / Math.PI;

    @Inject(
            method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFFF)V",
            at = @At("TAIL")
    )
    private void polymech$spaceBodyRoll(LivingEntity entity, PoseStack poseStack,
            float bob, float yBodyRot, float partialTick, float scale, CallbackInfo ci) {
        if (!(entity instanceof Player player)) return;
        if (!player.level().dimension().equals(PlanetDimensions.SPACE)) return;

        SpacePlayerData data = SpacePlayerData.get(player);
        if (!data.isInitialized()) return;

        // 插值
        Vector3d facing = lerp(data.facingO(), data.facing(), partialTick);
        Vector3d left = lerp(data.leftO(), data.left(), partialTick);
        facing.normalize();
        double d = left.dot(facing);
        left.sub(facing.x * d, facing.y * d, facing.z * d);
        if (left.lengthSquared() > 1e-12) left.normalize();

        // yaw/pitch
        float pitch = (float) (-Math.asin(clamp(facing.y, -1, 1)) * TODEG);
        float yaw = (float) (-Math.atan2(facing.x, facing.z) * TODEG);

        // roll（与相机一致，用连续性约束）
        Vector3d refLeft = new Vector3d(1, 0, 0);
        refLeft.rotateX(-pitch * TORAD);
        refLeft.rotateY(-(yaw + 180) * TORAD);
        double dd = refLeft.dot(facing);
        refLeft.sub(facing.x * dd, facing.y * dd, facing.z * dd);
        if (refLeft.lengthSquared() > 1e-12) refLeft.normalize();

        double roll = -Math.atan2(
                left.cross(refLeft, new Vector3d()).dot(facing),
                left.dot(refLeft)) * TODEG;

        if (Math.abs(roll) > 1e-3) {
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) roll));
        }
    }

    private static Vector3d lerp(Vector3d a, Vector3d b, float t) {
        return new Vector3d(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
