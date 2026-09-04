package com.mss.polymech.client.space;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mss.polymech.client.gui.widget.planet.PlanetLighting;
import com.mss.polymech.client.gui.widget.planet.SkyboxRenderer;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderObject;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderObjectFactory;
import com.mss.polymech.client.gui.widget.planet.PlanetRenderParams;
import com.mss.polymech.client.gui.widget.planet.StarGlowRenderer;
import com.mss.polymech.client.gui.widget.planet.StarSystemCatalog;
import net.minecraft.client.renderer.GameRenderer;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SpaceRenderer {

    private static final float SPACE_FAR_PLANE = 1.0e13f;
    private static final float SPACE_NEAR_PLANE = 1000.0f;
    private static final float SKY_FAR_PLANE = 2000.0f;
    private static final float SKY_NEAR_PLANE = 0.05f;
    private static final float FOV_DEG = 70.0f;
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Vector3f TMP_REL = new Vector3f();
    private static final Vector3f TMP_CAM = new Vector3f();

    /** AFTER_SKY 阶段绘制的星球数据，供 AFTER_PARTICLES 阶段的后处理（泛光 / 大气）使用。 */
    private static Matrix4f spaceView;
    private static Matrix4f spaceProj;
    private static List<PlanetRenderObject> spaceBodies = List.of();
    private static double spaceCamRealX;
    private static double spaceCamRealY;
    private static double spaceCamRealZ;
    private static float spacePartialTick;
    private static boolean spaceFrameDrawn;
    /** 本帧画面里有没有恒星：没有时跳过泛光 pass，省一次全屏绘制。 */
    private static boolean spaceHasStars;
    /**
     * 后处理用的深度快照（AFTER_PARTICLES 时的主深度）。
     *
     * <p>{@link net.minecraft.client.renderer.PostPass#process} 写回主缓冲时会调
     * {@code RenderTarget.clear}，无条件清颜色、useDepth 时还清深度。泛光与大气两条链路
     * 都要 blit 回主缓冲，先跑的那条会把主深度抹掉；云/天气也需要主深度。
     * 所以开跑前先快照一份，结束后再写回主缓冲。</p>
     *
     * <p>大气散射拿它反解距离；恒星泛光只拿它与 {@link #spaceSkyDepthSnapshot} 比对，
     * 判断像素是不是 MC 几何体，不用它算距离（见 {@link SpaceStarBloomRenderer} 类注释）。</p>
     */
    private static RenderTarget spaceDepthSnapshot;

    /**
     * AFTER_SKY 星球层画完、MC 地形还没开始画时的主深度副本。
     *
     * <p>泛光需要区分「星球」和「MC 几何体」，但 AFTER_PARTICLES 时主深度是混合投影的：
     * 星球用 spaceProj（near=1000m / far=1e13m）写入，地形/实体/粒子随后用 MC 自己的投影写入，
     * 两者深度区间互相重叠（地球 8.88e6m → 0.9998874，443m 处的方块 → 0.9998871），
     * 单看一个值分不出来源。留下这份底之后逐像素比较就够了：
     * {@code depthNow < skyDepth} 当且仅当该像素有 MC 几何体通过深度测试、真正画了上去。</p>
     *
     * <p>space mod 不需要这一步，因为它让两套投影共用同一对 near/far
     * （mixin 改 getDepthFar 为 FarCompress*4，太空投影取 setPerspective(4194304, 0.05)），
     * 于是 {@code max(1 - mainDepth, spaceDepth)} 天然就是「更近的那个表面」。
     * 本项目没这套约定，只能用双快照换。</p>
     */
    private static RenderTarget spaceSkyDepthSnapshot;
    /** {@link #spaceSkyDepthSnapshot} 的深度纹理 id，每帧在 AFTER_SKY 刷新；本帧没有恒星时为 0。 */
    private static int spaceSkyDepthTexture;

    private SpaceRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!mc.level.dimension().equals(PlanetDimensions.SPACE)) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            renderSpaceBodies(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            renderSpacePostEffects();
        }
    }

    /** AFTER_SKY：绘制天空盒 + 星球本体/云层/光环/日冕，并记录本帧数据供大气后处理。 */
    private static void renderSpaceBodies(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        var camPos = camera.getPosition();
        var cameraRot = new org.joml.Quaternionf(camera.rotation()).conjugate();
        Matrix4f view = new Matrix4f().rotation(cameraRot);
        float aspect = (float) mc.getWindow().getWidth() / (float) mc.getWindow().getHeight();
        Matrix4f skyProj = new Matrix4f().perspective((float) Math.toRadians(FOV_DEG), aspect, SKY_NEAR_PLANE, SKY_FAR_PLANE);
        Matrix4f spaceProj = new Matrix4f().perspective((float) Math.toRadians(FOV_DEG), aspect, SPACE_NEAR_PLANE, SPACE_FAR_PLANE);

        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.set(view);
        RenderSystem.applyModelViewMatrix();

        try {
            RenderSystem.setProjectionMatrix(skyProj, VertexSorting.DISTANCE_TO_ORIGIN);
            drawSkybox();

            RenderSystem.setProjectionMatrix(spaceProj, VertexSorting.DISTANCE_TO_ORIGIN);
            // 星球 BASE 层不透明：确认深度测试/写入开启（天空盒绘制后依赖它恢复，这里显式兜底）。
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.clearDepth(1.0f);
            RenderSystem.clear(0x100, false);
            double seconds = SpaceWorld.j2000Seconds();
            double camRealX = SpaceWorld.toReal(camPos.x);
            double camRealY = SpaceWorld.toReal(camPos.y);
            double camRealZ = SpaceWorld.toReal(camPos.z);

            // 按相机距离从远到近排序：不透明 BASE 可减少 overdraw，
            // 半透明 CLOUD/RING/日冕按正确顺序混合。
            List<PlanetRenderObject> bodies = new ArrayList<>(PlanetRenderObjectFactory.bodies());
            bodies.sort(Comparator.comparingDouble(
                    (PlanetRenderObject o) -> bodyDistanceSq(o, camRealX, camRealY, camRealZ)).reversed());
            float partialTick = event.getPartialTick().getGameTimeDeltaTicks();
            double simTime = seconds;

            // 每颗行星独立计算“该行星指向太阳”的平行光。
            // 太阳位于原点，若仍用相机位置算全局光向，远处行星的晨昏线会明显错误。
            // 每帧只算一次，BASE/CLOUD/ATMO/RING/日冕各 pass 共用同一组光照参数。
            List<PlanetRenderParams> bodyParams = new ArrayList<>(bodies.size());
            for (PlanetRenderObject obj : bodies) {
                bodyParams.add(paramsForBody(view, spaceProj, obj,
                        camRealX, camRealY, camRealZ, partialTick, simTime));
            }

            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).render(bodyParams.get(i));
            }

            // CLOUD 半透明层：与 GUI 星图一致，先画云，再让大气壳覆盖在云上。
            RenderSystem.depthMask(false);
            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).renderClouds(bodyParams.get(i));
            }

            // 大气壳（GPU rim）：太阳与带大气的行星都走这里；屏幕空间散射负责更柔和的外晕。
            RenderSystem.depthMask(false);
            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).renderAtmosphere(bodyParams.get(i));
            }

            // RING 半透明 pass：与 GUI 星图一致（不写深度）。
            RenderSystem.depthMask(false);
            for (int i = 0; i < bodies.size(); i++) {
                bodies.get(i).renderRings(bodyParams.get(i));
            }

            // 恒星 3D 日冕光晕：depthMask(false) 且深度测试开启，可被前景行星正确遮挡。
            drawSunGlows(view, bodies, camRealX, camRealY, camRealZ);

            // 记录本帧数据，AFTER_PARTICLES 阶段再做屏幕空间泛光 + 大气散射。
            // 这样后处理不会在 AFTER_SKY 阶段清空主深度缓冲，避免破坏后续地形的深度遮挡。
            boolean hasStars = false;
            for (PlanetRenderObject obj : bodies) {
                if (obj.visual().isGlowing()) {
                    hasStars = true;
                    break;
                }
            }
            spaceView = view;
            SpaceRenderer.spaceProj = spaceProj;
            spaceBodies = bodies;
            spaceCamRealX = camRealX;
            spaceCamRealY = camRealY;
            spaceCamRealZ = camRealZ;
            spacePartialTick = partialTick;
            spaceFrameDrawn = true;
            spaceHasStars = hasStars;

            // 星球层到此为止、MC 地形还没画 —— 这是留深度底唯一可行的时机。
            // 没有恒星就不留：泛光是这份底唯一的消费者，能省掉一次全屏 blit。
            spaceSkyDepthTexture = hasStars ? captureSkyDepth(mc.getMainRenderTarget()) : 0;

        } finally {
            mvs.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProj, VertexSorting.DISTANCE_TO_ORIGIN);
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    /**
     * AFTER_PARTICLES：在 MC 地形/实体/粒子之后叠加屏幕空间特效，匹配 space mod 的原有时序。
     *
     * <p>顺序与 space mod 的 {@code RenderEffectAfter} 一致：先恒星泛光，再行星大气散射。
     * 天体数据只上传一次，两条链路共用同一个 UBO。</p>
     */
    private static void renderSpacePostEffects() {
        if (!spaceFrameDrawn) return;
        if (spaceBodies == null || spaceView == null || spaceProj == null) return;
        RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
        try {
            CelestialBodyDataBuffer.get().update(spaceBodies, spaceCamRealX, spaceCamRealY, spaceCamRealZ);
            int depthTexture = snapshotDepth(mainTarget);
            try {
                if (spaceHasStars && spaceSkyDepthTexture != 0) {
                    SpaceStarBloomRenderer.get().render(spaceView, spaceProj, spacePartialTick,
                            depthTexture, spaceSkyDepthTexture);
                }
                SpaceAtmosphereRenderer.get().render(spaceView, spaceProj, spacePartialTick, depthTexture);
            } finally {
                restoreDepth(mainTarget);
            }
        } finally {
            spaceFrameDrawn = false;
            // 深度纹理 id 只在本帧有效，清掉以免下一帧误用陈值。
            spaceSkyDepthTexture = 0;
        }
    }

    /**
     * 在星球层画完、MC 地形还没开始画时把主深度复制一份作为「底」，返回其深度纹理 id。
     *
     * @see #spaceSkyDepthSnapshot
     */
    private static int captureSkyDepth(RenderTarget mainTarget) {
        if (spaceSkyDepthSnapshot == null) {
            spaceSkyDepthSnapshot = new TextureTarget(mainTarget.width, mainTarget.height, true, false);
            // 同 snapshotDepth：两边深度附件格式不一致时 glBlitFramebuffer 会静默失败。
            if (mainTarget.isStencilEnabled()) {
                spaceSkyDepthSnapshot.enableStencil();
            }
        } else if (spaceSkyDepthSnapshot.width != mainTarget.width || spaceSkyDepthSnapshot.height != mainTarget.height) {
            spaceSkyDepthSnapshot.resize(mainTarget.width, mainTarget.height, false);
        }
        // blit 走的是拷贝路径而不是片元管线，理论上不受 depthMask 影响；
        // 这里显式置 true 只是为了排除驱动差异（drawSunGlows 的 finally 已经把它恢复成 true）。
        RenderSystem.depthMask(true);
        spaceSkyDepthSnapshot.copyDepthFrom(mainTarget);
        // copyDepthFrom 会把 GL_FRAMEBUFFER 解绑到默认窗口缓冲。这里必须绑回主缓冲，
        // 否则紧接着的地形会画到窗口上而不是主 FBO。
        mainTarget.bindWrite(false);
        return spaceSkyDepthSnapshot.getDepthTextureId();
    }

    /** 把主缓冲深度 blit 到快照缓冲，返回快照的深度纹理 id。 */
    private static int snapshotDepth(RenderTarget mainTarget) {
        if (spaceDepthSnapshot == null) {
            spaceDepthSnapshot = new TextureTarget(mainTarget.width, mainTarget.height, true, false);
            // 与 PostChain.addTempTarget 一致：主缓冲开了 stencil 就必须跟着开。
            // 两边的深度附件格式不一致时 glBlitFramebuffer 会静默失败（GL_INVALID_OPERATION），
            // 快照里永远是创建时清出来的 1.0，大气散射就会把整屏当成无穷远天空。
            if (mainTarget.isStencilEnabled()) {
                spaceDepthSnapshot.enableStencil();
            }
        } else if (spaceDepthSnapshot.width != mainTarget.width || spaceDepthSnapshot.height != mainTarget.height) {
            spaceDepthSnapshot.resize(mainTarget.width, mainTarget.height, false);
        }
        spaceDepthSnapshot.copyDepthFrom(mainTarget);
        // copyDepthFrom 会把 GL_FRAMEBUFFER 解绑到默认窗口缓冲，这里必须绑回主缓冲。
        mainTarget.bindWrite(false);
        return spaceDepthSnapshot.getDepthTextureId();
    }

    /** 把快照深度写回主缓冲，保证后续云/天气仍有正确的深度遮挡。 */
    private static void restoreDepth(RenderTarget mainTarget) {
        if (spaceDepthSnapshot == null) return;
        mainTarget.copyDepthFrom(spaceDepthSnapshot);
        mainTarget.bindWrite(false);
    }

    /** GUI 阶段暂不绘制额外元素，保持太空画面干净；后续可在此挂接太空 HUD。 */
    public static void onRenderGui(RenderGuiEvent.Post event) {
    }

    private static PlanetRenderParams paramsForBody(Matrix4f view, Matrix4f proj,
                                                     PlanetRenderObject obj,
                                                     double camRealX, double camRealY, double camRealZ,
                                                     float partialTick, double simTime) {
        PlanetLighting lighting = new PlanetLighting();
        double ldx = -obj.posX();
        double ldy = -obj.posY();
        double ldz = -obj.posZ();
        double llen = Math.sqrt(ldx * ldx + ldy * ldy + ldz * ldz);
        if (llen < 1e-5) {
            ldx = 0;
            ldy = 1;
            ldz = 0;
        } else {
            ldx /= llen;
            ldy /= llen;
            ldz /= llen;
        }
        lighting.updateGlobal((float) ldx, (float) ldy, (float) ldz, 1.0f);
        return new PlanetRenderParams(view, proj, camRealX, camRealY, camRealZ,
                partialTick, simTime, lighting, null);
    }

    private static double bodyDistanceSq(PlanetRenderObject body,
                                         double camX, double camY, double camZ) {
        double dx = body.posX() - camX;
        double dy = body.posY() - camY;
        double dz = body.posZ() - camZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 恒星 3D 日冕：相机空间公告板，与 GUI 星图共用 {@link StarGlowRenderer}。 */
    private static void drawSunGlows(Matrix4f view, List<PlanetRenderObject> bodies,
                                     double camRealX, double camRealY, double camRealZ) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();

        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
        try {
            for (int i = 0; i < bodies.size(); i++) {
                PlanetRenderObject body = bodies.get(i);
                if (!body.visual().isGlowing()) continue;
                float[] base = body.visual().baseColor();
                if (base == null) continue;
                TMP_REL.set((float) (body.posX() - camRealX),
                        (float) (body.posY() - camRealY),
                        (float) (body.posZ() - camRealZ));
                view.transformPosition(TMP_REL, TMP_CAM);
                // 相机空间 -Z 为前方，日冕中心在相机后方或极近处时跳过。
                if (TMP_CAM.z > -(float) body.radius()) continue;
                float baseR = (float) body.radius();
                float gr = base[0] * 0.65f + 0.35f;
                float gg = base[1] * 0.65f + 0.35f;
                float gb = base[2] * 0.65f + 0.35f;
                StarGlowRenderer.drawGlowHalo(IDENTITY,
                        TMP_CAM.x, TMP_CAM.y, TMP_CAM.z,
                        baseR * 1.00f, baseR * 2.40f, 0.35f,
                        gr, gg, gb);
            }
        } finally {
            mvs.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            RenderSystem.disableBlend();
        }
    }

    private static ResourceLocation currentSkyboxTexture() {
        long seed = StarSystemCatalog.get(0).seed;
        int idx = 1 + (int) ((seed >>> 16) % 11L);
        return ResourceLocation.fromNamespaceAndPath("poly_mech",
                "textures/gui/skybox/cubemap/cubemap_space" + idx + ".png");
    }

    private static void drawSkybox() {
        SkyboxRenderer.drawCubemap(RenderSystem.getModelViewMatrix(), currentSkyboxTexture());
    }
}
