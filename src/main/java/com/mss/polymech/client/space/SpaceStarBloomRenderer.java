package com.mss.polymech.client.space;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;

import java.io.IOException;

/**
 * 恒星泛光后处理（照抄 space mod 的 star_bloom 思路）：
 * 在星球画完、地形/粒子也画完之后，对每颗恒星叠加「球状光晕 + 镜头星芒」，再 blit 回主画面。
 *
 * <p>天体数据由 {@link CelestialBodyDataBuffer} 与屏幕空间大气散射共用同一个 UBO，
 * 每帧由 {@link SpaceRenderer} 统一更新一次，本类只负责绑定与绘制。</p>
 *
 * <p>链路结构与 space mod 的 {@code shaders/post/star/star_bloom.json} 一致：
 * {@code main → bloomTarget → main}，即一次全屏计算加一次 blit。</p>
 *
 * <p><b>深度用法与 space mod 不同：这里只用深度判定「像素是不是 MC 几何体」，不用它算距离。</b>
 * space mod 的 {@code ScreenToWorld} 能拿深度直接反解世界距离，是因为它让 MC 主投影与
 * 自己的太空投影共用同一对 near/far（mixin 把 {@code getDepthFar} 改成 {@code FarCompress*4}，
 * 太空投影取 {@code setPerspective(4194304, 0.05)}，数值相同、Z 方向翻转），
 * 于是 {@code max(1 - mainDepth, spaceDepth)} 天然就是「更近的那个表面」，
 * 再配 {@code PositionCompression} 与 {@code fittedY()} 容差把天体全部压进精度甜区。
 * 本项目是把星球直接画进主缓冲、用标准 Z（near=1000m / far=1e13m）的真实米坐标，
 * 主深度在 AFTER_PARTICLES 时是混合投影的，两者区间互相重叠，反解出的距离是假的。</p>
 *
 * <p>所以本 pass 绑两张深度纹理：{@code DepthSampler}（AFTER_PARTICLES 的主深度）与
 * {@code SkyDepthSampler}（AFTER_SKY 星球层画完时的深度底），两者都由 {@link SpaceRenderer} 提供。
 * 着色器只比较它们的大小得出 MC 几何体掩码，方块与玩家因此能挡住光晕。
 * 遮挡是<b>逐像素</b>的，不是逐恒星的整体开关：被挡住的部分消失，没挡住的部分照常露出来，
 * 于是方块和玩家只在光晕/星芒上剪出自己的轮廓。
 * （space mod star_bloom.fsh:95 那种采样恒星中心一点、被挡就整颗 continue 的写法不要照搬：
 * 它会让遮挡变成全有全无，那一条阈值在 space 里同时兼做行星遮挡，本项目不需要。）
 * 行星遮挡不走深度，仍是着色器里的角空间解析判定。详见 star_bloom.fsh 顶部注释。</p>
 */
public final class SpaceStarBloomRenderer {

    /**
     * 镜头星芒的方向线条数，对应 space mod 的 star_scatter_count（默认 16）。
     *
     * <p>方向线只覆盖半圆（着色器里 {@code angleStep = PI / ScatterCount}），
     * 每条双向延长，所以 16 实际画出的是 32 道射线。</p>
     */
    private static final int SCATTER_COUNT = 16;
    /** 整体亮度倍率，对应 space mod 的 exposure（默认 1.0）。 */
    private static final float EXPOSURE = 1.0F;
    /**
     * 光晕强度。space mod 固定为 3.0，但本项目恒星已经有 3D 日冕公告板
     * （{@code SpaceRenderer#drawSunGlows}），这里压到 2.0 避免两层叠加过曝。
     */
    private static final float GLOW_STRENGTH = 2.0F;
    /** 星芒强度，space mod 固定为 1.0。 */
    private static final float STREAK_STRENGTH = 1.0F;
    /** 星芒横向宽度衰减系数，space mod 固定为 75。 */
    private static final float STREAK_WIDTH = 75.0F;
    /** 星芒长度衰减基准，space mod 固定为 17.5：值越大星芒越短。 */
    private static final float STREAK_FALLOFF = 17.5F;
    /**
     * 星芒长度抖动幅度（space mod 为 ±5）。
     *
     * <p>决定 2N 道射线的长短差多少：falloff 落在
     * {@code [STREAK_FALLOFF - STREAK_JITTER, STREAK_FALLOFF + STREAK_JITTER]}，
     * 射线可见长度大致与 falloff 成反比，所以 ±5 / 17.5 对应最长与最短约 1.8 倍之差。
     * 嫌射线长短太一致就往上调（7~8），嫌太乱就往下调。</p>
     *
     * <p>着色器用的是 space mod 原版的离散 argmin 归属、不是相邻线插值（插值会把
     * 长短差抹平，看上去就是「每条射线都一样长」），所以调大这个值会同时加重
     * 角平分线上的亮度突跳 —— 转视角时能看到一道细线闪一下，这是原版本来的观感。</p>
     */
    private static final float STREAK_JITTER = 5.0F;

    private static SpaceStarBloomRenderer instance;

    private final CelestialBodyDataBuffer celestialBodyData = CelestialBodyDataBuffer.get();
    private RenderTarget bloomTarget;
    private PostPass bloomPass;
    private PostPass blitToMain;
    private int width = -1;
    private int height = -1;
    private boolean disabled;
    private boolean loggedRun;

    private SpaceStarBloomRenderer() {
    }

    public static SpaceStarBloomRenderer get() {
        if (instance == null) instance = new SpaceStarBloomRenderer();
        return instance;
    }

    /**
     * @param view              与星球绘制同一套相机矩阵（{@code SpaceRenderer} 的 spaceView）
     * @param proj              与星球绘制同一套投影矩阵（near=1000m / far=1e13m 的 spaceProj）
     * @param mcDepthTextureId  AFTER_PARTICLES 时主深度快照的纹理 id
     * @param skyDepthTextureId AFTER_SKY 星球层画完时的深度底纹理 id
     */
    public void render(Matrix4f view, Matrix4f proj, float partialTick, int mcDepthTextureId, int skyDepthTextureId) {
        if (disabled) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        int w = mainTarget.width;
        int h = mainTarget.height;
        if (w <= 0 || h <= 0) return;

        try {
            try {
                ensureCreated(mainTarget, w, h);
            } catch (IOException e) {
                Polymech.LOGGER.error("[poly_mech] Failed to create star bloom passes", e);
                disabled = true;
                bloomPass = null;
                return;
            }
            if (bloomPass == null) return;

            EffectInstance bloomEffect = bloomPass.getEffect();
            bindBloomUniforms(bloomEffect, view, proj, w, h, mcDepthTextureId, skyDepthTextureId);
            celestialBodyData.bindToShader(bloomEffect.getId());
            if (!loggedRun) {
                Polymech.LOGGER.info("[poly_mech] Star bloom effect id={}, scatter={}", bloomEffect.getId(), SCATTER_COUNT);
                loggedRun = true;
            }

            // 后处理阶段不需要深度测试/面剔除：屏幕四边形不应被深度缓冲剔除或裁剪。
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            bloomPass.process(partialTick);
            blitToMain.process(partialTick);
        } catch (RuntimeException e) {
            Polymech.LOGGER.error("[poly_mech] Star bloom post effect failed, disabling it", e);
            disabled = true;
        } finally {
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private void ensureCreated(RenderTarget mainTarget, int w, int h) throws IOException {
        if (bloomPass != null && width == w && height == h) return;

        // 分辨率变化时先释放旧 pass，避免每次 resize 都泄漏一组 GPU 程序。
        if (bloomPass != null) {
            closePasses();
        }

        if (bloomTarget == null) {
            bloomTarget = new TextureTarget(w, h, false, false);
            bloomTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        } else {
            bloomTarget.resize(w, h, false);
        }

        ResourceProvider provider = Minecraft.getInstance().getResourceManager();
        bloomPass = new PostPass(provider, "poly_mech:star/star_bloom", mainTarget, bloomTarget, false);
        blitToMain = new PostPass(provider, "minecraft:blit", bloomTarget, mainTarget, false);

        Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float) w, 0.0F, (float) h, 0.1F, 1000.0F);
        bloomPass.setOrthoMatrix(ortho);
        blitToMain.setOrthoMatrix(ortho);

        width = w;
        height = h;
        Polymech.LOGGER.info("[poly_mech] Star bloom passes created ({}x{})", w, h);
    }

    private void closePasses() {
        if (bloomPass != null) { bloomPass.close(); bloomPass = null; }
        if (blitToMain != null) { blitToMain.close(); blitToMain = null; }
    }

    private void bindBloomUniforms(EffectInstance effect, Matrix4f view, Matrix4f proj, int w, int h,
                                   int mcDepthTextureId, int skyDepthTextureId) {
        Matrix4f invProj = new Matrix4f(proj).invert();
        Matrix4f invView = new Matrix4f(view).invert();
        setMatrix(effect, "tProjMat", proj);
        setMatrix(effect, "tModelViewMat", view);
        // 逆矩阵只用于 ScreenToDir（固定 z_ndc = 0 反解视线方向），不参与任何深度重建。
        setMatrix(effect, "iProjMat", invProj);
        setMatrix(effect, "iModelViewMat", invView);

        var outSize = effect.getUniform("OutSize");
        if (outSize != null) outSize.set((float) w, (float) h);
        var scatterCount = effect.getUniform("ScatterCount");
        if (scatterCount != null) scatterCount.set(SCATTER_COUNT);
        var exposure = effect.getUniform("Exposure");
        if (exposure != null) exposure.set(EXPOSURE);
        var glowStrength = effect.getUniform("GlowStrength");
        if (glowStrength != null) glowStrength.set(GLOW_STRENGTH);
        var streakStrength = effect.getUniform("StreakStrength");
        if (streakStrength != null) streakStrength.set(STREAK_STRENGTH);
        var streakWidth = effect.getUniform("StreakWidth");
        if (streakWidth != null) streakWidth.set(STREAK_WIDTH);
        var streakFalloff = effect.getUniform("StreakFalloff");
        if (streakFalloff != null) streakFalloff.set(STREAK_FALLOFF);
        var streakJitter = effect.getUniform("StreakJitter");
        if (streakJitter != null) streakJitter.set(STREAK_JITTER);

        // 两张深度只用于比大小得出 MC 几何体掩码，着色器不会拿它们反解距离（见类注释）。
        effect.setSampler("DepthSampler", () -> mcDepthTextureId);
        effect.setSampler("SkyDepthSampler", () -> skyDepthTextureId);
    }

    private static void setMatrix(EffectInstance effect, String name, Matrix4f value) {
        var uniform = effect.getUniform(name);
        if (uniform != null) uniform.set(value);
    }
}
