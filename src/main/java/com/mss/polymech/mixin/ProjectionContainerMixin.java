package com.mss.polymech.mixin;

import com.mss.polymech.physics.ProjectionManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让<b>投影维度里</b>的容器对玩家"仍然有效"。
 *
 * <p>物理体上的箱子/熔炉活在隐藏维度（{@code poly_mech:projection_world}），
 * 而玩家在太空维度。原版 {@code stillValid} 比的是"玩家到打开的那个方块的距离" ——
 * 跨维度必然超距，结果是<b>箱子打开又立刻关上</b>（GUI 一闪而过、还刷一条警告）。</p>
 *
 * <p>这里只对投影维度的方块改写判定：改成"玩家离它所属物理体够近"
 * （见 {@link ProjectionManager#isProjectionContainerValid}）——
 * 语义上正是"玩家够得着那个箱子"。普通世界里的容器<b>一行都不受影响</b>
 * （判定为 false 时不取消，原版逻辑照跑）。</p>
 *
 * <p>覆盖面：{@code BaseContainerBlockEntity} 是箱子、熔炉、漏斗、发射器、桶等的共同父类，
 * 一处注入即可覆盖绝大多数容器。</p>
 */
@Mixin(BaseContainerBlockEntity.class)
public abstract class ProjectionContainerMixin {

    @Inject(method = "stillValid", at = @At("HEAD"), cancellable = true)
    private void polymech$projectionStillValid(Player player, CallbackInfoReturnable<Boolean> cir) {
        BaseContainerBlockEntity self = (BaseContainerBlockEntity) (Object) this;
        if (ProjectionManager.isProjectionContainerValid(self, player)) {
            cir.setReturnValue(true);
        }
    }
}
