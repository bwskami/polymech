package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空自由旋转：facing/left 双向量方案，无任何欧拉角奇点。
 *
 * <p>俯仰绕局部 left 轴；偏航绕世界竖轴，并按“屏幕 up 是否倒置”翻转偏航符号。
 * 这样保留之前世界竖轴方案“打圈不累积 roll”的优点，同时修掉倒挂 180° 后左右反向。
 * roll 仍由 Z/C 滚转键主动改变。</p>
 */
@Mixin(Entity.class)
public abstract class SpaceTurnMixin {

    private static final double TORAD = Math.PI / 180.0;
    private static final double TODEG = 180.0 / Math.PI;

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void polymech$spaceTurn(double yRotDelta, double xRotDelta, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player)) return;
        if (!self.level().dimension().equals(PlanetDimensions.SPACE)) return;

        ci.cancel();

        SpacePlayerData data = SpacePlayerData.get(self);
        if (!data.isInitialized()) {
            data.initFromVanilla(self.getYRot(), self.getXRot());
        }

        double pitchInput = xRotDelta;
        double yawInput = yRotDelta;

        Vector3d facing = data.facing();
        Vector3d left = data.left();

        // ── 旋转（鼠标永不产生 roll）──
        // 俯仰：绕局部 left 轴（鼠标下 → pitchInput>0 → 负角度 → facing.y 减 → 朝下）
        facing.rotateAxis(-0.15 * pitchInput * TORAD, left.x, left.y, left.z);

        // 偏航：绕世界竖轴 (0,-1,0)，保留世界竖轴方案“打圈不累积 roll”。
        // 倒挂后屏幕 up 反向，因此用 up = facing×left 的 y 分量翻偏航符号：
        // roll=0 时 u.y<0 -> +1；roll=180 时 u.y>0 -> -1。
        Vector3d up = facing.cross(left, new Vector3d());
        double yawSign = up.y > 0 ? -1.0 : 1.0;
        if (Math.abs(up.y) < 1.0e-6) yawSign = 1.0; // roll≈90° 的瞬时退化，保持原方向
        facing.rotateAxis(0.15 * yawInput * yawSign * TORAD, 0, -1, 0);
        left.rotateAxis(0.15 * yawInput * yawSign * TORAD, 0, -1, 0);

        // 数值稳定：保持正交归一
        data.orthonormalize();

        // 鼠标输入每帧都会走一次 turn；和 vanilla Entity.turn 一样，
        // 同步更新 old 向量。否则 Camera/Body 会用 partialTick 从旧朝向插值到新朝向，
        // 画面看起来只有 20Hz 的台阶感（方块/场景移动一卡一卡）。
        data.facingO().set(data.facing());
        data.leftO().set(data.left());

        // ── 同步 vanilla yaw/pitch 字段（供移动、渲染等用；不参与相机）──
        double newPitch = -Math.asin(clamp(facing.y, -1, 1)) * TODEG;
        double newYaw = -Math.atan2(facing.x, facing.z) * TODEG;

        float deltaYaw = wrapDegrees((float) (newYaw - self.getYRot()));
        float appliedPitch = net.minecraft.util.Mth.clamp((float) newPitch, -90f, 90f);
        float realDeltaPitch = appliedPitch - self.getXRot();

        self.setYRot(self.getYRot() + deltaYaw);
        self.setXRot(appliedPitch);
        self.yRotO += deltaYaw;
        self.xRotO += realDeltaPitch;
        self.xRotO = net.minecraft.util.Mth.clamp(self.xRotO, -90f, 90f);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float wrapDegrees(float deg) {
        float d = deg % 360.0F;
        if (d >= 180.0F) d -= 360.0F;
        if (d < -180.0F) d += 360.0F;
        return d;
    }
}
