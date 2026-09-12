package com.mss.polymech.mixin;

import com.mss.polymech.space.SpaceWorld;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 拆墙：Player.tick 末尾的坐标自愈 ——
 * {@code double d0 = Mth.clamp(getX(), ±2.9999999E7)} 两处，且越界时 {@code setPos} 直接拽回。
 * 太空维度中每 tick 都会把玩家拉回 3e7 处，必须重定向放行。
 *
 * <p>Player.tick 中 Mth.clamp(DDD)D 恰好只有这两处（已核对 1.21.1 源码），
 * 故单条无 ordinal 的 Redirect 即可同时接管两个调用点。
 * 非太空维度保持原版行为，逐位一致。</p>
 *
 * <p>对应 space 0.1.0 的 MixinPlayer（ordinal 0/1 双 Redirect）。</p>
 */
@Mixin(Player.class)
public abstract class PlayerTickClampMixin {

    @Redirect(
        method = "tick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(DDD)D")
    )
    private double polyMech$tickClamp(double value, double min, double max) {
        Entity self = (Entity) (Object) this;
        if (SpaceWorld.isSpace(self.level())) {
            return value;
        }
        return Mth.clamp(value, min, max);
    }
}
