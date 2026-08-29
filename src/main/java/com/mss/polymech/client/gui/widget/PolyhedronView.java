package com.mss.polymech.client.gui.widget;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;
import com.mss.polymech.client.gui.widget.planet.Planet;
import com.mss.polymech.client.gui.widget.planet.PlanetLayer;
import com.mss.polymech.client.gui.widget.planet.PlanetLayerType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 科技树 3D 可视化（Mindustry 式，正规 3D 模型渲染）：
 * <ul>
 *   <li>内核：六边形/五边形色块拼成的低多边形星球。</li>
 *   <li>大气层：向外膨胀一层的半透明蓝色球壳。</li>
 *   <li>最外层棱框：稍大的球体，默认显示全部多边形棱。</li>
 *   <li>所有几何作为“3D 模型”一次性交给 GPU，用真实透视投影 + 深度缓冲自动遮挡；
 *       不手动跳面/切正背面。</li>
 * </ul>
 */
public class PolyhedronView extends UIElement {

    private final Planet planet;
    private final float planetR;
    private final float cloudR;
    private final float atmosR;
    private final float wireR;
    private final float techR;

    private static final float DEFAULT_PLANET_R = 0.96f;
    private static final float DEFAULT_CLOUD_R = 1.00f;
    private static final float DEFAULT_ATMOS_R = 1.02f;
    private static final float DEFAULT_TECH_R = 1.035f;
    private static final float DEFAULT_WIRE_R = 1.05f;
    private static final float EDGE_HW = 0.005f;  // 相机空间宽度（投影后自然近粗远细）
    private static final long PLANET_SEED = 0x5EED1234L;
    private static final float LX = -0.398f, LY = 0.696f, LZ = 0.597f, AMB = 0.30f;

    private final Polyhedron shell;
    private final Polyhedron atmos;
    private final List<TechNode> nodes;
    private final Consumer<TechNode> onSelect;
    private final float[][] faceAlbedo;
    private final Noise3 noise;

    private float yaw = 0.6f;
    private float pitch = 0.35f;
    private float dist = 3.2f;
    private boolean dragging = false;
    private int lastMX = 0, lastMY = 0;
    private long lastNano = System.nanoTime();

    // 每图层独立自转偏移（相对星球默认自转）
    private float cloudSpin, atmosSpin, techSpin, wireSpin;
    private float cloudYaw, atmosYaw, techYaw, wireYaw;

    private int hoveredTile = -1;
    private int selectedTile = -1;
    private static final float CHASE_TAU = 0.10f;
    private float hoverAlpha;
    private long hoverStartNano;
    private long lastHighlightNano = System.nanoTime();
    private int chaseFace = -1;
    private final float[] chaseWx = new float[6], chaseWy = new float[6], chaseWz = new float[6];
    private float chaseWMx, chaseWMy, chaseWMz;
    private boolean chaseActive;
    private int chaseFaceVerts;

    // 拾取用：星球屏幕坐标 + 世界z（rz，>0 表示正面可见）
    private final float[] tx, ty, tnz;
    // 模型相机空间坐标（x, y, z-relative，渲染用）
    private final float[] pcx, pcy, pcz; // shell @ planetR
    private final float[] wcx, wcy, wcz; // shell @ wireR
    private final float[] acx, acy, acz; // atmos @ atmosR

    public PolyhedronView(Polyhedron shell, Polyhedron atmos, List<TechNode> nodes, Consumer<TechNode> onSelect) {
        this(Planet.of("星球", shell, 0.25f,
                PlanetLayer.of(PlanetLayerType.BASE, DEFAULT_PLANET_R),
                PlanetLayer.of(PlanetLayerType.CLOUD, DEFAULT_CLOUD_R),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, DEFAULT_ATMOS_R, atmos),
                PlanetLayer.of(PlanetLayerType.TECH, DEFAULT_TECH_R),
                PlanetLayer.of(PlanetLayerType.WIREFRAME, DEFAULT_WIRE_R)
        ).build(), nodes, onSelect);
    }

    public PolyhedronView(Planet planet, List<TechNode> nodes, Consumer<TechNode> onSelect) {
        this.planet = planet;
        this.shell = planet.baseMesh();
        this.atmos = planet.layer(PlanetLayerType.ATMOSPHERE)
                .map(planet::resolveGeometry)
                .orElse(Polyhedron.sphere(24, 32));
        this.nodes = nodes;
        this.onSelect = onSelect;
        this.noise = new Noise3(PLANET_SEED);

        this.planetR = planet.layer(PlanetLayerType.BASE).map(PlanetLayer::radius).orElse(DEFAULT_PLANET_R);
        this.cloudR = planet.layer(PlanetLayerType.CLOUD).map(PlanetLayer::radius).orElse(DEFAULT_CLOUD_R);
        this.atmosR = planet.layer(PlanetLayerType.ATMOSPHERE).map(PlanetLayer::radius).orElse(DEFAULT_ATMOS_R);
        this.techR = planet.layer(PlanetLayerType.TECH).map(PlanetLayer::radius).orElse(DEFAULT_TECH_R);
        this.wireR = planet.layer(PlanetLayerType.WIREFRAME).map(PlanetLayer::radius).orElse(DEFAULT_WIRE_R);

        int sv = shell.vertices.length;
        this.tx = new float[sv]; this.ty = new float[sv]; this.tnz = new float[sv];
        this.pcx = new float[sv]; this.pcy = new float[sv]; this.pcz = new float[sv];
        this.wcx = new float[sv]; this.wcy = new float[sv]; this.wcz = new float[sv];
        int av = atmos.vertices.length;
        this.acx = new float[av]; this.acy = new float[av]; this.acz = new float[av];

        this.faceAlbedo = new float[shell.faces.length][3];
        precomputeFaces();

        addEventListener(UIEvents.MOUSE_DOWN, e -> {
            dragging = true;
            lastMX = (int) e.x; lastMY = (int) e.y;
        });
        addEventListener(UIEvents.MOUSE_UP, e -> dragging = false);
        addEventListener(UIEvents.MOUSE_MOVE, e -> {
            if (dragging) {
                int mx = (int) e.x, my = (int) e.y;
                yaw += (mx - lastMX) * 0.01f;
                pitch += (my - lastMY) * 0.01f;
                pitch = clamp(pitch, -1.4f, 1.4f);
                lastMX = mx; lastMY = my;
            }
        });
        addEventListener(UIEvents.MOUSE_WHEEL, e -> {
            if (e.deltaY > 0) dist *= 0.9f;
            else if (e.deltaY < 0) dist *= 1.1f;
            dist = clamp(dist, 1.6f, 8f);
        });
        addEventListener(UIEvents.CLICK, e -> {
            if (hoveredTile >= 0 && hoveredTile < nodes.size()) {
                selectedTile = hoveredTile;
                onSelect.accept(nodes.get(hoveredTile));
            }
        });
        addEventListener(UIEvents.HOVER_TOOLTIPS, e -> {
            if (hoveredTile >= 0 && hoveredTile < nodes.size()) {
                List<Component> tips = new ArrayList<>();
                tips.add(nodes.get(hoveredTile).title());
                e.hoverTooltips = new HoverTooltips(tips, null, null, null);
            }
        });
    }

    /** 每个地块面基础色：科技块用 tier 色，其余用程序化地形色。 */
    private void precomputeFaces() {
        for (int f = 0; f < shell.faces.length; f++) {
            int[] fv = shell.faces[f];
            float cx = 0, cy = 0, cz = 0;
            for (int v : fv) {
                cx += shell.vertices[v][0]; cy += shell.vertices[v][1]; cz += shell.vertices[v][2];
            }
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            cx /= len; cy /= len; cz /= len;

            float n = noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f);
            n = clamp(n / 0.94f, 0, 1);
            float lat = Math.abs(cy);
            float r, g, b;
            if (n < 0.5f) {
                float t = n / 0.5f;
                r = 0.05f + 0.04f * t; g = 0.16f + 0.30f * t; b = 0.40f + 0.40f * t;
            } else {
                float t = (n - 0.5f) / 0.5f;
                r = 0.24f + 0.42f * t; g = 0.45f - 0.10f * t; b = 0.18f - 0.10f * t;
            }
            if (lat > 0.80f) {
                float t = (lat - 0.80f) / 0.20f;
                r = r + (1 - r) * t; g = g + (1 - g) * t; b = b + (1 - b) * t;
            }
            faceAlbedo[f][0] = r; faceAlbedo[f][1] = g; faceAlbedo[f][2] = b;
        }
    }

    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1.0e9f;
        lastNano = now;
        if (dt > 0.1f) dt = 0.1f;
        float baseSpeed = planet.defaultRotationSpeed();
        if (!dragging) yaw += dt * baseSpeed;
        cloudSpin = updateLayerSpin(PlanetLayerType.CLOUD, cloudSpin, dt, baseSpeed);
        atmosSpin = updateLayerSpin(PlanetLayerType.ATMOSPHERE, atmosSpin, dt, baseSpeed);
        techSpin = updateLayerSpin(PlanetLayerType.TECH, techSpin, dt, baseSpeed);
        wireSpin = updateLayerSpin(PlanetLayerType.WIREFRAME, wireSpin, dt, baseSpeed);
        cloudYaw = yaw + cloudSpin;
        atmosYaw = yaw + atmosSpin;
        techYaw = yaw + techSpin;
        wireYaw = yaw + wireSpin;

        GuiGraphics g = guiContext.graphics;
        int x = (int) getPositionX(), y = (int) getPositionY();
        int w = (int) getSizeWidth(), h = (int) getSizeHeight();
        g.fill(x, y, x + w, y + h, 0xFF000000);

        float cx = x + w / 2f, cy = y + h / 2f;
        float focalDesired = Math.min(w, h) * 0.9f;
        float fov = 2f * (float) Math.atan((h / 2f) / focalDesired);
        float focal = (h / 2f) / (float) Math.tan(fov / 2f);
        float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        float cosX = (float) Math.cos(pitch), sinX = (float) Math.sin(pitch);

        // 星球：相机空间坐标 + 拾取用屏幕坐标/世界z
        for (int i = 0; i < shell.vertices.length; i++) {
            float[] v = shell.vertices[i];
            float rx = (v[0] * planetR) * cosY + (v[2] * planetR) * sinY;
            float rz1 = -(v[0] * planetR) * sinY + (v[2] * planetR) * cosY;
            float ry = v[1] * planetR;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            pcx[i] = rx; pcy[i] = ry2; pcz[i] = rz - dist;
            float denom = -pcz[i];
            if (denom < 0.05f) denom = 0.05f;
            tx[i] = cx + rx * focal / denom;
            ty[i] = cy - ry2 * focal / denom;
            tnz[i] = rz;
        }

        // 最外层棱框（使用线框层独立自转）
        float wCosY = (float) Math.cos(wireYaw), wSinY = (float) Math.sin(wireYaw);
        for (int i = 0; i < shell.vertices.length; i++) {
            float[] v = shell.vertices[i];
            float rx = (v[0] * wireR) * wCosY + (v[2] * wireR) * wSinY;
            float rz1 = -(v[0] * wireR) * wSinY + (v[2] * wireR) * wCosY;
            float ry = v[1] * wireR;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            wcx[i] = rx; wcy[i] = ry2; wcz[i] = rz - dist;
        }

        // 大气层（使用大气层独立自转）
        float aCosY = (float) Math.cos(atmosYaw), aSinY = (float) Math.sin(atmosYaw);
        for (int i = 0; i < atmos.vertices.length; i++) {
            float[] v = atmos.vertices[i];
            float rx = (v[0] * atmosR) * aCosY + (v[2] * atmosR) * aSinY;
            float rz1 = -(v[0] * atmosR) * aSinY + (v[2] * atmosR) * aCosY;
            float ry = v[1] * atmosR;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            acx[i] = rx; acy[i] = ry2; acz[i] = rz - dist;
        }

        // 悬停：只对正面可见科技地块拾取（点不到被挡住的背面）
        hoveredTile = -1;
        for (int f = 0; f < shell.faces.length; f++) {
            if (tileAvgWorldZ(f) <= 0) continue;
            if (pointInTilePoly(guiContext.mouseX, guiContext.mouseY, f)) {
                hoveredTile = f;
                break;
            }
        }

        g.flush();
        // 关键：模型矩阵用单位阵，避免把 GUI 的特殊平移矩阵带进 3D 渲染
        Matrix4f matrix = new Matrix4f();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        // 关键：applyModelViewMatrix 把 stack 顶部同步到 modelViewMatrix
        // shader 的 ModelViewMat uniform 读的是 modelViewMatrix，不是 stack
        RenderSystem.applyModelViewMatrix();

        Matrix4f proj = new Matrix4f().perspective(fov, (float) w / (float) h, 0.1f, 100f);
        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.clearDepth(1.0f);
        // clearDepth 只设置清零值，必须调用 clear 执行真正的 GL 清零
        // GL_DEPTH_BUFFER_BIT = 0x100
        RenderSystem.clear(0x100, false);

        // 图层系统驱动渲染：只画存在的可见图层（按半径从内到外）
        if (layerVisible(PlanetLayerType.BASE)) drawPlanet(g, matrix);
        if (layerVisible(PlanetLayerType.CLOUD)) drawClouds(g, matrix);

        // 半透明/装饰层：不写深度，仍按深度测试被星球挡住背面
        RenderSystem.depthMask(false);
        if (layerVisible(PlanetLayerType.ATMOSPHERE)) drawAtmosphere(g, matrix);
        if (layerVisible(PlanetLayerType.TECH)) drawTechLayer(g, matrix);
        if (layerVisible(PlanetLayerType.WIREFRAME)) drawWireframe(g, matrix);
        drawTechHighlight(g, matrix); // 选择框始终基于科技数据

        // 恢复 GUI 投影与深度状态
        RenderSystem.setProjectionMatrix(oldProj, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix(); // 同步回 GUI 的 modelview 给 shader
        RenderSystem.setShaderColor(1, 1, 1, 1); // 重置 shader 颜色
    }

    /** 内核星球：全部六边形/五边形色块，不透明、写深度。 */
    private float updateLayerSpin(PlanetLayerType type, float spin, float dt, float baseSpeed) {
        var layer = planet.layer(type);
        if (layer.isEmpty()) return spin;
        float speed = planet.resolveRotationSpeed(layer.get());
        return spin + (speed - baseSpeed) * dt;
    }

    private boolean layerVisible(PlanetLayerType type) {
        var layer = planet.layer(type);
        return layer.isPresent() && layer.get().visible();
    }

    private void drawPlanet(GuiGraphics g, Matrix4f matrix) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f = 0; f < shell.faces.length; f++) {
            int[] fv = shell.faces[f];
            float nX = 0, nY = 0, nZ = 0;
            float ccx = 0, ccy = 0, ccz = 0;
            for (int v : fv) {
                nX += pcx[v]; nY += pcy[v]; nZ += pcz[v] + dist;
                ccx += pcx[v]; ccy += pcy[v]; ccz += pcz[v];
            }
            float nlen = (float) Math.sqrt(nX * nX + nY * nY + nZ * nZ);
            if (nlen > 1e-5f) { nX /= nlen; nY /= nlen; nZ /= nlen; }
            float diff = Math.max(0, nX * LX + nY * LY + nZ * LZ);
            float shade = AMB + (1 - AMB) * diff;
            float r = Math.min(1, faceAlbedo[f][0] * shade);
            float g2 = Math.min(1, faceAlbedo[f][1] * shade);
            float b2 = Math.min(1, faceAlbedo[f][2] * shade);
            ccx /= fv.length; ccy /= fv.length; ccz /= fv.length;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                bb.addVertex(matrix, ccx, ccy, ccz).setColor(r, g2, b2, 1f);
                bb.addVertex(matrix, pcx[fv[k]], pcy[fv[k]], pcz[fv[k]]).setColor(r, g2, b2, 1f);
                bb.addVertex(matrix, pcx[fv[a1]], pcy[fv[a1]], pcz[fv[a1]]).setColor(r, g2, b2, 1f);
            }
        }
        BufferUploader.drawWithShader(bb.build());
    }

    /** 大气层：全部半透明面，深度测试自动剔除被星球挡住的背面。 */

    /** 云层：在星球表面稍高处画半透明白色噪点片 */
    private void drawClouds(GuiGraphics g, Matrix4f matrix) {
        // 云层半径由图层对象提供，使用云层独立自转
        float cosY = (float) Math.cos(cloudYaw), sinY = (float) Math.sin(cloudYaw);
        float cosX = (float) Math.cos(pitch), sinX = (float) Math.sin(pitch);
        // 云层顶点（世界坐标变换到相机空间）
        float[] ccx = new float[shell.vertices.length];
        float[] ccy = new float[shell.vertices.length];
        float[] ccz = new float[shell.vertices.length];
        float[] cnx = new float[shell.vertices.length];
        float[] cny = new float[shell.vertices.length];
        for (int i = 0; i < shell.vertices.length; i++) {
            float[] v = shell.vertices[i];
            float rx = v[0] * cloudR * cosY + v[2] * cloudR * sinY;
            float rz1 = -v[0] * cloudR * sinY + v[2] * cloudR * cosY;
            float ry = v[1] * cloudR;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            ccx[i] = rx; ccy[i] = ry2; ccz[i] = rz - dist;
            cnx[i] = rx; cny[i] = ry2;
        }
        List<Integer> pf = new ArrayList<>(shell.faces.length);
        for (int f = 0; f < shell.faces.length; f++) pf.add(f);
        pf.sort(Comparator.comparingDouble(this::tileAvgWorldZ));
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f : pf) {
            int[] fv = shell.faces[f];
            // 云量：基于世界坐标的噪声，模拟云带分布
            float cx0 = 0, cy0 = 0, cz0 = 0;
            for (int v : fv) { cx0 += shell.vertices[v][0]; cy0 += shell.vertices[v][1]; cz0 += shell.vertices[v][2]; }
            float len = (float) Math.sqrt(cx0 * cx0 + cy0 * cy0 + cz0 * cz0);
            cx0 /= len; cy0 /= len; cz0 /= len;
            float cloudVal = noise.fbm(cx0 * 2.5f + 7.3f, cy0 * 2.5f + 13.7f, cz0 * 2.5f + 3.1f);
            // 二值判定：有云 or 无云
            float lat = Math.abs(cy0);
            float threshold = 0.38f + lat * 0.2f; // 赤道阈值低（多云），极地阈值高（少云）
            if (cloudVal < threshold) continue; // 无云地块，跳过
            // 简单光照
            float nX = cnx[fv[0]] + cnx[fv[1]] + cnx[fv[2]];
            float nY = cny[fv[0]] + cny[fv[1]] + cny[fv[2]];
            float nLen = (float) Math.sqrt(nX * nX + nY * nY);
            if (nLen > 1e-5f) { nX /= nLen; nY /= nLen; }
            float shade = AMB + (1 - AMB) * Math.max(0, nX * LX + nY * LY + 0.577f * LZ);
            float fa = 0.55f * shade; // 有云地块：固定半透明白
            float fcx = 0, fcy = 0, fcz = 0;
            for (int v : fv) { fcx += ccx[v]; fcy += ccy[v]; fcz += ccz[v]; }
            fcx /= fv.length; fcy /= fv.length; fcz /= fv.length;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                bb.addVertex(matrix, fcx, fcy, fcz).setColor(0.95f, 0.97f, 1.0f, fa);
                bb.addVertex(matrix, ccx[fv[k]], ccy[fv[k]], ccz[fv[k]]).setColor(1f, 1f, 1f, fa);
                bb.addVertex(matrix, ccx[fv[a1]], ccy[fv[a1]], ccz[fv[a1]]).setColor(1f, 1f, 1f, fa);
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
    }

    /** 科技项悬浮层：在大气层上方显示 tier 彩色标记，不改动星球地块颜色。 */
    private void drawTechLayer(GuiGraphics g, Matrix4f matrix) {
        if (nodes.isEmpty()) return;
        // 科技层半径由图层对象提供，使用科技层独立自转
        float cosY = (float) Math.cos(techYaw), sinY = (float) Math.sin(techYaw);
        float cosX = (float) Math.cos(pitch), sinX = (float) Math.sin(pitch);
        // 科技顶点世界->相机
        float[] tcx = new float[shell.vertices.length];
        float[] tcy = new float[shell.vertices.length];
        float[] tcz = new float[shell.vertices.length];
        for (int i = 0; i < shell.vertices.length; i++) {
            float[] v = shell.vertices[i];
            float rx = v[0] * techR * cosY + v[2] * techR * sinY;
            float rz1 = -v[0] * techR * sinY + v[2] * techR * cosY;
            float ry = v[1] * techR;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            tcx[i] = rx; tcy[i] = ry2; tcz[i] = rz - dist;
        }
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f = 0; f < nodes.size(); f++) {
            int[] fv = shell.faces[f];
            int col = tierColor(nodes.get(f).tier());
            float cr = ((col >> 16) & 0xFF) / 255f;
            float cg = ((col >> 8) & 0xFF) / 255f;
            float cb = (col & 0xFF) / 255f;
            // 面中心
            float cx = 0, cy = 0, cz = 0;
            for (int v : fv) { cx += tcx[v]; cy += tcy[v]; cz += tcz[v]; }
            cx /= fv.length; cy /= fv.length; cz /= fv.length;
            // 半透明面填充
            float fa = 0.28f;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                bb.addVertex(matrix, cx, cy, cz).setColor(cr, cg, cb, fa);
                bb.addVertex(matrix, tcx[fv[k]], tcy[fv[k]], tcz[fv[k]]).setColor(cr, cg, cb, fa);
                bb.addVertex(matrix, tcx[fv[a1]], tcy[fv[a1]], tcz[fv[a1]]).setColor(cr, cg, cb, fa);
            }
            // 亮边
            float ea = 0.8f;
            float hw = 0.006f;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                addQuad3D(bb, matrix, tcx[fv[k]], tcy[fv[k]], tcz[fv[k]],
                        tcx[fv[a1]], tcy[fv[a1]], tcz[fv[a1]], hw, cr, cg, cb, ea);
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
    }

    private void drawAtmosphere(GuiGraphics g, Matrix4f matrix) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f = 0; f < atmos.faces.length; f++) {
            int[] fv = atmos.faces[f];
            float rzSum = 0;
            for (int v : fv) rzSum += acz[v] + dist;
            float rzn = clamp(rzSum / fv.length / atmosR, -1, 1);
            float rim = 1 - Math.abs(rzn);
            float alpha = 0.05f + 0.24f * rim;
            for (int k = 0; k < 3; k++) {
                int idx = fv[k];
                bb.addVertex(matrix, acx[idx], acy[idx], acz[idx]).setColor(0.25f, 0.55f, 1.0f, alpha);
            }
        }
        BufferUploader.drawWithShader(bb.build());
    }

    /** 最外层棱框：全部棱用屏幕等宽四边带绘制，深度测试自动遮挡背面。 */
    private void drawWireframe(GuiGraphics g, Matrix4f matrix) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int[] ed : shell.edges) {
            int i0 = ed[0], i1 = ed[1];
            addQuad3D(bb, matrix,
                    wcx[i0], wcy[i0], wcz[i0],
                    wcx[i1], wcy[i1], wcz[i1],
                    EDGE_HW, 0.55f, 0.85f, 1.0f, 0.35f);
        }
        BufferUploader.drawWithShader(bb.build());
    }

    /** 选择框：世界坐标追赶 + 淡入 + 呼吸脉冲 + 半透明面 + 亮边 + 角点辉光 */
    private void drawTechHighlight(GuiGraphics g, Matrix4f matrix) {
        int f;
        if (hoveredTile >= 0) f = hoveredTile;
        else if (selectedTile >= 0) f = selectedTile;
        else f = -1;

        long now = System.nanoTime();
        float dt = (now - lastHighlightNano) / 1e9f;
        lastHighlightNano = now;
        if (dt > 0.1f) dt = 0.1f;

        boolean active = (f >= 0);
        if (active) { if (hoverStartNano == 0) hoverStartNano = now; } else hoverStartNano = 0;
        float elapsed = active ? (now - hoverStartNano) / 1e9f : 0f;
        float fadeIn = 1f - (float) Math.pow(1f - Math.min(1f, elapsed / 0.25f), 3);
        hoverAlpha += ((active ? fadeIn : 0f) - hoverAlpha) * Math.min(1f, dt * (active ? 12f : 6f));
        if (hoverAlpha < 0.005f) { hoverAlpha = 0; chaseFace = -1; chaseActive = false; return; }
        float a = hoverAlpha * (0.85f + 0.15f * (float) Math.sin(elapsed * 4.5f));

        if (f >= 0 && f != chaseFace) {
            boolean firstEver = (chaseFace == -1);
            chaseFace = f; chaseActive = true; chaseFaceVerts = shell.faces[f].length;
            if (firstEver) {
                // 第一次出现：直接锁定到目标，不从远处飞来
                int[] fv = shell.faces[f];
                for (int i = 0; i < fv.length; i++) {
                    chaseWx[i] = shell.vertices[fv[i]][0] * wireR;
                    chaseWy[i] = shell.vertices[fv[i]][1] * wireR;
                    chaseWz[i] = shell.vertices[fv[i]][2] * wireR;
                }
                chaseWMx = 0; chaseWMy = 0; chaseWMz = 0;
                for (int v : fv) { chaseWMx += shell.vertices[v][0]; chaseWMy += shell.vertices[v][1]; chaseWMz += shell.vertices[v][2]; }
                chaseWMx = chaseWMx / fv.length * wireR;
                chaseWMy = chaseWMy / fv.length * wireR;
                chaseWMz = chaseWMz / fv.length * wireR;
                if (fv.length < 6) { chaseWx[5] = chaseWMx; chaseWy[5] = chaseWMy; chaseWz[5] = chaseWMz; }
                chaseActive = false;
            }
            // 否则：保留当前 chaseWx（旧格子位置）作为追赶起点
        }
        if (f < 0) { chaseFace = -1; chaseActive = false; return; }
        if (chaseFace < 0) return;

        int[] fv = shell.faces[chaseFace];
        int drawN = fv.length;

        if (chaseActive) {
            float ch = 1f - (float) Math.exp(-dt / CHASE_TAU);
            float maxD2 = 0;
            for (int i = 0; i < fv.length; i++) {
                float ttx = shell.vertices[fv[i]][0] * wireR;
                float tty = shell.vertices[fv[i]][1] * wireR;
                float ttz = shell.vertices[fv[i]][2] * wireR;
                chaseWx[i] += (ttx - chaseWx[i]) * ch;
                chaseWy[i] += (tty - chaseWy[i]) * ch;
                chaseWz[i] += (ttz - chaseWz[i]) * ch;
                float dx = ttx - chaseWx[i], dy = tty - chaseWy[i], dz = ttz - chaseWz[i];
                maxD2 = Math.max(maxD2, dx * dx + dy * dy + dz * dz);
            }
            chaseWMx = 0; chaseWMy = 0; chaseWMz = 0;
            for (int i = 0; i < fv.length; i++) { chaseWMx += chaseWx[i]; chaseWMy += chaseWy[i]; chaseWMz += chaseWz[i]; }
            chaseWMx /= fv.length; chaseWMy /= fv.length; chaseWMz /= fv.length;
            if (fv.length < 6) { chaseWx[5] = chaseWMx; chaseWy[5] = chaseWMy; chaseWz[5] = chaseWMz; }
            if (maxD2 < 1e-6f) chaseActive = false;
        } else {
            for (int i = 0; i < fv.length; i++) {
                chaseWx[i] = shell.vertices[fv[i]][0] * wireR;
                chaseWy[i] = shell.vertices[fv[i]][1] * wireR;
                chaseWz[i] = shell.vertices[fv[i]][2] * wireR;
            }
            chaseWMx = 0; chaseWMy = 0; chaseWMz = 0;
            for (int v : fv) { chaseWMx += shell.vertices[v][0]; chaseWMy += shell.vertices[v][1]; chaseWMz += shell.vertices[v][2]; }
            chaseWMx = chaseWMx / fv.length * wireR;
            chaseWMy = chaseWMy / fv.length * wireR;
            chaseWMz = chaseWMz / fv.length * wireR;
            if (fv.length < 6) { chaseWx[5] = chaseWMx; chaseWy[5] = chaseWMy; chaseWz[5] = chaseWMz; }
        }

        float cosY3 = (float) Math.cos(wireYaw), sinY3 = (float) Math.sin(wireYaw);
        float cosX3 = (float) Math.cos(pitch), sinX3 = (float) Math.sin(pitch);
        float[] cx = new float[6], cy = new float[6], cz = new float[6];
        for (int i = 0; i < drawN; i++) {
            float rx = chaseWx[i] * cosY3 + chaseWz[i] * sinY3;
            float rz1 = -chaseWx[i] * sinY3 + chaseWz[i] * cosY3;
            cy[i] = chaseWy[i] * cosX3 - rz1 * sinX3;
            cz[i] = chaseWy[i] * sinX3 + rz1 * cosX3 - dist;
            cx[i] = rx;
        }
        float cmx = 0, cmy = 0, cmz = 0;
        if (fv.length < 6) {
            float rx = chaseWMx * cosY3 + chaseWMz * sinY3;
            float rz1 = -chaseWMx * sinY3 + chaseWMz * cosY3;
            cmx = rx; cmy = chaseWMy * cosX3 - rz1 * sinX3; cmz = chaseWMy * sinX3 + rz1 * cosX3 - dist;
        } else {
            for (int i = 0; i < drawN; i++) { cmx += cx[i]; cmy += cy[i]; cmz += cz[i]; }
            cmx /= drawN; cmy /= drawN; cmz /= drawN;
        }

        float cr, cg, cb;
        if (chaseFace >= 0 && chaseFace < nodes.size()) {
            int col = tierColor(nodes.get(chaseFace).tier());
            cr = ((col >> 16) & 0xFF) / 255f; cg = ((col >> 8) & 0xFF) / 255f; cb = (col & 0xFF) / 255f;
        } else if (chaseFace >= 0) {
            cr = Math.min(1f, faceAlbedo[chaseFace][0] * 1.5f);
            cg = Math.min(1f, faceAlbedo[chaseFace][1] * 1.5f);
            cb = Math.min(1f, faceAlbedo[chaseFace][2] * 1.5f);
        } else { cr = 1; cg = 1; cb = 1; }

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float fa = 0.35f * a;
        if (fa > 0.01f) {
            for (int k = 0; k < drawN; k++) { int a1 = (k + 1) % drawN;
                bb.addVertex(matrix, cmx, cmy, cmz).setColor(cr, cg, cb, fa);
                bb.addVertex(matrix, cx[k], cy[k], cz[k]).setColor(cr, cg, cb, fa);
                bb.addVertex(matrix, cx[a1], cy[a1], cz[a1]).setColor(cr, cg, cb, fa);
            }
        }
        float ea = 0.95f * a; float hw = 0.025f;
        for (int k = 0; k < drawN; k++) { int a1 = (k + 1) % drawN;
            addQuad3D(bb, matrix, cx[k], cy[k], cz[k], cx[a1], cy[a1], cz[a1], hw, cr, cg, cb, ea);
        }

        // 角帽：每个顶点把相邻两条棱的端面四个角连成梯形，填角但不突出
        float ca = 0.95f * a;
        for (int i = 0; i < drawN; i++) {
            int p = (i - 1 + drawN) % drawN;
            int q = (i + 1) % drawN;
            appendCornerCap(bb, matrix, cx[i], cy[i], cz[i],
                    cx[p], cy[p], cz[p],
                    cx[q], cy[q], cz[q],
                    hw, cr, cg, cb, ca);
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
    }

    /** 角帽：把当前顶点两条邻边的端面互相连起来，形成无突起的梯形接缝 */
    private static void appendCornerCap(BufferBuilder bb, Matrix4f m,
                                        float vx, float vy, float vz,
                                        float px, float py, float pz,
                                        float qx, float qy, float qz,
                                        float hw, float r, float g, float b, float a) {
        // 前一邻边（p->v）的端面法线
        float dx1 = vx - px, dy1 = vy - py;
        float len1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1);
        if (len1 < 1e-4f) return;
        float n1x = -dy1 / len1 * hw, n1y = dx1 / len1 * hw;
        // 后一邻边（v->q）的端面法线
        float dx2 = qx - vx, dy2 = qy - vy;
        float len2 = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2);
        if (len2 < 1e-4f) return;
        float n2x = -dy2 / len2 * hw, n2y = dx2 / len2 * hw;
        // 用四边形连接两侧角点：v-n1, v+n1, v+n2, v-n2
        bb.addVertex(m, vx - n1x, vy - n1y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx + n1x, vy + n1y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx + n2x, vy + n2y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx - n1x, vy - n1y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx + n2x, vy + n2y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx - n2x, vy - n2y, vz).setColor(r, g, b, a);
    }

    /** 相机空间等宽细长四边形：宽度是相机空间固定值，投影后自然近粗远细。 */
    private static void addQuad3D(BufferBuilder bb, Matrix4f m,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float hw, float r, float g, float b, float a) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return;
        // 相机空间 XY 平面垂直方向
        float nx = -dy / len * hw;
        float ny =  dx / len * hw;
        bb.addVertex(m, x0 - nx, y0 - ny, z0).setColor(r, g, b, a);
        bb.addVertex(m, x0 + nx, y0 + ny, z0).setColor(r, g, b, a);
        bb.addVertex(m, x1 - nx, y1 - ny, z1).setColor(r, g, b, a);
        bb.addVertex(m, x0 + nx, y0 + ny, z0).setColor(r, g, b, a);
        bb.addVertex(m, x1 + nx, y1 + ny, z1).setColor(r, g, b, a);
        bb.addVertex(m, x1 - nx, y1 - ny, z1).setColor(r, g, b, a);
    }

    private float tileAvgWorldZ(int f) {
        int[] fv = shell.faces[f];
        float s = 0;
        for (int v : fv) s += tnz[v];
        return s / fv.length;
    }

    private boolean pointInTilePoly(int mx, int my, int faceIdx) {
        int[] fv = shell.faces[faceIdx];
        boolean inside = false;
        int n = fv.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = tx[fv[i]], yi = ty[fv[i]];
            float xj = tx[fv[j]], yj = ty[fv[j]];
            boolean intersect = ((yi > my) != (yj > my)) &&
                    (mx < (xj - xi) * (my - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static int tierColor(int tier) {
        return switch (tier) {
            case 0 -> 0x4FC3F7;
            case 1 -> 0x81C784;
            case 2 -> 0xBA68C8;
            case 3 -> 0xFFB74D;
            default -> 0x4DB6AC;
        };
    }

    /** 轻量 3D 值噪声（带种子），用于程序化生成类地星球色块。 */
    private static final class Noise3 {
        private final long seed;
        Noise3(long seed) { this.seed = seed; }
        private int hash(int x, int y, int z) {
            long h = seed ^ (x * 374761393L) ^ (y * 668265263L) ^ (z * 2654435761L);
            h = (h ^ (h >>> 13)) * 1274126177L;
            h = h ^ (h >>> 16);
            return (int) (h & 0x7fffffff);
        }
        private float val(int x, int y, int z) { return hash(x, y, z) / (float) 0x7fffffff; }
        private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
        float noise(float x, float y, float z) {
            int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
            float xf = x - xi, yf = y - yi, zf = z - zi;
            float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
            float c000 = val(xi, yi, zi), c100 = val(xi + 1, yi, zi);
            float c010 = val(xi, yi + 1, zi), c110 = val(xi + 1, yi + 1, zi);
            float c001 = val(xi, yi, zi + 1), c101 = val(xi + 1, yi, zi + 1);
            float c011 = val(xi, yi + 1, zi + 1), c111 = val(xi + 1, yi + 1, zi + 1);
            float x00 = lerp(c000, c100, u), x10 = lerp(c010, c110, u);
            float x01 = lerp(c001, c101, u), x11 = lerp(c011, c111, u);
            return lerp(lerp(x00, x10, v), lerp(x01, x11, v), w);
        }
        float fbm(float x, float y, float z) {
            float s = 0, a = 0.5f, f = 1;
            for (int o = 0; o < 4; o++) { s += a * noise(x * f, y * f, z * f); f *= 2; a *= 0.5f; }
            return s;
        }
    }
}
