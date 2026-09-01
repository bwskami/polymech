package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 投掷物重力（参考 Ad Astra 的 ThrowableProjectileMixin）。
 */
@Mixin(ThrowableProjectile.class)
public abstract class ThrowableProjectileGravityMixin extends Entity {

    protected ThrowableProjectileGravityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "getDefaultGravity", at = @At("HEAD"), cancellable = true)
    private void polymech$getDefaultGravity(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(0.03 * PlanetDimensions.gravity(level().dimension()));
    }
}
