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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
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

    private static final float PLANET_R = 0.93f;
    private static final float ATMOS_R = 0.99f;
    private static final float WIRE_R = 1.05f;
    private static final float EDGE_HW = 0.01f;  // 相机空间宽度（投影后自然近粗远细）
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

    private int hoveredTile = -1;
    private int selectedTile = -1;

    // 拾取用：星球屏幕坐标 + 世界z（rz，>0 表示正面可见）
    private final float[] tx, ty, tnz;
    // 模型相机空间坐标（x, y, z-relative，渲染用）
    private final float[] pcx, pcy, pcz; // shell @ PLANET_R
    private final float[] wcx, wcy, wcz; // shell @ WIRE_R
    private final float[] acx, acy, acz; // atmos @ ATMOS_R

    public PolyhedronView(Polyhedron shell, Polyhedron atmos, List<TechNode> nodes, Consumer<TechNode> onSelect) {
        this.shell = shell;
        this.atmos = atmos;
        this.nodes = nodes;
        this.onSelect = onSelect;
        this.noise = new Noise3(PLANET_SEED);

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

            if (f < nodes.size()) {
                int col = tierColor(nodes.get(f).tier());
                faceAlbedo[f][0] = ((col >> 16) & 0xFF) / 255f;
                faceAlbedo[f][1] = ((col >> 8) & 0xFF) / 255f;
                faceAlbedo[f][2] = (col & 0xFF) / 255f;
                continue;
            }
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
        if (!dragging) yaw += dt * 0.25f;

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
            float rx = (v[0] * PLANET_R) * cosY + (v[2] * PLANET_R) * sinY;
            float rz1 = -(v[0] * PLANET_R) * sinY + (v[2] * PLANET_R) * cosY;
            float ry = v[1] * PLANET_R;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            pcx[i] = rx; pcy[i] = ry2; pcz[i] = rz - dist;
            float denom = -pcz[i];
            if (denom < 0.05f) denom = 0.05f;
            tx[i] = cx + rx * focal / denom;
            ty[i] = cy - ry2 * focal / denom;
            tnz[i] = rz;
        }

        // 最外层棱框：相机空间坐标
        for (int i = 0; i < shell.vertices.length; i++) {
            float[] v = shell.vertices[i];
            float rx = (v[0] * WIRE_R) * cosY + (v[2] * WIRE_R) * sinY;
            float rz1 = -(v[0] * WIRE_R) * sinY + (v[2] * WIRE_R) * cosY;
            float ry = v[1] * WIRE_R;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            wcx[i] = rx; wcy[i] = ry2; wcz[i] = rz - dist;
        }

        // 大气层：相机空间坐标
        for (int i = 0; i < atmos.vertices.length; i++) {
            float[] v = atmos.vertices[i];
            float rx = (v[0] * ATMOS_R) * cosY + (v[2] * ATMOS_R) * sinY;
            float rz1 = -(v[0] * ATMOS_R) * sinY + (v[2] * ATMOS_R) * cosY;
            float ry = v[1] * ATMOS_R;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            acx[i] = rx; acy[i] = ry2; acz[i] = rz - dist;
        }

        // 悬停：只对正面可见科技地块拾取（点不到被挡住的背面）
        hoveredTile = -1;
        for (int f = 0; f < nodes.size(); f++) {
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

        // 不透明星球：全部色块一次发，写深度成为遮挡体
        drawPlanet(g, matrix);

        // 半透明/装饰层：不写深度，仍按深度测试被星球挡住背面
        RenderSystem.depthMask(false);
        drawAtmosphere(g, matrix);
        drawWireframe(g, matrix);
        drawTechHighlight(g, matrix);

        // 恢复 GUI 投影与深度状态
        RenderSystem.setProjectionMatrix(oldProj, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix(); // 同步回 GUI 的 modelview 给 shader
        RenderSystem.setShaderColor(1, 1, 1, 1); // 重置 shader 颜色
    }

    /** 内核星球：全部六边形/五边形色块，不透明、写深度。 */
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
    private void drawAtmosphere(GuiGraphics g, Matrix4f matrix) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f = 0; f < atmos.faces.length; f++) {
            int[] fv = atmos.faces[f];
            float rzSum = 0;
            for (int v : fv) rzSum += acz[v] + dist;
            float rzn = clamp(rzSum / fv.length / ATMOS_R, -1, 1);
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
                    EDGE_HW, 0.55f, 0.85f, 1.0f, 0.95f);
        }
        BufferUploader.drawWithShader(bb.build());
    }

    /** 选中/悬停地块：加一圈更粗的轮廓，深度测试自动隐藏被挡住的背面。 */
    private void drawTechHighlight(GuiGraphics g, Matrix4f matrix) {
        int f;
        if (hoveredTile >= 0) f = hoveredTile;
        else if (selectedTile >= 0) f = selectedTile;
        else return;
        if (f < 0 || f >= nodes.size()) return;
        int[] fv = shell.faces[f];
        int col = tierColor(nodes.get(f).tier());
        float cr = ((col >> 16) & 0xFF) / 255f;
        float cg = ((col >> 8) & 0xFF) / 255f;
        float cb = (col & 0xFF) / 255f;
        float hw = 0.025f;  // 高亮轮廓比棱粗约2.5倍
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int k = 0; k < fv.length; k++) {
            int a1 = (k + 1) % fv.length;
            addQuad3D(bb, matrix,
                    wcx[fv[k]], wcy[fv[k]], wcz[fv[k]],
                    wcx[fv[a1]], wcy[fv[a1]], wcz[fv[a1]],
                    hw, cr, cg, cb, 1f);
        }
        BufferUploader.drawWithShader(bb.build());
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
