package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 按维度应用星球重力（参考 Ad Astra 的 LivingEntityMixin#travel 实现）。
 * <p>
 * 在原版 travel 前把 Y 轴速度预加 (0.08 - 星球重力加速度)，
 * 随后原版 travel 会照常扣除 0.08，最终净效果就是每个维度不同的重力加速度。
 * 主世界重力比例为 1.0，因此与未修改时完全一致。
 * </p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGravityMixin extends Entity {

    protected LivingEntityGravityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "travel", at = @At("HEAD"))
    private void polymech$applyPlanetGravity(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self instanceof Player player && player.getAbilities().flying) return;
        if (self.isInWater() || self.isInLava() || self.isFallFlying() || self.hasEffect(MobEffects.SLOW_FALLING)) {
            return;
        }

        float gravity = PlanetDimensions.gravity(self.level().dimension());
        if (gravity <= 0.0f) return;

        float newGravity = 0.08f * gravity;
        Vec3 velocity = self.getDeltaMovement();
        self.setDeltaMovement(velocity.x(), velocity.y() + 0.08f - newGravity, velocity.z());
    }
}
