package com.mss.polymech.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拆墙：{@link Level#isInWorldBoundsHorizontal(BlockPos)}（private static，
 * 硬编码 ±3000 万）—— {@code isInWorldBounds}（实体生成合法性）与
 * {@code isInSpawnableBounds}（summon 命令、出生点合法性）都经它把关，
 * 大坐标处 summon / setSpawn 等直接被 "Invalid position" 拒绝。
 *
 * <p>对应 space 0.1.0 MixinLevel（无条件 true）。这里用坐标值域做保守放行：
 * 原版存档坐标永远落在 ±3e7 内，行为逐位一致；只有太空大坐标才会越界穿透。
 * （方法拿不到 Level 实例，无法直接判维度。）</p>
 */
@Mixin(Level.class)
public abstract class LevelBoundsMixin {

    @Inject(method = "isInWorldBoundsHorizontal", at = @At("HEAD"), cancellable = true)
    private static void polymech$spaceBounds(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (pos.getX() < -30_000_000 || pos.getX() >= 30_000_000
                || pos.getZ() < -30_000_000 || pos.getZ() >= 30_000_000) {
            cir.setReturnValue(true);
        }
    }
}
