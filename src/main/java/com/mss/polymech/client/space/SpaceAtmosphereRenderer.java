package com.mss.polymech.client.space;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;

/**
 * 屏幕空间行星大气散射（照抄 space mod 的 planet_atmosphere 思路）：
 * 在星球画完后，从主深度缓冲重建视空间坐标，沿视线对每颗行星的大气壳进行 ray march，
 * 再做一次 5x5 平滑后叠加回主画面。
 *
 * <p>天体数据不再在本类里构造：{@link CelestialBodyDataBuffer} 直接拿
 * {@code PlanetRenderObject} 打包（大气壳半径、颜色与行星渲染完全一致），
 * 并与恒星泛光 pass 共享同一个 UBO，由 {@link SpaceRenderer} 每帧统一更新一次。</p>
 */
public final class SpaceAtmosphereRenderer {

    private static final int STEP_COUNT = 24;

    private static SpaceAtmosphereRenderer instance;

    private final CelestialBodyDataBuffer celestialBodyData = CelestialBodyDataBuffer.get();
    private RenderTarget smoothTarget;
    private RenderTarget smoothSaveTarget;
    private RenderTarget swapTarget;
    private PostPass atmospherePass;
    private PostPass blitToSmoothSave;
    private PostPass smoothPass;
    private PostPass blitToMain;
    private int width = -1;
    private int height = -1;
    private boolean disabled;
    private boolean loggedRun;

    private SpaceAtmosphereRenderer() {
    }

    public static SpaceAtmosphereRenderer get() {
        if (instance == null) instance = new SpaceAtmosphereRenderer();
        return instance;
    }

    /**
     * @param view           与星球绘制同一套相机矩阵（{@code SpaceRenderer} 的 spaceView）
     * @param proj           与星球绘制同一套投影矩阵（near=1000m / far=1e13m 的 spaceProj）
     * @param depthTextureId 本帧深度快照的纹理 id，由 {@link SpaceRenderer} 统一提供
     */
    public void render(Matrix4f view, Matrix4f proj, float partialTick, int depthTextureId) {
        if (disabled) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget mainTarget = mc.getMainRenderTarget();
        int w = mainTarget.width;
        int h = mainTarget.height;
        if (w <= 0 || h <= 0) return;

        try {
            try {
                ensureCreated(mainTarget, w, h);
            } catch (java.io.IOException e) {
                Polymech.LOGGER.error("[poly_mech] Failed to create planet atmosphere passes", e);
                disabled = true;
                atmospherePass = null;
                smoothPass = null;
                return;
            }
            if (atmospherePass == null || smoothPass == null) return;

            EffectInstance atmosphereEffect = atmospherePass.getEffect();
            bindAtmosphereUniforms(atmosphereEffect, view, proj, depthTextureId);
            celestialBodyData.bindToShader(atmosphereEffect.getId());
            if (!loggedRun) {
                Polymech.LOGGER.info("[poly_mech] Planet atmosphere effect id={}, step={}", atmosphereEffect.getId(), STEP_COUNT);
                loggedRun = true;
            }

            // 后处理阶段不需要深度测试/面剔除：屏幕四边形不应被深度缓冲剔除或裁剪。
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            atmospherePass.process(partialTick);
            blitToSmoothSave.process(partialTick);

            EffectInstance smoothEffect = smoothPass.getEffect();
            smoothEffect.setSampler("MainScreenSampler", () -> mainTarget.getColorTextureId());
            var screenSize = smoothEffect.getUniform("ScreenSize");
            if (screenSize != null) screenSize.set((float) w, (float) h);
            var outSize = smoothEffect.getUniform("OutSize");
            if (outSize != null) outSize.set((float) w, (float) h);
            smoothPass.process(partialTick);
            blitToMain.process(partialTick);
        } catch (RuntimeException e) {
            Polymech.LOGGER.error("[poly_mech] Planet atmosphere post effect failed, disabling it", e);
            disabled = true;
        } finally {
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.enableCull();
            com.mojang.blaze3d.systems.RenderSystem.disableBlend();
        }
    }

    private void ensureCreated(RenderTarget mainTarget, int w, int h) throws java.io.IOException {
        if (atmospherePass != null && width == w && height == h) return;

        // 分辨率变化时先释放旧 pass，避免每次 resize 都泄漏一组 GPU 程序。
        if (atmospherePass != null) {
            closePasses();
        }

        if (smoothTarget == null) {
            smoothTarget = new TextureTarget(w, h, false, false);
            smoothTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            smoothSaveTarget = new TextureTarget(w, h, false, false);
            smoothSaveTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            swapTarget = new TextureTarget(w, h, false, false);
            swapTarget.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        } else {
            smoothTarget.resize(w, h, false);
            smoothSaveTarget.resize(w, h, false);
            swapTarget.resize(w, h, false);
        }

        ResourceProvider provider = Minecraft.getInstance().getResourceManager();
        atmospherePass = new PostPass(provider, "poly_mech:planet/planet_atmosphere", mainTarget, smoothTarget, false);
        blitToSmoothSave = new PostPass(provider, "minecraft:blit", smoothTarget, smoothSaveTarget, false);
        smoothPass = new PostPass(provider, "poly_mech:planet/smooth_atmosphere", smoothSaveTarget, swapTarget, false);
        blitToMain = new PostPass(provider, "minecraft:blit", swapTarget, mainTarget, false);

        Matrix4f ortho = new Matrix4f().setOrtho(0.0F, (float) w, 0.0F, (float) h, 0.1F, 1000.0F);
        atmospherePass.setOrthoMatrix(ortho);
        blitToSmoothSave.setOrthoMatrix(ortho);
        smoothPass.setOrthoMatrix(ortho);
        blitToMain.setOrthoMatrix(ortho);

        width = w;
        height = h;
        Polymech.LOGGER.info("[poly_mech] Planet atmosphere passes created ({}x{})", w, h);
    }

    private void closePasses() {
        if (atmospherePass != null) { atmospherePass.close(); atmospherePass = null; }
        if (blitToSmoothSave != null) { blitToSmoothSave.close(); blitToSmoothSave = null; }
        if (smoothPass != null) { smoothPass.close(); smoothPass = null; }
        if (blitToMain != null) { blitToMain.close(); blitToMain = null; }
    }

    private void bindAtmosphereUniforms(EffectInstance effect, Matrix4f view, Matrix4f proj, int depthTextureId) {
        Matrix4f invProj = new Matrix4f(proj).invert();
        Matrix4f invView = new Matrix4f(view).invert();
        var iProj = effect.getUniform("iProjMat");
        if (iProj != null) iProj.set(invProj);
        var iView = effect.getUniform("iModelViewMat");
        if (iView != null) iView.set(invView);
        var exposure = effect.getUniform("Exposure");
        if (exposure != null) exposure.set(1.0F);
        var stepCount = effect.getUniform("StepCount");
        if (stepCount != null) stepCount.set(STEP_COUNT);
        var useMcDepth = effect.getUniform("useMinecraftDepth");
        if (useMcDepth != null) useMcDepth.set(0);

        effect.setSampler("DepthSampler", () -> depthTextureId);
        effect.setSampler("SpaceDepthSampler", () -> depthTextureId);
    }
}
