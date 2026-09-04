package com.mss.polymech.client.space;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderObject;
import com.mss.polymech.space.RealAstroData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.PostPass;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕空间行星大气散射（照抄 space mod 的 planet_atmosphere 思路）：
 * 在星球画完后，从主深度缓冲重建视空间坐标，沿视线对每颗行星的大气壳进行 ray march，
 * 再做一次 5x5 平滑后叠加回主画面。
 *
 * <p>渲染数据直接使用 {@link PlanetRenderObject}，不再重复构造天体列表，
 * 保证大气壳半径、颜色与行星渲染完全一致。</p>
 */
public final class SpaceAtmosphereRenderer {

    private static final int UBO_SIZE = 8192;
    private static final int MAX_STARS = 16;
    private static final int MAX_PLANETS = 64;
    private static final int MAX_BLACKHOLES = 16;
    private static final int STAR_STRUCT_FLOATS = 16;
    private static final int PLANET_STRUCT_FLOATS = 20;
    private static final int BLACKHOLE_STRUCT_FLOATS = 12;
    private static final int STEP_COUNT = 24;

    private static SpaceAtmosphereRenderer instance;

    private UniformBuffer celestialBodyData;
    private ByteBuffer celestialBuffer;
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

    public void render(Matrix4f view, Matrix4f proj,
                       List<PlanetRenderObject> bodies,
                       double camRealX, double camRealY, double camRealZ,
                       float partialTick) {
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

            updateCelestialBodyData(bodies, camRealX, camRealY, camRealZ);

            EffectInstance atmosphereEffect = atmospherePass.getEffect();
            bindAtmosphereUniforms(atmosphereEffect, view, proj);
            celestialBodyData.bindToShader(atmosphereEffect.getId(), "CelestialBodyData");
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

    private void bindAtmosphereUniforms(EffectInstance effect, Matrix4f view, Matrix4f proj) {
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

        Minecraft mc = Minecraft.getInstance();
        effect.setSampler("DepthSampler", () -> mc.getMainRenderTarget().getDepthTextureId());
        effect.setSampler("SpaceDepthSampler", () -> mc.getMainRenderTarget().getDepthTextureId());
    }

    private void updateCelestialBodyData(List<PlanetRenderObject> bodies,
                                         double camRealX, double camRealY, double camRealZ) {
        if (celestialBodyData == null) {
            celestialBodyData = new UniformBuffer(UBO_SIZE);
            celestialBuffer = MemoryUtil.memAlloc(UBO_SIZE);
        }

        ByteBuffer buffer = celestialBuffer;
        buffer.clear();

        // 收集恒星与行星，沿用 PlanetRenderObjectFactory 的同一批对象。
        List<PlanetRenderObject> stars = new ArrayList<>();
        List<PlanetRenderObject> planets = new ArrayList<>();
        for (PlanetRenderObject body : bodies) {
            if (body.visual().isGlowing()) {
                stars.add(body);
            } else {
                planets.add(body);
            }
        }

        // ===== StarCount + starlist =====
        int starCount = Math.min(stars.size(), MAX_STARS);
        buffer.putInt(starCount);
        buffer.putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < MAX_STARS; i++) {
            if (i < starCount) {
                PlanetRenderObject star = stars.get(i);
                double starRelX = star.posX() - camRealX;
                double starRelY = star.posY() - camRealY;
                double starRelZ = star.posZ() - camRealZ;
                putVec3(buffer, starRelX, starRelY, starRelZ);
                putVec3(buffer, starRelX, starRelY, starRelZ);
                float[] starColor = star.visual().baseColor();
                if (starColor == null) starColor = new float[]{1.0F, 1.0F, 1.0F};
                putVec4(buffer, starColor[0], starColor[1], starColor[2], 1.0F);
                buffer.putFloat((float) star.radius());
                putPaddingFloats(buffer, 3);
            } else {
                putStarPadding(buffer);
            }
        }

        // ===== PlanetCount + planetlist =====
        int planetCount = Math.min(planets.size(), MAX_PLANETS);
        buffer.putInt(planetCount);
        buffer.putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < MAX_PLANETS; i++) {
            if (i < planetCount) {
                PlanetRenderObject planet = planets.get(i);
                RealAstroData data = RealAstroData.byId(planet.planetName());
                AtmoData atmo = AtmoData.forBody(data, planet);
                double relX = planet.posX() - camRealX;
                double relY = planet.posY() - camRealY;
                double relZ = planet.posZ() - camRealZ;
                buffer.putFloat((float) relX);
                buffer.putFloat((float) relY);
                buffer.putFloat((float) relZ);
                buffer.putFloat(atmo.gravity); // std140: vec3 Pos + float g 共 16 字节
                buffer.putFloat((float) relX);
                buffer.putFloat((float) relY);
                buffer.putFloat((float) relZ);
                buffer.putFloat((float) planet.radius()); // std140: vec3 RealPos + float R 共 16 字节
                buffer.putFloat((float) atmo.renderHeight);
                buffer.putFloat((float) atmo.realHeight);
                buffer.putFloat(atmo.temperature);
                buffer.putFloat(atmo.molarMass);
                buffer.putFloat(atmo.seaLevelDensity);
                putPaddingFloats(buffer, 3); // std140: vec4 AtmosphericColor 需要 16 字节对齐
                putVec4(buffer, atmo.color[0], atmo.color[1], atmo.color[2], 1.0F);
            } else {
                putPlanetPadding(buffer);
            }
        }

        // ===== BlackHoleCount + blackholelist =====
        buffer.putInt(0);
        buffer.putInt(0).putInt(0).putInt(0);
        for (int i = 0; i < MAX_BLACKHOLES; i++) {
            putBlackHolePadding(buffer);
        }

        if (buffer.position() > UBO_SIZE) {
            Polymech.LOGGER.error("[poly_mech] CelestialBodyData UBO overflow: {} > {}", buffer.position(), UBO_SIZE);
        }

        buffer.flip();
        celestialBodyData.update(buffer, false);
    }

    private static void putVec3(ByteBuffer buffer, double x, double y, double z) {
        buffer.putFloat((float) x);
        buffer.putFloat((float) y);
        buffer.putFloat((float) z);
        buffer.putFloat(0.0F);
    }

    private static void putVec4(ByteBuffer buffer, float x, float y, float z, float w) {
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(z);
        buffer.putFloat(w);
    }

    private static void putPaddingFloats(ByteBuffer buffer, int count) {
        for (int i = 0; i < count; i++) buffer.putFloat(0.0F);
    }

    private static void putStarPadding(ByteBuffer buffer) {
        putPaddingFloats(buffer, STAR_STRUCT_FLOATS);
    }

    private static void putPlanetPadding(ByteBuffer buffer) {
        putPaddingFloats(buffer, PLANET_STRUCT_FLOATS);
    }

    private static void putBlackHolePadding(ByteBuffer buffer) {
        putPaddingFloats(buffer, BLACKHOLE_STRUCT_FLOATS);
    }

    /** 大气参数照抄 space mod solar_system/object/*.json，渲染壳厚度与行星渲染对象一致。 */
    private record AtmoData(float gravity, double renderHeight, double realHeight,
                            float temperature, float molarMass, float seaLevelDensity,
                            float[] color) {
        static AtmoData forBody(RealAstroData body, PlanetRenderObject object) {
            float[] color = object.visual().atmosphereColor() != null
                    ? object.visual().atmosphereColor()
                    : new float[]{0.0F, 0.0F, 0.0F};
            if (body == null || !object.hasAtmosphere()) {
                return new AtmoData(0.0F, 0.0, 0.0, 0.0F, 0.0F, 0.0F, color);
            }
            double renderHeight = Math.max(0.0, object.atmosphereRadius() - object.radius());
            double realHeight = body.atmosphereHeightMeters();
            return switch (body.id()) {
                case "venus" -> new AtmoData(8.87F, renderHeight, realHeight, 737.0F, 0.04345F, 65.0F, color);
                case "earth" -> new AtmoData(9.807F, renderHeight, realHeight, 288.0F, 0.02896F, 1.225F, color);
                case "mars" -> new AtmoData(3.72F, renderHeight, realHeight, 210.0F, 0.04334F, 0.020F, color);
                case "jupiter" -> new AtmoData(24.79F, renderHeight, realHeight, 165.0F, 0.00222F, 0.16F, color);
                case "saturn" -> new AtmoData(10.44F, renderHeight, realHeight, 134.0F, 0.00207F, 0.19F, color);
                case "uranus" -> new AtmoData(8.69F, renderHeight, realHeight, 76.0F, 0.00264F, 0.42F, color);
                case "neptune" -> new AtmoData(11.15F, renderHeight, realHeight, 72.0F, 0.00253F, 0.45F, color);
                default -> new AtmoData(0.0F, renderHeight, realHeight, 0.0F, 0.0F, 0.0F, color);
            };
        }
    }
}
