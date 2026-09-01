package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 箭矢重力（参考 Ad Astra 的 AbstractArrowMixin）。
 */
@Mixin(AbstractArrow.class)
public abstract class AbstractArrowGravityMixin extends Entity {

    protected AbstractArrowGravityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void polymech$applyArrowGravity(CallbackInfo ci) {
        double gravity = 0.05 * PlanetDimensions.gravity(level().dimension());
        Vec3 velocity = getDeltaMovement();
        setDeltaMovement(velocity.x(), velocity.y() + 0.05 - gravity, velocity.z());
    }
}
