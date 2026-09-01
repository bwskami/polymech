package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 附魔之瓶重力（参考 Ad Astra 的 ThrownExperienceBottleMixin）。
 */
@Mixin(ThrownExperienceBottle.class)
public abstract class ThrownExperienceBottleGravityMixin extends Entity {

    protected ThrownExperienceBottleGravityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "getDefaultGravity", at = @At("HEAD"), cancellable = true)
    private void polymech$getDefaultGravity(CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(0.07 * PlanetDimensions.gravity(level().dimension()));
    }
}
