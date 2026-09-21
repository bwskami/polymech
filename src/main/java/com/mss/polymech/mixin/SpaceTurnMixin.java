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
 * 太空自由旋转：鼠标输入进入 6DOF 朝向（{@link SpacePlayerData#turn(double, double)}）。
 *
 * <p>输入轴全部取<b>视线自己的局部轴</b>：俯仰绕屏幕左轴、偏航绕屏幕竖轴 ——
 * 两者恒垂直于视线，任何姿态（含正上/正下极点）都不会退化成"绕视线自转"。
 * 这是"真 360° 自由视角"的关键；旧的"偏航绕世界竖轴"水平锁定相机在极点必然空转
 * （球面上不存在处处非零的连续水平切向量场）。</p>
 *
 * <p>颈部模型（照 space 0.0.6）：鼠标输入先加在"头"的局部偏角上，头在自己的小锥内可以
 * 自由转；超出颈部锥（±40°/±42° 偏航、+30°/−26° 俯仰）的部分被推到"身体"上 ——
 * 现实里脖子转不动了人就转躯干。视线 = 身体 ∘ 颈部偏角（见
 * {@link SpacePlayerData#turn(double, double)} 第 3 步与内部的 {@code rederiveBody()}）。</p>
 *
 * <p>3-DOF 的固有代价：俯仰状态下打转会让地平线跟着滚，需要回正时用 Z/C 自己滚
 * （见 {@code SpaceTravelMixin#polymech$handleRollKeys}）。</p>
 *
 * <p>这样就没有欧拉角奇点：俯仰到 ±90° 附近航向不再退化，一路翻到倒挂也连续。
 * 身体姿态由 {@code SpaceBodyTiltMixin} 用四元数直接渲染，物理碰撞箱也跟着身体转。</p>
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
        // 两个轴都是视线局部轴：屏幕左轴（俯仰）、屏幕竖轴（偏航）。
        // 颈部锥在 turn() 里处理：超出锥的偏角推给身体，于是"头先转、转不动了身体跟上"。
        data.turn(yawInput, pitchInput);

        Vector3d facing = data.facing();

        // old 向量只在**输入真的改变了朝向**时刷新，零增量调用一律不碰它。
        //
        // 为什么必须这么写：{@code Minecraft.runTick()} 每帧都会调
        // {@code mouseHandler.handleAccumulatedMovement()}，而它在鼠标被 grab 时**无条件**调
        // {@code turnPlayer()} → {@code Entity.turn(0,0)} —— 鼠标没动也会走到这里。
        // 若此时也把 O 刷成当前值，那么任何**tick 级**的朝向变化（最典型的就是
        // {@link com.mss.polymech.client.space.LevelAssist} 的滚转回正）就永远拿不到
        // partialTick 插值：相机每帧都被直接按到"回正后"的值上，于是 20Hz 一个台阶 —— 就是那个"顿"。
        //
        // 有输入时仍然刷新 O，保持原意：鼠标输入直接落到最新值，不做插值（不然手感会发飘）。
        if (Math.abs(yawInput) > 1.0e-9 || Math.abs(pitchInput) > 1.0e-9) {
            data.facingO().set(data.facing());
            data.leftO().set(data.left());
            // 身体也要一起存旧姿态：身体是逐帧跟着视线动的，而 saveOld() 只在 tick 末尾跑。
            data.bodyFacingO().set(data.bodyFacing());
            data.bodyLeftO().set(data.bodyLeft());
        }

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
