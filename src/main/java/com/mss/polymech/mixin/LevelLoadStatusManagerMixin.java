package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.client.multiplayer.LevelLoadStatusManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复太空维度切维时的"Waiting for chunk..."卡死：
 * {@link LevelLoadStatusManager} 要等玩家所在区块完成编译才关闭加载画面，
 * 而深空没有区块可编译。这里在太空维度直接报告"已就绪"
 * （space mod 的 MixinReceivingLevelScreen 思路：太空不需要等区块）。
 * 仅影响 {@code tick()} 中这一处判定，其它维度的加载画面不受影响。
 */
@Mixin(LevelLoadStatusManager.class)
public abstract class LevelLoadStatusManagerMixin {

    @Shadow
    @Final
    private ClientLevel level;

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiled(Lnet/minecraft/core/BlockPos;)Z"
        )
    )
    private boolean polymech$readyInSpace(LevelRenderer renderer, BlockPos pos) {
        if (this.level.dimension() == PlanetDimensions.SPACE) {
            return true;
        }
        return renderer.isSectionCompiled(pos);
    }
}
