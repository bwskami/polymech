package com.mss.polymech.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空自由旋转：相机直接从 facing/left 向量构建，覆盖 setRotation；
 * 第三人称/正面视角的锚点挪到"身体坐标系里的头部"。
 *
 * <p>yaw/pitch 从 facing 提取（无歧义），roll 从 left 相对"无 roll 参考系"
 * 的偏离提取，并用与上一帧的连续性约束消除 ±180° 符号跳变。
 * 这避免了"每帧重建→提取"导致的 roll 高频抖动。</p>
 */
@Mixin(Camera.class)
public abstract class SpaceCameraMixin {

    private static final double TORAD = Math.PI / 180.0;
    private static final double TODEG = 180.0 / Math.PI;

    @Shadow
    private Entity entity;

    @Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Shadow
    public abstract boolean isDetached();

    @Shadow
    public abstract float getPartialTickTime();

    /**
     * 第三人称/正面：把镜头锚点从"脚底正上方 eyeHeight（世界竖直）"换成
     * <b>身体坐标系里的眼睛位置</b>（{@code 实体位置 + Q_body·(0, eyeHeight, 0)}）。
     *
     * <p>相机转的是<b>头</b>的朝向，第三人称/正面本来就该站在"头的前后方"：
     * 锚点放在头上，原版随后沿视线方向后退，镜头就正对头部 —— 人物永远在画面中心。
     * 这样"身体绕脚底转导致人偏出画面"的问题从根上消失，模型一行都不用动。
     * 第一人称（!detached）保持原版锚点不变。</p>
     */
    @WrapOperation(
            method = "setup",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setPosition(DDD)V")
    )
    private void polymech$headAnchor(Camera self, double x, double y, double z, Operation<Void> operation) {
        Entity e = this.entity;
        if (!this.isDetached() || !(e instanceof Player)
                || !e.level().dimension().equals(PlanetDimensions.SPACE)) {
            operation.call(self, x, y, z);
            return;
        }
        SpacePlayerData data = SpacePlayerData.get(e);
        if (!data.isInitialized()) {
            operation.call(self, x, y, z);
            return;
        }
        float pt = this.getPartialTickTime();
        double px = Mth.lerp(pt, e.xo, e.getX());
        double py = Mth.lerp(pt, e.yo, e.getY());
        double pz = Mth.lerp(pt, e.zo, e.getZ());
        // 原版传进来的 y = 插值后的脚底 y + 眼高，所以眼高直接减出来（不必去碰它的私有字段）
        double eye = y - py;
        if (eye <= 0.0) {
            operation.call(self, x, y, z);
            return;
        }
        Vector3d off = data.eyeOffset(eye, pt, new Vector3d());
        operation.call(self, px + off.x, py + off.y, pz + off.z);
    }

    /**
     * 在 vanilla 第一次 setRotation 之后，用向量计算的正确值再调一次 setRotation 覆盖。
     */
    @Inject(
            method = "setup",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Camera;setRotation(FFF)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER)
    )
    private void polymech$vectorCamera(BlockGetter level, Entity entity, boolean detached,
            boolean thirdPersonReverse, float partialTick, CallbackInfo ci) {
        if (!(entity instanceof Player)) return;
        if (!entity.level().dimension().equals(PlanetDimensions.SPACE)) return;

        SpacePlayerData data = SpacePlayerData.get(entity);
        if (!data.isInitialized()) return;

        // ── 插值 facing/left ──
        Vector3d facing = lerp(data.facingO(), data.facing(), partialTick);
        Vector3d left = lerp(data.leftO(), data.left(), partialTick);
        // 重新正交归一
        facing.normalize();
        double d = left.dot(facing);
        left.sub(facing.x * d, facing.y * d, facing.z * d);
        if (left.lengthSquared() > 1e-12) left.normalize();

        // ── yaw/pitch：从 facing 提取（无歧义）──
        float pitch = (float) (-Math.asin(Mth.clamp(facing.y, -1, 1)) * TODEG);
        float yaw = (float) (-Math.atan2(facing.x, facing.z) * TODEG);

        // ── roll：left 相对无 roll 参考系的偏离 ──
        // 无 roll 时 left 应为：Ry(-(yaw+180)) * Rx(-pitch) 后的 (1,0,0)
        Vector3d refLeft = new Vector3d(1, 0, 0);
        refLeft.rotateX(-pitch * TORAD);
        refLeft.rotateY(-(yaw + 180) * TORAD);
        // 正交化到与 facing 垂直
        double dd = refLeft.dot(facing);
        refLeft.sub(facing.x * dd, facing.y * dd, facing.z * dd);
        if (refLeft.lengthSquared() > 1e-12) refLeft.normalize();

        double rollRad = -Math.atan2(
                left.cross(refLeft, new Vector3d()).dot(facing),
                left.dot(refLeft));
        double roll = rollRad * TODEG;

        // ── 连续性约束：把 roll 归一到离上帧最近的等价角 ──
        roll = polymech$closestAngle(roll, data.getLastRoll());
        data.setLastRoll(roll);

        // ── 覆盖相机旋转 ──
        float finalRoll = (float) roll;
        if (thirdPersonReverse) {
            this.setRotation(yaw + 180.0F, -pitch, -finalRoll);
        } else {
            this.setRotation(yaw, pitch, finalRoll);
        }
    }

    /**
     * 向量插值（反平行安全）。
     * <p>
     * 普通 lerp 在 a≈-b（传送重建朝向恰好与传送前相反时）会插出近零向量，
     * 后续正交化回退到"任意垂直轴"→ 相机架翻转 → 表现为鼠标上下左右全反。
     * 反平行时改走确定性大圆路径：绕与 a 垂直的固定轴转过 angle·t。
     * </p>
     */
    @Unique
    private static Vector3d lerp(Vector3d a, Vector3d b, float t) {
        double dot = a.x * b.x + a.y * b.y + a.z * b.z;
        if (dot < -0.999) {
            Vector3d ref = Math.abs(a.y) < 0.9 ? new Vector3d(0, 1, 0) : new Vector3d(1, 0, 0);
            Vector3d axis = new Vector3d(a).cross(ref, new Vector3d()).normalize();
            double angle = Math.PI * t;
            Vector3d r = new Vector3d(a).mul(Math.cos(angle));
            r.add(axis.x * Math.sin(angle), axis.y * Math.sin(angle), axis.z * Math.sin(angle));
            return r;
        }
        return new Vector3d(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t);
    }

    /** 把 angle 调整成离 reference 最近的等价角（差值在 ±180 内） */
    @Unique
    private static double polymech$closestAngle(double angle, double reference) {
        double diff = (angle - reference) % 360.0;
        if (diff > 180.0) diff -= 360.0;
        if (diff < -180.0) diff += 360.0;
        return reference + diff;
    }
}
