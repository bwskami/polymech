package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空 6DOF 与"移动"的唯一接触点：把原版"输入 → 世界位移"那一步的旋转轴换掉。
 *
 * <p><b>原版只有一个这样的地方</b>（{@code Entity.moveRelative}）：</p>
 * <pre>
 * public void moveRelative(float amount, Vec3 relative) {
 *    Vec3 vec3 = getInputVector(relative, amount, this.getYRot());   // 只绕 yaw 转
 *    this.setDeltaMovement(this.getDeltaMovement().add(vec3));
 * }
 * // getInputVector: v = normalize/scale(relative, amount)
 * //                  return (v.x·cos(yaw) − v.z·sin(yaw), v.y, v.z·cos(yaw) + v.x·sin(yaw))
 * </pre>
 * <p>走路的 WASD、游泳、创造飞行（含垂直分量 {@code yya}）全部经过这里
 * （{@code Player.travel} → {@code LivingEntity.travel} → {@code moveRelative}）。</p>
 *
 * <p>所以自由视角和移动<b>不需要二选一</b>：原版继续负责"算多少"（速度、摩擦、阻力、
 * 重力、碰撞、疾跑……），我们只负责"往哪走" —— 把 {@code getInputVector} 里的 yaw 旋转
 * 换成 6DOF 基底（facing / screenLeft / screenUp）。</p>
 *
 * <p>这正是 space 0.0.6 在 {@code MixinLocalPlayer.aiStep$rotateFlyMove} 里做的事
 * （它只改了创造飞行分支的 {@code Vec3.add}，我们挂在这个更靠上游的单点上，
 * 顺带覆盖走路/游泳/任意未来分支）。</p>
 *
 * <p>注意与"物理接管"的分工：这里只改 {@code deltaMovement} 的方向，
 * 真正的位置仍由物理刚体积分后回写（见 {@code EntityPhysicsDriveMixin}）。</p>
 */
@Mixin(Entity.class)
public abstract class SpaceInputRotationMixin {

    @Inject(method = "moveRelative", at = @At("HEAD"), cancellable = true)
    private void polymech$rotateInputBySpaceBasis(float amount, Vec3 relative, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player)) return;
        if (!self.level().dimension().equals(PlanetDimensions.SPACE)) return;
        // 创造/旁观飞行走原版：W 沿水平朝向、Space/Shift 沿世界竖直 —— 建造测试才顺手。
        // （自由视角本身不受影响，只是飞行时移动方向按原版算。）
        if (((Player) self).getAbilities().flying || ((Player) self).isSpectator()) return;

        SpacePlayerData data = SpacePlayerData.get(self);
        if (!data.isInitialized()) return;

        // ── 照抄原版 getInputVector 的"归一化 + 缩放"部分，只换掉旋转 ──
        //   垂直分量一律沿用原版传进来的 relative.y，不做任何自造输入。
        //   （早先这里自己补过"Space/Shift → 上下"：零重力 + 无阻尼下那点输入会无限累积，
        //     表现就是"在失重里一直往上飞"。space 的太空垂直移动靠创造飞行，或抬头按 W。）
        double lenSqr = relative.lengthSqr();
        if (lenSqr < 1.0E-7) {
            ci.cancel(); // 与原版一致：无输入不产生位移
            return;
        }
        Vec3 scaled = (lenSqr > 1.0 ? relative.normalize() : relative).scale(amount);
        double inX = scaled.x;
        double inY = scaled.y;
        double inZ = scaled.z;

        Vector3d facing = data.facing();
        // SpacePlayerData 里的 left 按 MC 镜像约定存：屏幕左 = -left。
        Vector3d screenLeft = new Vector3d(data.left()).negate();
        // 屏幕 up = facing × screenLeft；roll 之后自动跟着视角倾斜。
        Vector3d screenUp = new Vector3d(facing).cross(screenLeft, new Vector3d()).normalize();

        // 输入约定与原版一致：x = 左、y = 上、z = 前
        double mx = facing.x * inZ + screenLeft.x * inX + screenUp.x * inY;
        double my = facing.y * inZ + screenLeft.y * inX + screenUp.y * inY;
        double mz = facing.z * inZ + screenLeft.z * inX + screenUp.z * inY;

        self.setDeltaMovement(self.getDeltaMovement().add(mx, my, mz));
        ci.cancel();
    }
}
