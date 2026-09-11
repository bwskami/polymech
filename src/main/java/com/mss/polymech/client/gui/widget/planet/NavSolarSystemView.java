package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.techtree.TechNode;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;

import java.util.function.Consumer;
import com.mss.polymech.space.SpaceWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;


import java.util.HashMap;
import java.util.Map;

/**
 * 带玩家位置标记的太阳系视图（宇宙导航星图）。
 * <p>
 * 星图使用风格化轨道模型（半径压缩、时间加速），因此标记定位采用
 * <b>最近天体锚点法</b>：找出玩家真实天文坐标最近的天体，
 * 把"玩家−该天体"的真实偏移按<b>该天体自己的地图比例</b>缩放后，
 * 叠加到它当前（动画）的地图位置上。玩家停在某星球附近时，
 * 标记始终贴合该星球的地图位置。
 * </p>
 */
public class NavSolarSystemView extends SolarSystemView {

    private final Map<String, Integer> indexByName = new HashMap<>();

    public NavSolarSystemView(SolarSystem system, Consumer<TechNode> onSelect) {
        super(system, onSelect);
        for (int i = 0; i < solarSystem.size(); i++) {
            indexByName.put(solarSystem.get(i).name(), i);
        }
    }

    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        super.drawBackgroundAdditional(guiContext);
        drawPlayerMarker(guiContext);
    }

    /** 绿色菱形标记：玩家当前真实位置（仅太空维度显示）。 */
    private void drawPlayerMarker(GUIContext guiContext) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!com.mss.polymech.dimension.PlanetDimensions.SPACE.equals(mc.level.dimension())) return;

        Vec3 p = mc.player.position();
        double ax = SpaceWorld.toReal(p.x);
        double ay = SpaceWorld.toReal(p.y);
        double az = SpaceWorld.toReal(p.z);

        // 最近天体锚点（含太阳/月球）——用游戏宇宙坐标（保向压缩），与玩家实际所处空间一致
        double[][] gps = new double[RealAstroData.BODIES.size()][];
        int nearest = -1;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < RealAstroData.BODIES.size(); i++) {
            gps[i] = SpaceWorld.gamePos(RealAstroData.BODIES.get(i));
            double dx = ax - gps[i][0], dy = ay - gps[i][1], dz = az - gps[i][2];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < best) { best = d2; nearest = i; }
        }
        if (nearest < 0) return;
        RealAstroData nb = RealAstroData.BODIES.get(nearest);
        Integer mi = indexByName.get(nb.name());
        if (mi == null) return;

        // 该天体的地图位置（动画中）与地图比例
        float[] mapPos = solarSystem.worldPosTo(new float[3], mi, simTime);
        double realR = Math.sqrt(gps[nearest][0] * gps[nearest][0] + gps[nearest][2] * gps[nearest][2]);
        double mapR = solarSystem.get(mi).orbitalRadius();
        double scale = (realR > 1e9 && mapR > 0.01f)
                ? mapR / realR
                : 35.0 / 1.477e11; // 太阳等静态天体：退回地球基准比例

        // 游戏宇宙偏移 → 地图偏移
        double ox = (ax - gps[nearest][0]) * scale;
        double oy = (ay - gps[nearest][1]) * scale;
        double oz = (az - gps[nearest][2]) * scale;
        float wx = (float) (mapPos[0] + ox);
        float wy = (float) (mapPos[1] + oy);
        float wz = (float) (mapPos[2] + oz);

        // ===== 世界(地图系) → 相机系（与 OrbitalDrawer 同一变换） =====
        float cosY = camera.cosY(), sinY = camera.sinY(), cosX = camera.cosX(), sinX = camera.sinX();
        float dwx = wx - camera.focalX(), dwz = wz - camera.focalZ();
        float rx = dwx * cosY + dwz * sinY;
        float rz1 = -dwx * sinY + dwz * cosY;
        float wyRel = wy - camera.focalY();
        float ry2 = wyRel * cosX - rz1 * sinX;
        float rz = wyRel * sinX + rz1 * cosX;
        float pzc = rz - camera.dist();
        if (pzc > -0.05f) return; // 在相机后方

        // 恒定屏幕尺寸：半宽 ≈ 屏幕高度的 1%
        float half = -pzc * 0.010f;

        GuiGraphics g = guiContext.graphics;
        int vw = (int) getSizeWidth(), vh = (int) getSizeHeight();
        float fov = 2f * (float) Math.atan((vh / 2f) / camera.focalLength());
        Matrix4f proj = new Matrix4f().perspective(fov, (float) vw / vh, 0.01f, 3000f);

        var mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix(); mvs.identity(); RenderSystem.applyModelViewMatrix();
        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        // 菱形（两个三角形），微微抬离轨道面避免与轨道线 z 冲突
        float z = pzc + half * 0.02f;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float topY = ry2 + half, botY = ry2 - half, lX = rx - half, rX = rx + half;
        bb.addVertex(idMat, rx, topY, z).setColor(0.25f, 1.0f, 0.45f, 0.95f);
        bb.addVertex(idMat, lX, ry2, z).setColor(0.25f, 1.0f, 0.45f, 0.95f);
        bb.addVertex(idMat, rX, ry2, z).setColor(0.25f, 1.0f, 0.45f, 0.95f);
        bb.addVertex(idMat, rx, topY, z).setColor(0.25f, 1.0f, 0.45f, 0.95f);
        bb.addVertex(idMat, rX, ry2, z).setColor(0.25f, 1.0f, 0.45f, 0.95f);
        bb.addVertex(idMat, rx, botY, z).setColor(0.25f, 1.0f, 0.45f, 0.95f);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.setProjectionMatrix(oldProj, VertexSorting.DISTANCE_TO_ORIGIN);
        mvs.popMatrix(); RenderSystem.applyModelViewMatrix();
    }
}
