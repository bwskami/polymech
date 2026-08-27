package com.mss.polymech.client.gui.widget;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 科技树 3D 可视化：
 * <ul>
 *   <li>内核：随机生成的类地星球（程序化大陆/海洋/冰盖，朗伯着色 + 边缘光），实心不透明。</li>
 *   <li>外壳：正六边形 + 12 五边形拼接的球，只显示“棱”（线框），面透明可透视到内核；
 *       但只画正面棱，背面棱剔除，因此不会透出背面的线框。</li>
 *   <li>科技 = 外壳上的某个地块（多边形面）：高亮该面的棱（tier 色）+ 极淡透明填充（可透出星球），
 *       不另盖不透明多边形。悬停高亮 + 名称提示，点击打开“思索”面板。</li>
 *   <li>轨道相机：拖拽旋转、滚轮缩放。</li>
 * </ul>
 */
public class PolyhedronView extends UIElement {

    private static final float PLANET_R = 0.95f;

    private final Polyhedron shell;   // 外壳：六边形+五边形线框
    private final Polyhedron planet;  // 内核：类地星球
    private final List<TechNode> nodes;
    private final Consumer<TechNode> onSelect;
    private final float[][] pAlbedo;  // 星球每顶点反照率(r,g,b)
    private final Noise3 noise;

    private float yaw = 0.6f;
    private float pitch = 0.35f;
    private float dist = 3.2f;
    private boolean dragging = false;
    private long lastNano = System.nanoTime();

    private int hoveredTile = -1;
    private int selectedTile = -1;

    // 投影缓存：星球
    private final float[] px, py, nx, ny, nz, pr, pg, pb;
    // 投影缓存：外壳
    private final float[] sx, sy, snx, sny, snz;

    public PolyhedronView(Polyhedron shell, Polyhedron planet, List<TechNode> nodes, Consumer<TechNode> onSelect) {
        this.shell = shell;
        this.planet = planet;
        this.nodes = nodes;
        this.onSelect = onSelect;
        this.noise = new Noise3(System.nanoTime());

        int pv = planet.vertices.length;
        this.px = new float[pv]; this.py = new float[pv];
        this.nx = new float[pv]; this.ny = new float[pv]; this.nz = new float[pv];
        this.pr = new float[pv]; this.pg = new float[pv]; this.pb = new float[pv];
        this.pAlbedo = new float[pv][3];
        precomputePlanet();

        int sv = shell.vertices.length;
        this.sx = new float[sv]; this.sy = new float[sv];
        this.snx = new float[sv]; this.sny = new float[sv]; this.snz = new float[sv];

        addEventListener(UIEvents.MOUSE_DOWN, e -> dragging = true);
        addEventListener(UIEvents.MOUSE_UP, e -> dragging = false);
        addEventListener(UIEvents.MOUSE_MOVE, e -> {
            if (dragging) {
                yaw += e.deltaX * 0.01f;
                pitch += e.deltaY * 0.01f;
                pitch = clamp(pitch, -1.4f, 1.4f);
            }
        });
        addEventListener(UIEvents.MOUSE_WHEEL, e -> {
            if (e.deltaY > 0) dist *= 1.1f;
            else if (e.deltaY < 0) dist *= 0.9f;
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

    /** 程序化生成类地星球反照率（海洋/大陆/冰盖）。 */
    private void precomputePlanet() {
        for (int i = 0; i < planet.vertices.length; i++) {
            float[] d = planet.vertices[i];
            float n = noise.fbm(d[0] * 1.8f + 11.3f, d[1] * 1.8f + 27.1f, d[2] * 1.8f + 5.7f);
            n = clamp(n / 0.94f, 0, 1);
            float lat = Math.abs(d[1]);
            float r, g, b;
            if (n < 0.5f) {            // 海洋
                float t = n / 0.5f;
                r = 0.02f + 0.03f * t; g = 0.12f + 0.30f * t; b = 0.38f + 0.45f * t;
            } else {                   // 陆地
                float t = (n - 0.5f) / 0.5f;
                r = 0.20f + 0.45f * t; g = 0.45f - 0.10f * t; b = 0.16f - 0.10f * t;
            }
            if (lat > 0.82f) {         // 极地冰盖
                float t = (lat - 0.82f) / 0.18f;
                r = r + (1 - r) * t; g = g + (1 - g) * t; b = b + (1 - b) * t;
            }
            pAlbedo[i][0] = r; pAlbedo[i][1] = g; pAlbedo[i][2] = b;
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
        float focal = Math.min(w, h) * 0.9f;
        float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        float cosX = (float) Math.cos(pitch), sinX = (float) Math.sin(pitch);

        // 投影内核星球（半径 PLANET_R）
        for (int i = 0; i < planet.vertices.length; i++) {
            float[] v = planet.vertices[i];
            float rx = (v[0] * PLANET_R) * cosY + (v[2] * PLANET_R) * sinY;
            float rz1 = -(v[0] * PLANET_R) * sinY + (v[2] * PLANET_R) * cosY;
            float ry = v[1] * PLANET_R;
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            float denom = dist - rz;
            if (denom < 0.05f) denom = 0.05f;
            px[i] = cx + rx * focal / denom;
            py[i] = cy - ry2 * focal / denom;
            nx[i] = rx; ny[i] = ry2; nz[i] = rz;
            // 着色
            float diff = Math.max(0, nx[i] * LX + ny[i] * LY + nz[i] * LZ);
            float shade = AMB + (1 - AMB) * diff;
            float facing = Math.max(0, nz[i]);
            float rim = (float) Math.pow(1 - facing, 3) * 0.5f;
            pr[i] = Math.min(1, pAlbedo[i][0] * shade + 0.30f * rim);
            pg[i] = Math.min(1, pAlbedo[i][1] * shade + 0.45f * rim);
            pb[i] = Math.min(1, pAlbedo[i][2] * shade + 0.70f * rim);
        }

        // 投影外壳（半径 1）
        for (int i = 0; i < shell.vertices.length; i++) {
            float[] v = shell.vertices[i];
            float rx = v[0] * cosY + v[2] * sinY;
            float rz1 = -v[0] * sinY + v[2] * cosY;
            float ry = v[1];
            float ry2 = ry * cosX - rz1 * sinX;
            float rz = ry * sinX + rz1 * cosX;
            float denom = dist - rz;
            if (denom < 0.05f) denom = 0.05f;
            sx[i] = cx + rx * focal / denom;
            sy[i] = cy - ry2 * focal / denom;
            snx[i] = rx; sny[i] = ry2; snz[i] = rz;
        }

        // 悬停：正面科技地块命中
        hoveredTile = -1;
        for (int f = 0; f < nodes.size(); f++) {
            if (shellAvgNz(f) <= 0) continue;
            if (pointInShellPoly(guiContext.mouseX, guiContext.mouseY, f)) {
                hoveredTile = f;
                break;
            }
        }

        // 绘制内核星球（实心不透明，正面 + 画家算法）
        List<Integer> pf = new ArrayList<>();
        for (int f = 0; f < planet.faces.length; f++) {
            if (planetAvgNz(f) > 0) pf.add(f);
        }
        pf.sort(Comparator.comparingDouble(this::planetAvgNz));

        g.flush();
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        BufferBuilder fb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f : pf) {
            int[] fv = planet.faces[f];
            for (int k = 0; k < 3; k++) {
                fb.addVertex(matrix, px[fv[k]], py[fv[k]], 0).setColor(pr[fv[k]], pg[fv[k]], pb[fv[k]], 1f);
            }
        }
        BufferUploader.drawWithShader(fb.build());

        // 外壳线框：只画正面棱（背面棱剔除，因此不会透出背面线框）
        RenderSystem.lineWidth(1.2f);
        BufferBuilder lb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        for (int[] ed : shell.edges) {
            int i0 = ed[0], i1 = ed[1];
            if (snz[i0] <= 0 || snz[i1] <= 0) continue;
            lb.addVertex(matrix, sx[i0], sy[i0], 0).setColor(0.30f, 0.55f, 0.7f, 0.35f);
            lb.addVertex(matrix, sx[i1], sy[i1], 0).setColor(0.30f, 0.55f, 0.7f, 0.35f);
        }
        BufferUploader.drawWithShader(lb.build());

        // 科技地块：极淡透明填充（可透出内核星球）+ 加亮棱（不再另盖不透明多边形）
        for (int f = 0; f < nodes.size(); f++) {
            if (shellAvgNz(f) <= 0) continue;
            int[] fv = shell.faces[f];
            int col = tierColor(nodes.get(f).tier());
            boolean hot = (f == hoveredTile) || (f == selectedTile);
            float cr = ((col >> 16) & 0xFF) / 255f;
            float cg = ((col >> 8) & 0xFF) / 255f;
            float cb = (col & 0xFF) / 255f;
            float fa = hot ? 0.32f : 0.16f;
            float ccx = 0, ccy = 0;
            for (int k : fv) { ccx += sx[k]; ccy += sy[k]; }
            ccx /= fv.length; ccy /= fv.length;
            BufferBuilder tfb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                tfb.addVertex(matrix, ccx, ccy, 0).setColor(cr, cg, cb, fa);
                tfb.addVertex(matrix, sx[fv[k]], sy[fv[k]], 0).setColor(cr, cg, cb, fa);
                tfb.addVertex(matrix, sx[fv[a1]], sy[fv[a1]], 0).setColor(cr, cg, cb, fa);
            }
            BufferUploader.drawWithShader(tfb.build());
            float oa = hot ? 1f : 0.85f;
            BufferBuilder tlb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                tlb.addVertex(matrix, sx[fv[k]], sy[fv[k]], 0).setColor(cr, cg, cb, oa);
                tlb.addVertex(matrix, sx[fv[a1]], sy[fv[a1]], 0).setColor(cr, cg, cb, oa);
            }
            BufferUploader.drawWithShader(tlb.build());
        }
    }

    private static final float LX = -0.398f, LY = 0.696f, LZ = 0.597f, AMB = 0.30f;

    private float shellAvgNz(int f) {
        int[] fv = shell.faces[f];
        return (snz[fv[0]] + snz[fv[1]] + snz[fv[2]]) / 3f;
    }

    private float planetAvgNz(int f) {
        int[] fv = planet.faces[f];
        return (nz[fv[0]] + nz[fv[1]] + nz[fv[2]]) / 3f;
    }

    private boolean pointInShellPoly(int mx, int my, int faceIdx) {
        int[] fv = shell.faces[faceIdx];
        boolean inside = false;
        int n = fv.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = sx[fv[i]], yi = sy[fv[i]];
            float xj = sx[fv[j]], yj = sy[fv[j]];
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

    /** 轻量 3D 值噪声（带种子），用于程序化生成类地星球。 */
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
