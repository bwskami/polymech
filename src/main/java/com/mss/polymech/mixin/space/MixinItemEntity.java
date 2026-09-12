package com.mss.polymech.mixin.space;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 对应 space 0.1.0 的 {@code ...space.mixin.common.entity.MixinItemEntity}。
 *
 * <ol>
 *   <li>太空里掉落物不吃"掉出世界"伤害（大坐标自由落体不该被处决）；</li>
 *   <li>太空里跳过 {@code moveTowardsClosestSpace} —— 原版防止掉落物卡在方块边缘的
 *       微推，在太空（深空没有方块、近旁又是物理体）只会造成无意义位移。</li>
 * </ol>
 */
@Mixin(ItemEntity.class)
public abstract class MixinItemEntity {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void polymech$noVoidDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)
                && SpaceWorld.isSpace(((Entity) (Object) this).level())) {
            cir.setReturnValue(false);
        }
    }

    /**
     * 跳过 {@code moveTowardsClosestSpace}。
     *
     * <p>用 {@code @WrapOperation} 而不是 {@code @Redirect} + {@code @Shadow}：
     * 该方法声明在 {@code Entity} 上、{@code ItemEntity} 只是继承，
     * {@code @Shadow} 只认"目标类自身声明"的方法（否则运行期抛
     * {@code @Shadow method ... was not located in the target class}）；
     * {@code @WrapOperation} 直接给到原调用，既不需要 shadow，也没有 protected 跨包访问问题。</p>
     */
    @WrapOperation(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/item/ItemEntity;moveTowardsClosestSpace(DDD)V",
                    ordinal = 0)
    )
    private void polymech$skipClosestSpace(ItemEntity instance, double x, double y, double z,
                                            Operation<Void> original) {
        if (!SpaceWorld.isSpace(((Entity) (Object) this).level())) {
            original.call(instance, x, y, z);
        }
    }
}
