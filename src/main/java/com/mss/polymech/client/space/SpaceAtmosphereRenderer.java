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
     * @param view             与星球绘制同一套相机矩阵（{@code SpaceRenderer} 的 spaceView）
     * @param proj             与星球绘制同一套投影矩阵（{@link SpaceRenderer} 的 spaceProj）
     * @param mcDepthTextureId AFTER_PARTICLES 的主深度快照（<b>只含</b> MC 世界几何体 ——
     *                         星球层深度已在 AFTER_SKY 被抹掉，见 {@code SpaceRenderer}）
     * @param skyDepthTextureId AFTER_SKY 的太空底深度（<b>只有</b>天空盒+星球，世界尚未绘制；
     *                         用来重建星球表面位置）
     */
    public void render(Matrix4f view, Matrix4f proj, float partialTick,
                       int mcDepthTextureId, int skyDepthTextureId) {
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
            bindAtmosphereUniforms(atmosphereEffect, view, proj, mcDepthTextureId, skyDepthTextureId);
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

    private void bindAtmosphereUniforms(EffectInstance effect, Matrix4f view, Matrix4f proj,
                                        int mcDepthTextureId, int skyDepthTextureId) {
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
        // ★ 2026-09-30（第十二轮）：按 space 置 **1** —— 视图位置取"世界与天体里**更近**的那个表面"：
        //       max((1 − mainDepth) × useMinecraftDepth, spaceDepth)
        //   这条式子成立的前提是本轮补齐的结构：两套投影共用同一对数字、只是对调了顺序
        //   （{@code SpaceDepthFarMixin} 把 getDepthFar() 抬到 FAR×2，天体投影取 (FAR×2, 0.05)），
        //   于是 `1 − mainDepth` 与 `spaceDepth` 是**精确镜像**（都等于 0.05/z，可代数验证），
        //   不是"差不多"。置 0（旧写法）也能跑，因为 fsh 开头那条 "mainDepth < 1.0 ⇒ 输出 0"
        //   已经把"世界挡在天体前面"的像素提前掐掉了；改成 1 是为了让式子本身自洽，
        //   而不是依赖那条早退。
        if (useMcDepth != null) useMcDepth.set(1);

        effect.setSampler("DepthSampler", () -> mcDepthTextureId);
        // ★ SpaceDepthSampler = **天体层独立缓冲的深度**（{@code spaceRenderTarget.getDepthTextureId()}），
        //   不再是主深度的副本：ScreenToWorld 要用它重建星球表面位置，而它现在是**反向 Z** 下
        //   spaceProj 自己的深度值 ⇒ 与 iProjMat（spaceProj 的逆）严格配套。
        //   曾经这里绑"主深度在 AFTER_SKY 的副本"，那是因为天体画在主缓冲里；
        //   本轮天体改画进独立缓冲（space 的结构），第十一轮那套副本机制已整体删除。
        effect.setSampler("SpaceDepthSampler", () -> skyDepthTextureId);
    }
}
