package com.mss.polymech.client.space;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mss.polymech.Polymech;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.client.gui.widget.planet.SkyboxRenderer;
import com.mss.polymech.client.gui.widget.planet.StarSystemCatalog;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * 太空维度特效：把我们的 cubemap 星空作为「维度天空盒」接入 MC 原生天空渲染管线。
 *
 * <p>通过 NeoForge 的 {@code IDimensionSpecialEffectsExtension.renderSky} 钩子，
 * 在 MC 画天空的阶段直接渲染我们的星空 cubemap 并返回 true，跳过 vanilla 天空。
 * 这样天空盒走 MC 标准维度特效管线（材质包/资源包可替换贴图），不再手画。</p>
 */
public class SpaceDimensionEffects extends DimensionSpecialEffects {

    /** 调试：天空盒六面各一色，用于确认水平/垂直移动是否走直线。 */
    private static final boolean DEBUG_SOLID_SKY = false;

    /** true = 天空盒交给 SpaceRenderer 与星球统一渲染（同 view/坐标系），本特效不再画天空盒。 */
    private static final boolean DEFER_SKYBOX_TO_RENDERER = false;

    public SpaceDimensionEffects() {
        // ⚠️ 必须是 NORMAL 而不是 NONE（2026-09-25 用户拍板）：
        //   NONE 让原版 `LevelRenderer.renderSky` 既不进 END 分支也不进 NORMAL 分支 ⇒
        //   天幕渐变、日落红染、星空**全部**没有，地表天空只剩一个平坦的雾色 —— 用户的评价是
        //   "天空太干净了"。NORMAL 把原版天空（含渐变与星空）放回来，我们只**单独掐掉原版日月**
        //   （见 mixin/LevelRendererCelestialSkyMixin），这样天上就只有我们自己的真实天体。
        //   太空维度不受影响：本类的 renderSky 在太空维度返回 true，直接跳过原版天空。
        super(192.0f, false, SkyType.NORMAL, false, false);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 color, float sunHeight) {
        // 太空无雾，返回原色
        return color;
    }

    @Override
    public boolean isFoggyAt(int x, int z) {
        return false;
    }

    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick,
                                PoseStack poseStack, double camX, double camY, double camZ,
                                Matrix4f projectionMatrix, Matrix4f modelViewMatrix) {
        // 太空没有云层：返回 true 跳过 vanilla 云渲染
        return true;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick,
                                     net.minecraft.client.renderer.LightTexture lightTexture,
                                     double camX, double camY, double camZ) {
        // 太空没有雨雪：返回 true 跳过
        return true;
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        // 太空没有降雨/雪粒子的 tick
        return true;
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick,
                             Matrix4f poseStack, Camera camera, Matrix4f projectionMatrix,
                             boolean isFoggy, Runnable fogSetter) {
        // 只对太空维度生效（本特效也只会被太空维度用到，双保险）
        if (!level.dimension().equals(PlanetDimensions.SPACE)) {
            return false;
        }

        // 天空盒交给 SpaceRenderer 与星球统一渲染：这里只返回 true 跳过 vanilla 天空，
        // 不绘制任何内容（背景由 SpaceRenderer 的天空盒覆盖）。
        if (DEFER_SKYBOX_TO_RENDERER) {
            return true;
        }

        // 自建天空投影（与 SpaceRenderer 原 skybox 一致：near=0.05, far=2000），
        // 确保 r=1000 的 cubemap 立方体完整在视锥内，不会因 far 太近被裁剪成异常平面。
        var window = net.minecraft.client.Minecraft.getInstance().getWindow();
        float aspect = (float) window.getWidth() / (float) window.getHeight();
        Matrix4f skyProj = new Matrix4f().perspective(
                (float) Math.toRadians(70.0), aspect, 0.05f, 2000.0f);
        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(skyProj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.set(poseStack);
        RenderSystem.applyModelViewMatrix();
        // 关键修复：addVertex(mat,...) 会把 mat 预乘进顶点，而 drawWithShader 又用
        // RenderSystem.getModelViewMatrix()（= poseStack）作为 shader 的 ModelViewMat。
        // 若 mat 也传 poseStack，view 会被应用两次（天空盒以 2 倍角速度旋转，
        // 相对星球产生"可逆的独立旋转"）。此处必须传单位矩阵，view 只应用一次。
        Matrix4f identityMat = new Matrix4f();
        try {
            if (DEBUG_SOLID_SKY) {
                // 六面各一色：front(+Z)绿, back(-Z)黄, left(-X)品红, right(+X)青, top(+Y)红, bottom(-Y)蓝
                SkyboxRenderer.drawCubemapSolid(identityMat, new int[]{
                        0xFF00FF00, // front +Z 绿
                        0xFFFFFF00, // back -Z 黄
                        0xFFFF00FF, // left -X 品红
                        0xFF00FFFF, // right +X 青
                        0xFFFF0000, // top +Y 红
                        0xFF0000FF  // bottom -Y 蓝
                });
            } else {
                SkyboxRenderer.drawCubemap(identityMat, currentSkyboxTexture());
            }
        } finally {
            mvs.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProj, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN);
        }
        // 返回 true：告诉 MC 已自定义渲染天空，跳过 vanilla
        return true;
    }

    /** 根据星图种子选择一张 cubemap 星空贴图（资源包可替换）。 */
    private static ResourceLocation currentSkyboxTexture() {
        long seed = StarSystemCatalog.get(0).seed;
        int idx = 1 + (int) ((seed >>> 16) % 11L);
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID,
                "textures/gui/skybox/cubemap/cubemap_space" + idx + ".png");
    }
}
