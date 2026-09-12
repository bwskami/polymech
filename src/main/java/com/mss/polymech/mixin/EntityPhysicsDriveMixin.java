package com.mss.polymech.mixin;

import com.mss.polymech.physics.PhysicsClientHooks;
import com.mss.polymech.physics.ServerPlayerPhysics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * space(MPS) 式"物理替代移动"的注入点。
 *
 * <p>打在 {@link Entity#move} 里<b>真正应用位移</b>的那次 {@code setPos}（ordinal 1）上：
 * 客户端接管时把原版位移交给客户端物理世界（转成力、位置从刚体读回），
 * 不再执行原版 {@code setPos}；未接管（服务端 / 物理未就绪）时原样调用。</p>
 *
 * <p>注意：{@code LocalPlayer.move} 只是委托给 {@code super.move}，真正的 {@code setPos}
 * 在 {@code Entity.move} 里，因此必须混入 {@code Entity}（与 space 一致）。
 * 客户端专属逻辑通过 {@link PhysicsClientHooks} 转发，保证专用服务端不加载客户端类。</p>
 */
@Mixin(Entity.class)
public abstract class EntityPhysicsDriveMixin {

    @Redirect(
            method = "move",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/Entity;setPos(DDD)V",
                    ordinal = 1)
    )
    private void polymech$physicsDrive(Entity entity, double x, double y, double z,
                                       @com.llamalad7.mixinextras.sugar.Local(ordinal = 1) Vec3 delta) {
        // 客户端半边（本地玩家）与服务端半边（ServerPlayer）各自驱动：
        // 两端同一套物理，才能让 vanilla 的 "moved wrongly" 校验残差≈0（不再闪回）
        if (PhysicsClientHooks.tryDrive(entity, delta) || ServerPlayerPhysics.tryDrive(entity, delta)) {
            return;
        }
        entity.setPos(x, y, z);
    }
}
