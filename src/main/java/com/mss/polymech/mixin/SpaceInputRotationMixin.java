package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.core.BlockPos;
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

    /**
     * 零重力下，把**竖直轴**的每 tick 阻尼补成与水平轴一致 —— 只改"阻尼"，不改"方向"。
     *
     * <p><b>为什么必须补</b>：非飞行走的是 {@code LivingEntity.travel} 的地面分支：</p>
     * <pre>
     * vec35 = handleRelativeFrictionAndCalculateMovement(...)   // 里含 moveRelative（我们的 6DOF 输入）
     * setDeltaMovement(vec35.x * f3,  d2 * 0.98,  vec35.z * f3) // ← x/z 有摩擦 f3，y 只有 0.98
     * </pre>
     * <p>原版敢让 y 轴没有摩擦，是因为它**指望重力抵消竖直通道**（{@code d2 -= getGravity()}），
     * 而且走路时 {@code travelVector.y} 恒为 0。**太空是零重力，这个抵消项不存在** ——
     * 于是 6DOF 输入里那个竖直分量（"低头/抬头按 W"）就按 {@code D ← 0.98·D + 输入} 累积：</p>
     * <pre>
     *   固定点 = 输入 / (1 − 0.98) = 50 × 输入
     *   实测：0.0976 / 0.02 = 4.88 格/tick = 97.6 m/s   ← 日志里那条 ~1Hz 锯齿
     *   （0.0976 = 走路输入 0.1 × 身体俯角 0.976）
     * </pre>
     * <p>顶着物理体时，这条 97 m/s 被接触反复顶回、输入又立刻补上 ⇒ 就是"抽搐"。</p>
     *
     * <p><b>怎么补才不改手感</b>：输入本身是"一个速度"（原版在 x/z 上靠摩擦让稳态 ≈ 输入速度）。
     * 所以只要让 y 轴的**每 tick 总阻尼也是 f3**，三个轴就完全同构：
     * 同样的输入在同一方向上得到同样的稳态速度 ⇒ <b>速度方向 = 视线方向（6DOF 手感一模一样）</b>，
     * 而量级被限制在"走路速度"而不是累积到 100 m/s。</p>
     *
     * <p>账：travel 随后还会对 y 乘一次 0.98，所以这里先乘 {@code f3 / 0.98}，两者相乘正好等于 f3
     * —— 与 x/z 的阻尼逐位一致。输入那一侧同理（{@link #polymech$scaleVerticalInput}）。</p>
     *
     * <p>只在<b>太空 + 零重力 + 非飞行 + 客户端</b>生效；其它情况一个字节都不碰。</p>
     */
    @Inject(method = "baseTick", at = @At("TAIL"))
    private void polymech$equalizeZeroGravityY(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player player) || !self.level().isClientSide) return;
        if (!self.level().dimension().equals(PlanetDimensions.SPACE)) return;
        if (player.getAbilities().flying || player.isSpectator()) return;
        if (player.getGravity() > 1.0E-6) return;         // 有重力时原版那套本来就自洽
        double f3 = zeroGFriction(self);
        Vec3 d = self.getDeltaMovement();
        self.setDeltaMovement(d.x, d.y * (f3 / 0.98) , d.z);
    }

    /** 与 travel 里 `f3 = onGround ? f2*0.91 : 0.91` 同一个算法，用于把 y 的阻尼对齐到 x/z。 */
    private static double zeroGFriction(Entity self) {
        BlockPos pos = self.getOnPos();
        float f2 = self.level().getBlockState(pos).getFriction(self.level(), pos, self);
        return self.onGround() ? f2 * 0.91F : 0.91F;
    }

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

        // 竖直分量与水平分量接受**同一个** f3 阻尼（travel 随后还会给 y 乘 0.98，故这里先除掉），
        // 否则零重力下竖直输入会被积成 50×（见 polymech$equalizeZeroGravityY 的账）。
        if (self.getGravity() <= 1.0E-6) {
            my *= zeroGFriction(self) / 0.98;
        }

        self.setDeltaMovement(self.getDeltaMovement().add(mx, my, mz));
        ci.cancel();
    }
}
