package com.mss.polymech.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拆墙：服务端移动包坐标合法性夹持。
 * {@code clampHorizontal}/{@code clampVertical} 是 private static，
 * 被 handleMovePlayer（×3）与 handleMoveVehicle（×3）共 6 处调用，
 * 把客户端上报坐标夹到 ±3e7 / ±2e7。
 *
 * <p>直接对两个方法做 HEAD 取消、恒等返回（照抄 space 0.1.0 的
 * MixinServerGamePacketListenerImpl）。防作弊意义由"移动过快"等其余
 * 校验保留，越界夹持对太空坐标是致命墙。</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class ServerPacketClampMixin {

    @Inject(method = "clampHorizontal", at = @At("HEAD"), cancellable = true)
    private static void polyMech$clampHorizontal(double value, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(value);
    }

    @Inject(method = "clampVertical", at = @At("HEAD"), cancellable = true)
    private static void polyMech$clampVertical(double value, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(value);
    }
}
