package com.mss.polymech.client.physics;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.renderer.RenderPassGuard;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * 牵引枪专用的两个自定义渲染通道 —— <b>本项目第一次用 {@code RenderType.create}</b>，
 * 所以把"为什么必须自己造、以及状态位为什么是这几个"写清楚。
 *
 * <h2>为什么不用 {@code RenderType.lines()}（第一版的做法）</h2>
 * 那一版的视觉就是一根 1px 直线，用户的原话是"太廉价了"。线宽没法用
 * {@code RenderSystem.lineWidth()} 补：核心 profile 下 GL 把线宽钳到 1，这也正是
 * 参考实现（机械动力航空学 {@code PhysicsStaffClientHandler}）自己要造一个
 * {@code LineOutline} 的原因 —— 它是<b>用四边形拼出来的线</b>。
 * 于是这里也走四边形：线宽、加色发光、十字截面全靠几何，不依赖 GL 线宽。
 *
 * <h2>{@link #BEAM}：加色混合（发光感）</h2>
 * <ul>
 *   <li>{@code ADDITIVE_TRANSPARENCY} = {@code blendFunc(SRC_ALPHA, ONE)} ⇒ 叠加变亮，
 *       这是"能量束"而不是"塑料管"的关键；</li>
 *   <li>{@code COLOR_WRITE}（写颜色、<b>不写深度</b>）⇒ 光束互相交叠不会互相遮挡；</li>
 *   <li>{@code NO_CULL} ⇒ 十字双四边形总有正面朝你，不用管绕序；</li>
 *   <li>格式是 {@code POSITION_COLOR}（无光照/无贴图）⇒ 亮度由顶点色直接给，
 *       所以是"自发光"，不会被洞穴里的黑暗吃掉。</li>
 * </ul>
 *
 * <h2>{@link #OVERLAY}：普通透明（高亮框/棋盘面）</h2>
 * 用 {@code TRANSLUCENT_TRANSPARENCY}（{@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA}）—— 棋盘面要能
 * "看穿一层还有一层"，加色混合叠两层就白成一片了。同样不写深度，避免半透明面互相 z-fight。
 *
 * <p>两者都保留 {@code LEQUAL_DEPTH_TEST}（builder 的默认值）：光束应当被墙挡住 ——
 * 参考的 staff overlay 是 {@code NO_DEPTH_TEST}，那是"必须永远可见"的 HUD 式高亮；
 * 我们的牵引枪是场景内的实体（GMod 的光束也会被墙挡），所以不跟着关深度测试。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PhysgunRenderTypes {

    /** 顶点缓冲大小（字节）：比原版的 1536 宽，减少长光束的 flush 次数。 */
    private static final int BUFFER_SIZE = 8192;

    /** 光束：加色发光的四边形（十字截面 + 端点光斑）。 */
    public static final RenderType BEAM = RenderType.create(
            "poly_mech_physgun_beam",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            BUFFER_SIZE,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.ADDITIVE_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /** 高亮/悬停：普通透明，用来画棋盘面与厚线框。 */
    public static final RenderType OVERLAY = RenderType.create(
            "poly_mech_physgun_overlay",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            BUFFER_SIZE,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    /**
     * 悬停框的棋盘贴图：16×16、4px 格、白与全透明交替。
     *
     * <p>参考用的是 Create 的 {@code AllSpecialTextures.CHECKERED}（{@code PhysicsStaffRenderHandler}
     * 的 {@code withFaceTexture(...)}）。本项目不依赖 Create，所以自带一张
     * —— 生成器是 {@code native/jni-smoketest/MakeCheckerTexture.java}（幂等，可重跑）。</p>
     */
    private static final ResourceLocation CHECKER_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/misc/physgun_checker.png");

    /**
     * 悬停格/锁定标记的<b>贴图面</b>：{@code POSITION_COLOR_TEX_LIGHTMAP} + 半透明 + 不写深度 + 不剔面。
     *
     * <p>为什么不用几何拼棋盘：贴图每个面只要 1 个四边形（几何拼法要 27 个），而且格纹可以画得
     * 比"3×3 大方格"细得多 —— 这更接近参考那张 CHECKERED 的观感。shader 用公开的
     * {@code POSITION_COLOR_TEX_LIGHTMAP_SHADER}，顶点给 {@code FULL_BRIGHT}（自发光、不受洞穴光照影响）。</p>
     */
    public static final RenderType CHECKER = RenderType.create(
            "poly_mech_physgun_checker",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            BUFFER_SIZE,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(CHECKER_TEXTURE, false, true))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));

    private PhysgunRenderTypes() {
    }

    /**
     * 开一个牵引枪渲染通道 —— <b>必须</b>与 {@link #endPass} 成对，且一次只开一个。
     *
     * <p>为什么不直接 {@code buffers.getBuffer(type)}：见 {@link RenderPassGuard} 的类注释
     * （2026-09-29 的 {@code Not building!} 崩溃）。这个包装把"一个通道一趟"变成强制约定，
     * 违反时给出能读懂的异常。</p>
     */
    public static VertexConsumer beginPass(MultiBufferSource.BufferSource buffers, RenderType type) {
        RenderPassGuard.open(type);
        return buffers.getBuffer(type);
    }

    /** 关掉通道（写完立刻调）。 */
    public static void endPass(MultiBufferSource.BufferSource buffers, RenderType type) {
        RenderPassGuard.close(type);
        buffers.endBatch(type);
    }
}
