package com.mss.polymech.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 第一人称手：修掉"准星划过视野球正上/正下极时手抽一下"。
 *
 * <p><b>成因</b>（1.21.1 {@code ItemInHandRenderer#renderHandsWithItems}）：</p>
 * <pre>
 * float f1 = Mth.lerp(pt, playerEntity.xRotO, playerEntity.getXRot());   // 只给地图倾角用
 * float f2 = Mth.lerp(pt, playerEntity.xBobO, playerEntity.xBob);        // 每 tick 追 50%
 * float f3 = Mth.lerp(pt, playerEntity.yBobO, playerEntity.yBob);
 * mulPose(Rx((playerEntity.getViewXRot(pt) - f2) * 0.1F));               // "手滞后于视角"
 * mulPose(Ry((playerEntity.getViewYRot(pt) - f3) * 0.1F));
 * </pre>
 * <p>手本身的朝向是跟着相机（视图空间）走的，这两项只是 0.1 的小滞后量。但
 * {@code SpaceTurnMixin} 写回 vanilla 的 yaw/pitch 用的是标准欧拉表示：视线一过极点，
 * 它会翻到等价分支（yaw ±180、pitch 反射），**方向连续、角度值一帧跳 180°**。
 * 于是滞后项瞬间变成 {@code 0.1 × 180 = 18°}，几 tick 后再被 xBob/yBob 平滑回来 ——
 * 就是那一下"抽"。</p>
 *
 * <p><b>改法</b>：把这两个滞后项的**被减数**（vanilla 的 xBob/yBob 平滑角）换成同源值，
 * 使整个式子正好等于 {@code (连续角 − 手的平滑角) * 0.1}：</p>
 * <pre>
 * 本函数返回 view − (cont − lag)
 *   ⇒ vanilla 的 (view − 本返回值) * 0.1 = (cont − lag) * 0.1
 * </pre>
 * <p>{@code cont}/{@code lag} 都来自 {@link SpacePlayerData} 的连续分支（过极点不跳），
 * 与 vanilla 语义完全一致；被减数里的 {@code view} 用同一个 {@code partialTick} 现算，
 * 与原表达式逐位抵消，不必去猜 {@code getViewXRot} 调用点写在哪个 owner 上。</p>
 *
 * <p>只在太空维度、且朝向已初始化时接管；其它维度/未初始化一律原样调用。
 * {@code Mth.lerp} 的 ordinal 0/1/2 依次是 xRot（地图倾角）、xBob、yBob —— 顺序与
 * space 0.1.3 的 {@code sunshine.MixinItemInHandRenderer} 相同（那份在 1.21.1 上可用）。
 * 地图那一项（ordinal 0）不动：vanilla pitch 在极点只是"反射"、本身连续。</p>
 */
@Mixin(ItemInHandRenderer.class)
public abstract class SpaceFirstPersonHandMixin {

    /** 俯仰滞后项的被减数（对应 {@code lerp(pt, xBobO, xBob)}）。 */
    @WrapOperation(
            method = "renderHandsWithItems",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 1))
    private float polymech$pitchSway(float delta, float start, float end, Operation<Float> original,
            @Local LocalPlayer player) {
        SpacePlayerData data = polymech$handData(player);
        if (data == null) {
            return original.call(delta, start, end);
        }
        float cont = data.contPitchLerped(delta);
        float lag = data.handPitchLagLerped(delta);
        return player.getViewXRot(delta) - (cont - lag);
    }

    /** 偏航滞后项的被减数（对应 {@code lerp(pt, yBobO, yBob)}）。 */
    @WrapOperation(
            method = "renderHandsWithItems",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;lerp(FFF)F", ordinal = 2))
    private float polymech$yawSway(float delta, float start, float end, Operation<Float> original,
            @Local LocalPlayer player) {
        SpacePlayerData data = polymech$handData(player);
        if (data == null) {
            return original.call(delta, start, end);
        }
        float cont = data.contYawLerped(delta);
        float lag = data.handYawLagLerped(delta);
        return player.getViewYRot(delta) - (cont - lag);
    }

    /** 只在太空维度、朝向已初始化时接管；否则返回 null 走原版。 */
    private static SpacePlayerData polymech$handData(LocalPlayer player) {
        if (player == null || player.level() == null
                || !player.level().dimension().equals(PlanetDimensions.SPACE)) {
            return null;
        }
        SpacePlayerData data = SpacePlayerData.get(player);
        return data.isInitialized() ? data : null;
    }
}
