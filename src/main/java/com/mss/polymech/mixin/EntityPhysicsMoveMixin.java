package com.mss.polymech.mixin;

import com.mss.polymech.physics.PhysicsEntityManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物理接管期间取消原版移动 —— 否则原版重力/方块碰撞会与 Rapier 的结果互相打架。
 *
 * <p>对应 space 模组 MPS 的 {@code MixinEntity.move}（他们是用 Redirect 改写 setPos，
 * 这里更直接：整个 move 交给物理层，位置由 {@link PhysicsEntityManager} 同步）。</p>
 */
@Mixin(Entity.class)
public abstract class EntityPhysicsMoveMixin {

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void polymech$physicsMove(MoverType type, Vec3 delta, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.level() instanceof ServerLevel && PhysicsEntityManager.isAttached(self)) {
            ci.cancel();
        }
    }
}
