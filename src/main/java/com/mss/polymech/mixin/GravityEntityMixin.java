package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 掉落物 / TNT / 矿车等实体的星球重力（参考 Ad Astra 的 GravityEntityMixin）。
 */
@Mixin({AbstractMinecart.class, ItemEntity.class, PrimedTnt.class})
public abstract class GravityEntityMixin extends Entity {

    protected GravityEntityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void polymech$applyGravity(CallbackInfo ci) {
        double gravity = 0.04 * PlanetDimensions.gravity(level().dimension());
        Vec3 velocity = getDeltaMovement();
        setDeltaMovement(velocity.x(), velocity.y() + 0.04 - gravity, velocity.z());
    }
}
