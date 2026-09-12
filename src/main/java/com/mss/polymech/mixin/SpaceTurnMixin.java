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
 * 太空自由旋转：<b>颈部模型</b>（照 space 0.0.6 的 {@code Entity#turn} 实现）。
 *
 * <p>鼠标输入只加在"头"上，头在自己的小锥内可以自由转；超出颈部锥（±π/18 偏航、
 * ±π/180 俯仰）的部分被推到"身体"上 —— 现实里脖子转不动了人就转躯干。
 * 视线 = 身体 ∘ 头部偏角（见 {@link SpacePlayerData#rebuildHeadFromBody()}）。</p>
 *
 * <p>这样就没有欧拉角奇点：俯仰到 ±90° 附近航向不再退化（修掉"只有朝上/朝下两个状态"），
 * 一路翻到倒挂也连续。身体姿态由 {@code SpaceBodyTiltMixin} 用四元数直接渲染，
 * 物理碰撞箱也跟着身体转。</p>
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

        // ── 转向（SpacePlayerData#turn）──
        // 俯仰绕屏幕左轴、**偏航绕世界竖轴** —— 这是早就定下的硬要求：
        // 打转绝不能让视角歪头（绕"头的 up"转的话，低头/抬头后再打转地平线就会歪）。
        // 颈部锥在 turn() 里处理：超出 ±π/18 / ±π/180 的偏角推给身体，
        // 于是"头先转、转不动了身体跟上"，修掉俯仰到 ±90° 附近航向退化
        // （看起来"只有朝上/朝下两个状态"）。
        data.turn(yawInput, pitchInput);

        Vector3d facing = data.facing();

        // 鼠标输入每帧都会走一次 turn；和 vanilla Entity.turn 一样，
        // 同步更新 old 向量。否则 Camera/Body 会用 partialTick 从旧朝向插值到新朝向，
        // 画面看起来只有 20Hz 的台阶感（方块/场景移动一卡一卡）。
        data.facingO().set(data.facing());
        data.leftO().set(data.left());
        // 身体也要逐帧存旧姿态：身体是逐帧跟着视线动的，而 saveOld() 只在 tick 末尾跑，
        // 于是 partialTick 插值会在每个 tick 边界"抽"一下（身体转动时一抽一抽的来源之一）。
        data.bodyFacingO().set(data.bodyFacing());
        data.bodyLeftO().set(data.bodyLeft());

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
