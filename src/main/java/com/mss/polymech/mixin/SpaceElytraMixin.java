package com.mss.polymech.mixin;

import com.mss.polymech.space.SpaceWorld;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 太空维度禁用鞘翅。
 *
 * <p>理由：太空里玩家位置由物理刚体积分后回写（见 {@code EntityPhysicsDriveMixin}），
 * 而鞘翅滑翔走的是原版那一套 —— 直接改写 {@code deltaMovement} 并按原版碰撞推进。
 * 两者同时生效会让位移出现第二个来源。太空机动交给太空服/推进器。</p>
 *
 * <p>{@code canElytraFly} 是 NeoForge 在 {@code ElytraItem} 上打的补丁方法，
 * 因此注入时需要 {@code remap = false}；该方法只在鞘翅可用时才会被调用，
 * 这里直接返回 false 与"先判断可用性再返回 false"等价。</p>
 */
@Mixin(ElytraItem.class)
public abstract class SpaceElytraMixin {

    @Inject(method = "canElytraFly", at = @At("HEAD"), cancellable = true, remap = false)
    private void polymech$noElytraInSpace(ItemStack stack, LivingEntity entity,
            CallbackInfoReturnable<Boolean> cir) {
        if (SpaceWorld.isSpace(entity.level())) {
            cir.setReturnValue(false);
        }
    }
}
