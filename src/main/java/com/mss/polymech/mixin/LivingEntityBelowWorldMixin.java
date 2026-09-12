package com.mss.polymech.mixin;

import com.mss.polymech.space.SpaceWorld;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空维度取消虚空伤害：行星游戏坐标 Y 虽压平到黄道面，但玩家可以自由俯冲，
 * 跌出 min_y(-64) 以下不应被 out_of_world 伤害处决（space mod 的 NoVoidDamage 行为）。
 * 仅太空维度生效；只挂 {@link LivingEntity}（含玩家），掉落物等非生物实体仍按原版丢弃。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBelowWorldMixin {

    @Inject(method = "onBelowWorld", at = @At("HEAD"), cancellable = true)
    private void polymech$noVoidDamageInSpace(CallbackInfo ci) {
        if (SpaceWorld.isSpace(((Entity) (Object) this).level())) {
            ci.cancel();
        }
    }
}
