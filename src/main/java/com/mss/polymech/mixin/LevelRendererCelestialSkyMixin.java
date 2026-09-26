package com.mss.polymech.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mss.polymech.Polymech;
import com.mss.polymech.mps.kelvin.physical.celestial_world.ClientCelestialWorld;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 地表维度（站在某颗行星上）<b>只掐掉原版日月，保留原版天空的其余部分</b>。
 *
 * <h2>职责</h2>
 * 站在行星地表时，原版 {@code LevelRenderer.renderSky} 里那两次"给日月换贴图"的调用
 * （太阳、月亮各一次）被换成一张<b>全透明贴图</b>；天幕渐变、日落红染、星空、以及雾
 * <b>全部照常保留</b>。太空维度与主世界完全不受影响。
 *
 * <h2>为什么改成"只掐日月"（用户 2026-09-25 拍板）</h2>
 * 上一版这个 mixin 是在 {@code renderSky} 的 HEAD 直接 {@code ci.cancel()} —— <b>整片原版天空都不画</b>。
 * 那是照 space 0.1.3 的
 * {@code org.cn_grass_block.sunshine.mixin.renderer.MixinLevelRenderer} 抄的判据，
 * 当时的理由是"原版日月混在天幕上会让'我们的天体画没画、画在哪'无法判读，先清干净"——
 * 属于<b>临时排障手段</b>，本类旧注释也写明了"在我们补上自己的地表天空之前"。
 * <p>代价实测下来太大：天空变成一个平坦的雾色，没有渐变、没有日落、没有星空，
 * 用户的原话是"天空太干净了"，并主张"把原版日月关掉就可以了"。现在我们的天体已经验证到位
 * （方位 / 姿态帧 / 画面对齐三条判据全 PASS），不再需要"清干净"这种排障手段。</p>
 *
 * <h2>为什么用"换贴图"而不是"跳过绘制"</h2>
 * 原版的日月是**和星空共用同一段姿势栈**画的（{@code pushPose} → 画太阳 → 画月亮 → 画星空 → {@code popPose}）。
 * 用 {@code @Inject} 跳掉中间的绘制，很容易让 {@code pushPose}/{@code popPose} 与
 * {@code RenderSystem} 状态（blendFunc、setShaderColor）失配 —— 那类错会污染后面所有渲染。
 * <b>换一张贴图则不可能改变任何状态</b>：顶点照画、矩阵照用、混合照走，
 * 只是采样出来的像素 alpha = 0 ⇒ 在 {@code (SRC_ALPHA, ONE)} 混合下贡献恰好为 0。
 * "失败模式安全"也是选它的理由：万一路径判据没命中，结果只是<b>原版日月又露出来</b>，而不是花屏或崩溃。
 *
 * <h2>判据与注入点都按实产物的源码核对过</h2>
 * <ul>
 *   <li>锚点：{@code build/mcsrc/net/minecraft/client/renderer/LevelRenderer.java} 第 1663 / 1671 行
 *       —— {@code renderSky} 全方法里<b>只有</b>这两处 {@code setShaderTexture}，正是日月；
 *       其余 {@code setShaderTexture}（327 雨、373 雪、1559 末地天空、2031 力场）都在别的方法里，不受影响。</li>
 *   <li>日月贴图路径 = {@code textures/environment/sun.png} / {@code moon_phases.png}
 *       —— 用路径串比对而不是 {@code @Shadow} 那两个 private 字段：{@code @Shadow} 解析失败会
 *       <b>直接把启动打崩</b>，而串比对失败的最坏结果只是"日月没藏住"。</li>
 *   <li>判据用 {@code ClientCelestialWorld.getCelestialWorld() != null}（只在地表维度非 null），
 *       与旧版同一判据，因此不会误伤太空维度的 cubemap 天空盒。</li>
 * </ul>
 */
@Mixin(LevelRenderer.class)
public class LevelRendererCelestialSkyMixin {

    /** 只报一次：证明锚点在运行时确实生效（Mixin 命中是静默的，没日志就无法区分"没生效"和"没触发"）。 */
    private static boolean polymech$reportedFirstReplace;

    @WrapOperation(
            method = "renderSky",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V")
    )
    private static void polymech$hideVanillaSunAndMoon(int slot, ResourceLocation texture, Operation<Void> original) {
        // ⚠️ 两个坑，都是本项目踩过的：
        //   ① 被包装的 RenderSystem.setShaderTexture 是**静态**方法 ⇒ 处理器必须也是 static。
        //   ② 不能把"全透明贴图"写成带初始化器的 static final 字段：那要求 Mixin 把本类的 <clinit>
        //      合并进 LevelRenderer，而"是否真的合并了"离线无法验证；万一没合并，字段就是 null，
        //      第一次拦截会把 null 贴图交给 setShaderTexture（§ 见 MixinEntityRenderDispatcher 的同类注释）。
        //      所以在这里**每次现建**（一帧至多两次，代价可忽略）。
        if (ClientCelestialWorld.getCelestialWorld() != null && texture != null) {
            String path = texture.getPath();
            if ("textures/environment/sun.png".equals(path) || "textures/environment/moon_phases.png".equals(path)) {
                if (!polymech$reportedFirstReplace) {
                    polymech$reportedFirstReplace = true;
                    Polymech.LOGGER.info("[地表天空] 原版日月已隐藏（贴图 {} → 全透明）；"
                            + "原版天空的渐变/日落/星空保留", path);
                }
                original.call(slot, ResourceLocation.fromNamespaceAndPath(
                        Polymech.MOD_ID, "textures/environment/blank.png"));
                return;
            }
        }
        original.call(slot, texture);
    }

    // ⚠️ 这里不能再放 public / 非 private 的 static 辅助方法：Mixin 会以
    //    "contains non-private static method ..." 直接把启动打崩（§31.8 的教训）。
}
