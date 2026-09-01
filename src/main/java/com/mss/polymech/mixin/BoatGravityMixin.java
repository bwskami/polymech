package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 船重力（参考 Ad Astra 的 BoatMixin）。
 */
@Mixin(Boat.class)
public abstract class BoatGravityMixin extends Entity {

    protected BoatGravityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "floatBoat", at = @At("TAIL"))
    private void polymech$applyBoatGravity(CallbackInfo ci) {
        double gravity = -0.04 * PlanetDimensions.gravity(level().dimension());
        Vec3 velocity = getDeltaMovement();
        setDeltaMovement(velocity.x(), velocity.y() + 0.04 + gravity, velocity.z());
    }
}
