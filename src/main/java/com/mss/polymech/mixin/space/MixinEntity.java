package com.mss.polymech.mixin.space;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 对应 space 0.1.0 的 {@code ...space.mixin.common.entity.MixinEntity}。
 *
 * <p><b>组织原则照 space</b>：一个原版类一个 mixin 文件，与实体相关的太空行为全部集中在这里。
 * 以前散成 4 个文件（{@code EntityGravityMixin} / {@code EntityAbsMoveToMixin} /
 * {@code EntityLoadClampMixin} / {@code SpaceInsideBlocksMixin}），结果"漏改某一处"成了常态。</p>
 *
 * <p>包含四件事：</p>
 * <ol>
 *   <li><b>重力</b>：唯一入口。1.21 的重力有两条路径 —— {@code LivingEntity.travel} 里的局部
 *       {@code d0} 和 {@code Entity.applyGravity()} 直接改 {@code deltaMovement}；
 *       只拦调用点会漏掉另一条（太空里就一直加速下坠）。这里拦返回值本身，按维度倍率缩放。</li>
 *   <li><b>absMoveTo</b>：原方法内部把坐标夹到 ±3e7 再 {@code setPos}，
 *       跨维度进入太空的第一落点会被这堵墙夹回去。</li>
 *   <li><b>load</b>：存档读回时对 xyz 各做一次 {@code Mth.clamp(±3e7/±2e7)}，
 *       不打掉则太空大坐标无法持久化。</li>
 *   <li><b>move</b>：太空里跳过 {@code tryCheckInsideBlocks}（卡在方块内的仙人掌/火/传送门副作用）。</li>
 * </ol>
 */
@Mixin(Entity.class)
public abstract class MixinEntity {

    // ── ① 重力：唯一入口 ──

    /**
     * 按维度倍率缩放 vanilla 重力：主世界/未登记维度 factor=1（逐位不变）、
     * 行星按倍率、太空 0。
     *
     * <p>比 space 更保守的一处：space 是非太空一律给绝对值 {@code G/122.5} 或 {@code 0.08}，
     * 这里改成缩放，保住各实体自身的差异（掉落物 0.04、生物 0.08）。</p>
     */
    @Inject(method = "getGravity", at = @At("RETURN"), cancellable = true)
    private void polymech$gravity(CallbackInfoReturnable<Double> cir) {
        Entity self = (Entity) (Object) this;
        Level level = self.level();
        if (level == null) {
            return;
        }
        float factor = PlanetDimensions.gravity(level.dimension());
        if (factor == 1.0f) {
            return;
        }
        cir.setReturnValue(cir.getReturnValue() * (double) factor);
    }

    // ── ② absMoveTo：去掉 ±3e7 夹持 ──

    @Inject(method = "absMoveTo(DDD)V", at = @At("HEAD"), cancellable = true)
    private void polymech$absMoveTo(double x, double y, double z, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        self.xo = x;
        self.yo = y;
        self.zo = z;
        self.setPos(x, y, z);
        ci.cancel();
    }

    // ── ③ load：去掉存档坐标夹持 ──

    @Redirect(
            method = "load",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D")
    )
    private static double polymech$loadUnclamp(double value, double min, double max) {
        return (value < min || value > max) ? value : Mth.clamp(value, min, max);
    }

    // ── ④ move：太空跳过 tryCheckInsideBlocks ──

    /** 原方法在 {@code Entity} 里是 protected，跨包只能这样拿到。 */
    @Shadow
    protected abstract void tryCheckInsideBlocks();

    @Redirect(
            method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;tryCheckInsideBlocks()V",
                    ordinal = 0)
    )
    private void polymech$skipInsideBlocks(Entity instance) {
        if (!SpaceWorld.isSpace(((Entity) (Object) this).level())) {
            this.tryCheckInsideBlocks();
        }
    }

    // ── ⑤ 碰撞箱：撑到刚好包住"旋转后的身体" ──

    /**
     * 太空里玩家的碰撞箱：<b>普通姿态一律走原版</b>（0.6×1.8，由 vanilla 位姿系统算），
     * 这里不再做任何自定义 —— 动态外包盒、宽度减半、头盒 + 下半身双盒等方案都已废弃。
     *
     * <p>只有<b>超人姿态</b>（太空疾跑）才改写：改成中心 1.6 的 0.6³ 头盒。
     * 那是"钻一格洞"能力本身（0.6³ 各向同性小盒 + 放开旋转伺服），去掉就没有了。</p>
     */
    @Inject(method = "makeBoundingBox", at = @At("HEAD"), cancellable = true)
    private void polymech$spaceBoundingBox(CallbackInfoReturnable<AABB> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof Player) || self.level() == null || !SpaceWorld.isSpace(self.level())) {
            return;
        }
        SpacePlayerData data = SpacePlayerData.get(self);
        if (!data.isInitialized()) {
            return;
        }
        // 普通姿态：直接返回，交回原版 makeBoundingBox()。
        if (!SpacePlayerData.isSuperman(self)) {
            return;
        }
        // 超人姿态：0.6³ 头盒（身体有意悬在盒外）。
        double x = self.getX(), y = self.getY(), z = self.getZ();
        double half = SpacePlayerData.HEAD_BOX_HALF;
        double c = SpacePlayerData.HEAD_BOX_CENTER;
        cir.setReturnValue(new AABB(
                x - half, y + c - half, z - half,
                x + half, y + c + half, z + half));
    }
}
