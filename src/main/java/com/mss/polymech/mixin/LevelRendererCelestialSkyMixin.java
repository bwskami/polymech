package com.mss.polymech.mixin;

import com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 地表维度（站在某颗行星上）<b>不画原版天空</b> —— 照抄 space 的
 * {@code org.cn_grass_block.sunshine.mixin.renderer.MixinLevelRenderer.renderSky}
 * （clean-room：抄它的<b>判断条件</b>与注入点，不抄实现）。
 *
 * <h2>为什么必须关</h2>
 * 原版 {@code LevelRenderer.renderSky} 一口气画完：天幕渐变、<b>太阳</b>、<b>月亮</b>、
 * 星空、以及天空雾。我们在地表维度要呈现的是"这颗行星在宇宙里的真实天空"，
 * 原版日月混在同一个天幕上，会让"我们的天体到底画没画、画在哪"完全无法判读
 * （实测现象就是"星球上还挂着原版日月"）。所以先清干净再看。
 *
 * <h2>为什么条件是"地表维度"，不是"太空维度"</h2>
 * space 用的是 {@code ClientCelestialWorld.getCelestialWorld() != null}：
 * 它只在地表维度非 null（进入太空维度时会被清掉）。用这个条件就<b>不会误伤</b>
 * 我们太空维度那套走 MC 原生管线的天空盒（{@code SpaceDimensionEffects}）。
 * 照抄它的判据、而不是自己另想一个，正是为了避免这种误伤。
 *
 * <h2>一起接受的代价（照 space）</h2>
 * 星空与天空雾也一起没了 ⇒ 在我们补上"自己的地表天空"之前，地表天幕是纯黑。
 * 这正是本轮需要的效果。
 *
 * <p>签名按本机 MC 源码逐字核对过：
 * {@code LevelRenderer:1598 renderSky(Matrix4f, Matrix4f, float, Camera, boolean, Runnable)}。</p>
 */
@Mixin(LevelRenderer.class)
public class LevelRendererCelestialSkyMixin {

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void polymech$hideVanillaSkyOnCelestialWorld(Matrix4f frustumMatrix, Matrix4f projectionMatrix,
                                                         float partialTick, Camera camera, boolean isFoggy,
                                                         Runnable skyFogSetup, CallbackInfo ci) {
        if (ClientCelestialWorld.getCelestialWorld() != null) {
            ci.cancel();
        }
    }
}
