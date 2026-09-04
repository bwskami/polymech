package com.mss.polymech.client.space;

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

    /** AFTER_SKY 阶段绘制的星球数据，供 AFTER_PARTICLES 阶段的大气后处理使用（参考 space mod 的渲染节奏）。 */
    private static Matrix4f spaceView;
    private static Matrix4f spaceProj;
    private static List<PlanetRenderObject> spaceBodies = List.of();
    private static double spaceCamRealX;
    private static double spaceCamRealY;
    private static double spaceCamRealZ;
    private static float spacePartialTick;
    private static boolean spaceFrameDrawn;

    private SpaceRenderer() {
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!mc.level.dimension().equals(PlanetDimensions.SPACE)) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) {
            renderSpaceBodies(event);
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            renderSpaceAtmosphere();
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

            // 记录本帧数据，AFTER_PARTICLES 阶段再做屏幕空间大气散射。
            // 这样大气后处理不会在 AFTER_SKY 阶段清空主深度缓冲，避免破坏后续地形的深度遮挡。
            spaceView = view;
            SpaceRenderer.spaceProj = spaceProj;
            spaceBodies = bodies;
            spaceCamRealX = camRealX;
            spaceCamRealY = camRealY;
            spaceCamRealZ = camRealZ;
            spacePartialTick = partialTick;
            spaceFrameDrawn = true;

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

    /** AFTER_PARTICLES：在 MC 地形/实体/粒子之后叠加屏幕空间大气散射，匹配 space mod 的原有时序。 */
    private static void renderSpaceAtmosphere() {
        if (!spaceFrameDrawn) return;
        if (spaceBodies == null || spaceView == null || spaceProj == null) return;
        SpaceAtmosphereRenderer.get().render(spaceView, spaceProj, spaceBodies,
                spaceCamRealX, spaceCamRealY, spaceCamRealZ, spacePartialTick);
        spaceFrameDrawn = false;
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
