package com.mss.polymech.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mss.polymech.client.space.RenderCompression;
import com.mss.polymech.dimension.PlanetDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * <b>太空维度里把 MC 主投影的 far 改成"距离压缩的 far"</b>，照 space 0.1.3 的
 * {@code MixinGameRenderer.modifyDepthFar}（那一版是
 * {@code !enableSpaceRender ? original : farCompressionDistance * 2.0}）。
 *
 * <h2>为什么必须让两套投影"对表"</h2>
 * 太空维度里同时存在两套投影，它们写进的是**两张不同的深度缓冲**（天体走独立的
 * {@code spaceRenderTarget}，世界走 MC 主缓冲），而后处理要把两者放在一起比"谁更近"：
 * <pre>
 *   MC 主投影（正常 Z，near=0.05，far=getDepthFar()）: depth = 1 − 0.05/z
 *   天体投影（反向 Z，near=getDepthFar()，far=0.05）: depth = 0.05/z
 * </pre>
 * 两者是**镜像**，所以 space 的 {@code max(1 − mainDepth, spaceDepth)} 才等价于
 * "取更近的那个表面"。镜像成立的关键是**两边共用的那个 0.05**：MC 侧是它的 near，
 * 天体侧是它的 far。本 mixin 把 MC 的 far 抬到与天体投影的 near 同一个数，
 * 于是"两套投影用的是同一对数字，只是对调了顺序"这句话在代码里成立、可核对。
 *
 * <p>不这么做的后果是 2026-09-30 那个 bug：两套投影各用各的 near/far，
 * 深度值互相不可比，任何"比深度大小"的判据都是假的（实测临界距离只有 2.80 格，
 * 详见 {@code native/jni-smoketest/DepthOcclusionProbe.java}）。</p>
 *
 * <h2>安全性（动手前核对过）</h2>
 * <ul>
 *   <li>{@code getDepthFar()} 在 MC 1.21.1 里**全库只有一个调用点** ——
 *       {@code GameRenderer.renderLevel} 里构造主投影那一行
 *       （{@code .wipbak/mcsrc/net/minecraft/client/renderer/GameRenderer.java:991}）。
 *       改它**只影响投影矩阵**，不碰雾、不碰视锥剔除（视锥是从投影矩阵推的）。</li>
 *   <li>判据与 {@code SpaceRenderer} 的 {@code inSpace} **同一条件**
 *       （维度 == {@code PlanetDimensions.SPACE} 且压缩启用），所以地表维度
 *       仍然是原版的 far（768），地形深度精度一点不变。</li>
 *   <li>只改 far：near 仍是原版的 {@code PROJECTION_Z_NEAR = 0.05F}，
 *       所以近处世界几何体的深度映射（{@code 1 − 0.05/z}）**形式完全不变**，
 *       精度也不变（实测 512 格处仍是 ~0.31 格的分辨率）。</li>
 *   <li>失败模式安全：万一没命中，far 就是原值，画面退化成"两套投影不对表"，
 *       而不是花屏或崩溃。</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public class SpaceDepthFarMixin {

    /** 只报一次：Mixin 命中是静默的，没日志就无法区分"没生效"和"没触发"。 */
    private static boolean polymech$reportedDepthFar;

    @ModifyReturnValue(method = "getDepthFar", at = @At("RETURN"))
    private float polymech$spaceDepthFar(float original) {
        if (!RenderCompression.enabled) return original;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return original;
        if (!mc.level.dimension().equals(PlanetDimensions.SPACE)) return original;
        if (!polymech$reportedDepthFar) {
            polymech$reportedDepthFar = true;
            com.mss.polymech.Polymech.LOGGER.info(
                    "[poly_mech] 太空维度深度对表已生效：getDepthFar() {} → {}（= RenderCompression.FAR × 2）",
                    original, RenderCompression.FAR * 2.0);
        }
        return (float) (RenderCompression.FAR * 2.0);
    }
}
