package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 按维度修改生物重力加速度（参考 space mod 的 MixinLivingEntity#modifyGravityAttribute）。
 * <p>
 * 1.21.1 的 {@code LivingEntity.travel} 会把 {@code getGravity()} 存为局部变量，
 * 我们直接修改这个局部变量。主世界为 0.08，星球按比例，太空为 0。
 * </p>
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityGravityMixin extends Entity {

    protected LivingEntityGravityMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    @ModifyExpressionValue(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getGravity()D"))
    private double space$modifyGravity(double original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().dimension().location().equals(PlanetDimensions.SPACE.location())) {
            return 0.0;
        }
        float gravity = PlanetDimensions.gravity(self.level().dimension());
        return 0.08 * gravity;
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void space$cancelVoidDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)
                && level().dimension().location().equals(PlanetDimensions.SPACE.location())) {
            cir.setReturnValue(false);
        }
    }
}
