package com.mss.polymech.client.gui.widget.planet;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class SolarSystemView extends UIElement {
    // 光照方向已改为每个行星实时计算（向太阳方向），旧常量不再使用
    private static final float CHASE_TAU = 0.06f;
    // ===== skybox =====
    private ResourceLocation skyLoc;
    private static final int SKY_W = 1024, SKY_H = 512;

    private final SolarSystem solarSystem;
    private final List<TechNode> nodes;
    private final Consumer<TechNode> onSelect;
    private float yaw = 0.6f, pitch = 0.35f, dist = 3.2f;
    private float lastFocal, lastCx, lastCy;
    private float lastMouseX, lastMouseY;
    private boolean dragging;
    private int lastMX, lastMY;
    private long lastNano = System.nanoTime();
    private int focalIndex = 3;
    private float simTime, focalX, focalZ;
    // 摄像机过渡动画：从旧焦点平滑移动到新焦点
    private float camTransT = 1.0f;
    private float camFromX, camFromZ;
    private final float[][][] faceColors;
    private final Noise3[] cloudNoise;
    private final java.util.HashMap<Long, Noise3> layerNoiseCache = new java.util.HashMap<>();
    private final Map<Polyhedron, List<int[]>> edgeCache = new HashMap<>();
    private final HashMap<Long, Polyhedron> rockCache = new HashMap<>();

    private static final int ASTEROID_COUNT = 300;
    private static final int KUIPER_COUNT = 90;
    private final float[][] asteroidPos;  // [i]={angle,radius,y,size,tiltA,tiltB,r,g,b}
    private final float[][] kuiperPos;
    private static final float[][] PLANET_TINT = {
        /* Sun */      {1.0f, 0.85f, 0.3f},
        /* Mercury */  {0.60f, 0.55f, 0.50f},
        /* Venus */    {0.85f, 0.75f, 0.40f},
        /* Earth */    {0.15f, 0.35f, 0.70f},
        /* Moon */     {0.55f, 0.55f, 0.55f},
        /* Mars */     {0.80f, 0.35f, 0.15f},
        /* Phobos */   {0.50f, 0.48f, 0.45f},
        /* Deimos */   {0.55f, 0.50f, 0.48f},
        /* Jupiter */  {0.80f, 0.60f, 0.35f},
        /* Io */       {0.85f, 0.75f, 0.20f},
        /* Europa */   {0.70f, 0.70f, 0.80f},
        /* Ganymede */ {0.55f, 0.50f, 0.45f},
        /* Callisto */ {0.40f, 0.35f, 0.30f},
        /* Saturn */   {0.85f, 0.72f, 0.40f},
        /* Titan */    {0.80f, 0.60f, 0.25f},
        /* Enceladus */{0.90f, 0.90f, 0.92f},
        /* Uranus */   {0.55f, 0.78f, 0.82f},
        /* Neptune */  {0.20f, 0.35f, 0.80f},
        /* Pluto */    {0.65f, 0.60f, 0.55f},
        /* Charon */   {0.50f, 0.48f, 0.45f},
    };
    private int hoveredTile = -1;
    private int selectedTile = -1;
    private float hoverAlpha;
    private long hoverStartNano;
    private long lastHighlightNano = System.nanoTime();
    private int chaseFace = -1;
    private final float[] chaseWx = new float[6], chaseWy = new float[6], chaseWz = new float[6];
    private float chaseWMx, chaseWMy, chaseWMz;
    private boolean chaseActive;
    private int chaseFaceVerts;
    private float[] pickSx, pickSy, pickDepth;
    private int pickCount;
    private float[] tileSx, tileSy, tileZ;
    private float tileDist;
    private float currentTilt;
    private float overlayFade = 1f;
    private final PlanetLighting lighting = new PlanetLighting();
    private final ShadowModel shadowModel;
    private Polyhedron tileMesh;
    public SolarSystemView(SolarSystem solarSystem, List<TechNode> nodes, Consumer<TechNode> onSelect) {
        this.solarSystem = solarSystem;
        this.nodes = nodes;
        this.onSelect = onSelect;
        int n = solarSystem.size();
        this.faceColors = new float[n][][];
        this.cloudNoise = new Noise3[n];

        this.shadowModel = new ShadowModel(solarSystem);
        for (int i = 0; i < n; i++) {
            Polyhedron mesh = solarSystem.get(i).baseMesh();
            faceColors[i] = new float[mesh.faces.length][3];
            precomputeColors(i);
        }
        this.asteroidPos = new float[ASTEROID_COUNT][9];
        this.kuiperPos = new float[KUIPER_COUNT][9];
        long rng1 = 0xDEADBEEF;
        for (int i = 0; i < ASTEROID_COUNT; i++) {
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float angle = ((int)(rng1 >>> 33)) / (float)(1L << 31) * 6.2832f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float radius = 50f + ((int)(rng1 >>> 33)) / (float)(1L << 31) * 14f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float yPos = (((int)(rng1 >>> 33)) / (float)(1L << 31) - 0.5f) * 0.8f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float sz = 0.12f + ((int)(rng1 >>> 33)) / (float)(1L << 31) * 0.23f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float tiltA = ((int)(rng1 >>> 33)) / (float)(1L << 31) * 6.2832f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float tiltB = ((int)(rng1 >>> 33)) / (float)(1L << 31) * 6.2832f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float bright = 0.35f + ((int)(rng1 >>> 33)) / (float)(1L << 31) * 0.30f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
              asteroidPos[i] = new float[]{angle, radius, yPos, sz, tiltA, tiltB, bright, bright*0.85f, bright*0.70f, (float)(int)rng1};
        }
        for (int i = 0; i < KUIPER_COUNT; i++) {
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float angle = ((int)(rng1 >>> 33)) / (float)(1L << 31) * 6.2832f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float radius = 230f + ((int)(rng1 >>> 33)) / (float)(1L << 31) * 50f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float yPos = (((int)(rng1 >>> 33)) / (float)(1L << 31) - 0.5f) * 1.0f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float sz = 0.08f + ((int)(rng1 >>> 33)) / (float)(1L << 31) * 0.17f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float tiltA = ((int)(rng1 >>> 33)) / (float)(1L << 31) * 6.2832f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float tiltB = ((int)(rng1 >>> 33)) / (float)(1L << 31) * 6.2832f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
            float bright = 0.30f + ((int)(rng1 >>> 33)) / (float)(1L << 31) * 0.25f;
            rng1 = rng1 * 6364136223846793005L + 1442695040888963407L;
              kuiperPos[i] = new float[]{angle, radius, yPos, sz, tiltA, tiltB, bright*0.7f, bright*0.75f, bright*0.85f, (float)(int)rng1};
        }
        float[] wp = solarSystem.worldPos(focalIndex, 0);
        focalX = wp[0]; focalZ = wp[2];
        addEventListener(UIEvents.MOUSE_DOWN, e -> { dragging = true; lastMX = (int) e.x; lastMY = (int) e.y; });
        addEventListener(UIEvents.MOUSE_UP, e -> dragging = false);
        addEventListener(UIEvents.MOUSE_MOVE, e -> { if (dragging) { int mx = (int) e.x, my = (int) e.y; yaw += (mx - lastMX) * 0.01f; pitch += (my - lastMY) * 0.01f; pitch = clamp(pitch, -1.4f, 1.4f); lastMX = mx; lastMY = my; } });
        addEventListener(UIEvents.MOUSE_WHEEL, e -> { if (e.deltaY > 0) dist *= 0.85f; else dist *= 1.15f; dist = clamp(dist, minDistForFocal(), 300f); });
        addEventListener(UIEvents.CLICK, e -> {
            if (hoveredTile >= 0) {
                selectedTile = hoveredTile;
                List<TechNode> fn = solarSystem.get(focalIndex).techNodes();
                if (fn.isEmpty() && !nodes.isEmpty()) fn = nodes;
                if (hoveredTile >= 0 && hoveredTile < fn.size()) onSelect.accept(fn.get(hoveredTile));
            } else {
                int pi = pickPlanet((int) lastMouseX, (int) lastMouseY, lastFocal, lastCx, lastCy);
                if (pi >= 0 && pi != focalIndex) {
                    camFromX = focalX; camFromZ = focalZ;
                    camTransT = 0;
                    focalIndex = pi;
                    dist = Math.max(dist, minDistForFocal());
                    selectedTile = -1;
                    hoveredTile = -1;
                }
            }
        });
    }
    private void precomputeColors(int pi) {
        Planet p = solarSystem.get(pi);
        Polyhedron mesh = p.baseMesh();
        float[][] fc = faceColors[pi];
        long seed = 0x5EED1234L + pi * 0x1234567L;
        var noise = new Noise3(seed);
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            float cx = 0, cy = 0, cz = 0;
            for (int v : fv) { cx += mesh.vertices[v][0]; cy += mesh.vertices[v][1]; cz += mesh.vertices[v][2]; }
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            cx /= len; cy /= len; cz /= len;
            float lat = Math.abs(cy);
            float lon = (float) Math.atan2(cz, cx);
            float n = noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f);
            n = clamp(n / 0.94f, 0, 1);
            float r, g, b;
            float[] tint = PLANET_TINT[pi];
            switch (pi) {
                case 0: { // 恒星：米粒组织 + 黑子 + 色球层色变
                    // 米粒组织：高频噪点模拟对流胞
                    float granule = noise.fbm(cx * 8f + 3.7f, cy * 8f - 1.2f, cz * 8f + 5.3f);
                    granule = clamp(granule / 0.85f, 0, 1);
                    // 低频大尺度亮度变化（活动区）
                    float activity = noise.fbm(cx * 2.5f + 10f, cy * 2.5f + 20f, cz * 2.5f);
                    activity = clamp(activity / 0.90f, 0, 1);
                    // 黑子
                    float spot = noise.fbm(cx * 6f - 5f, cy * 6f + 3f, cz * 6f + 1f);
                    boolean isSpot = spot > 0.72f && activity < 0.45f;
                    if (isSpot) {
                        float sf = (spot - 0.72f) / 0.28f;
                        r = 0.55f + 0.15f * sf;
                        g = 0.25f + 0.10f * sf;
                        b = 0.08f + 0.05f * sf;
                    } else {
                        // 正常表面：黄→橙渐变 + 米粒明暗
                        float brightness = 0.75f + 0.25f * granule + 0.10f * (activity - 0.5f);
                        r = Math.min(1f, 0.95f * brightness + 0.05f);
                        g = Math.min(1f, 0.72f * brightness + 0.05f);
                        b = Math.min(1f, 0.28f * brightness + 0.02f);
                    }
                    break;
                }
                case 3: { // 地球：保留原有的海洋/大陆/极冰
                    if (n < 0.5f) { float t = n / 0.5f; r = 0.05f + 0.04f * t; g = 0.16f + 0.30f * t; b = 0.40f + 0.40f * t; }
                    else { float t = (n - 0.5f) / 0.5f; r = 0.24f + 0.42f * t; g = 0.45f - 0.10f * t; b = 0.18f - 0.10f * t; }
                    if (lat > 0.80f) { float t2 = (lat - 0.80f) / 0.20f; r = r + (1 - r) * t2; g = g + (1 - g) * t2; b = b + (1 - b) * t2; }
                    break;
                }
                case 8: { // 木星：色带 + 大红斑
                    float band = (float) Math.sin(lat * 28f) * 0.5f + 0.5f;
                    float storm = noise.fbm(cx * 5f, cy * 8f, cz * 5f) * 0.3f;
                    r = tint[0] * (0.70f + 0.30f * band + storm);
                    g = tint[1] * (0.65f + 0.35f * band);
                    b = tint[2] * (0.55f + 0.45f * band);
                    float spot = noise.fbm(cx * 3f + 1.5f, cy * 3f - 0.4f, cz * 3f);
                    if (spot > 0.6f && lat < 0.4f && lat > 0.1f) {
                        float sf = (spot - 0.6f) / 0.4f;
                        r = r + (0.85f - r) * sf * 0.7f;
                        g = g + (0.30f - g) * sf * 0.5f;
                        b = b + (0.15f - b) * sf * 0.3f;
                    }
                    break;
                }
                case 13: { // 土星：柔和色带
                    float band = (float) Math.sin(lat * 20f) * 0.5f + 0.5f;
                    r = tint[0] * (0.80f + 0.20f * band) + n * 0.08f;
                    g = tint[1] * (0.78f + 0.22f * band) + n * 0.06f;
                    b = tint[2] * (0.70f + 0.30f * band) + n * 0.04f;
                    break;
                }
                case 16: case 17: { // 天王星 / 海王星：平滑淡色
                    float band = (float) Math.sin(lat * 12f + lon * 2f) * 0.15f;
                    r = tint[0] + band;
                    g = tint[1] + band;
                    b = tint[2] + band + n * 0.05f;
                    break;
                }
                default: {
                    // 岩质/冰质天体：以各自 tint 为基色，不再套地球海洋/大陆
                    float v = 0.70f + n * 0.55f;
                    r = tint[0] * v;
                    g = tint[1] * v;
                    b = tint[2] * v;
                    if (pi == 1) { // 水星：灰褐色，撞击坑暗斑
                        float dark = noise.fbm(cx * 4f, cy * 4f, cz * 4f);
                        r *= 0.92f - dark * 0.18f;
                        g *= 0.92f - dark * 0.18f;
                        b *= 0.92f - dark * 0.18f;
                    } else if (pi == 2) { // 金星：奶油黄云层
                        float swirl = noise.fbm(cx * 3f + 7f, cy * 3f + 3f, cz * 3f + 9f);
                        r = tint[0] * (0.80f + 0.30f * swirl);
                        g = tint[1] * (0.80f + 0.25f * swirl);
                        b = tint[2] * (0.75f + 0.20f * swirl);
                    } else if (pi == 4) { // 月球：灰色，陨石坑暗色
                        float dark = n * n;
                        r = tint[0] * (1f - dark * 0.30f);
                        g = tint[1] * (1f - dark * 0.30f);
                        b = tint[2] * (1f - dark * 0.30f);
                    } else if (pi == 5) { // 火星：红橙色 + 暗区 + 极冠
                        float dark = noise.fbm(cx * 3f, cy * 3f, cz * 3f);
                        r = tint[0] * (0.75f + n * 0.25f - dark * 0.20f);
                        g = tint[1] * (0.60f + n * 0.20f - dark * 0.15f);
                        b = tint[2] * (0.50f + n * 0.20f - dark * 0.10f);
                        if (lat > 0.85f) {
                            float t2 = (lat - 0.85f) / 0.15f;
                            r += (1 - r) * t2; g += (1 - g) * t2; b += (1 - b) * t2;
                        }
                    } else if (pi == 9) { // 木卫一：硫磺黄橙
                        float patch = noise.fbm(cx * 3f, cy * 3f, cz * 3f);
                        r = 0.90f - patch * 0.20f;
                        g = 0.70f + patch * 0.10f;
                        b = 0.20f + patch * 0.10f;
                    } else if (pi == 10) { // 木卫二：冰白 + 棕色裂缝
                        r = 0.85f + 0.10f * n;
                        g = 0.88f + 0.08f * n;
                        b = 0.92f;
                        float crack = noise.fbm(cx * 5f, cy * 5f, cz * 5f);
                        if (crack > 0.60f) {
                            float t2 = (crack - 0.60f) / 0.40f;
                            r -= 0.20f * t2; g -= 0.15f * t2; b -= 0.10f * t2;
                        }
                    } else if (pi == 11) { // 木卫三：灰褐斑驳
                        r = tint[0] * (0.70f + n * 0.30f);
                        g = tint[1] * (0.70f + n * 0.30f);
                        b = tint[2] * (0.70f + n * 0.30f);
                    } else if (pi == 12) { // 木卫四：深灰褐，撞击坑
                        float dark = noise.fbm(cx * 4f, cy * 4f, cz * 4f);
                        r = tint[0] * (0.60f + n * 0.40f - dark * 0.15f);
                        g = tint[1] * (0.60f + n * 0.40f - dark * 0.15f);
                        b = tint[2] * (0.60f + n * 0.40f - dark * 0.15f);
                    } else if (pi == 14) { // 土卫六：橙色雾霾
                        r = tint[0] * (0.70f + n * 0.30f);
                        g = tint[1] * (0.70f + n * 0.30f);
                        b = tint[2] * (0.70f + n * 0.30f);
                    } else if (pi == 15) { // 土卫二：亮白冰面
                        r = 0.90f; g = 0.90f; b = 0.92f;
                    } else if (pi == 18) { // 冥王星：棕褐色 + 亮心形区域
                        r = tint[0] * (0.80f + n * 0.20f);
                        g = tint[1] * (0.80f + n * 0.20f);
                        b = tint[2] * (0.80f + n * 0.20f);
                        float heart = noise.fbm(cx * 2f + 1f, cy * 2f - 0.3f, cz * 2f);
                        if (heart > 0.62f) {
                            float t2 = (heart - 0.62f) / 0.38f;
                            r += (0.95f - r) * t2; g += (0.92f - g) * t2; b += (0.85f - b) * t2;
                        }
                    } else if (pi == 19) { // 卡戎：灰色
                        r = tint[0] * (0.70f + n * 0.30f);
                        g = tint[1] * (0.70f + n * 0.30f);
                        b = tint[2] * (0.70f + n * 0.30f);
                    }
                    break;
                }
            }
            fc[f][0] = clamp(r, 0, 1);
            fc[f][1] = clamp(g, 0, 1);
            fc[f][2] = clamp(b, 0, 1);
        }
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; } else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return new float[]{r + m, g + m, b + m};
    }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static float easeOutCubic(float t) { return 1f - (1f - t) * (1f - t) * (1f - t); }
    private static class RenderTask {
        final int pi; final String type; final float layerR, selfRot, camDist, dwx, dwz;
        final Polyhedron mesh;
        RenderTask(int pi, String type, float layerR, float selfRot, float camDist, float dwx, float dwz, Polyhedron mesh) {
            this.pi = pi; this.type = type; this.layerR = layerR; this.selfRot = selfRot; this.camDist = camDist; this.dwx = dwx; this.dwz = dwz; this.mesh = mesh;
        }
    }
    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1.0e9f;
        lastNano = now;
        if (dt > 0.1f) dt = 0.1f;
        simTime += dt;
        float[] wp = solarSystem.worldPos(focalIndex, simTime);
        dist = Math.max(dist, minDistForFocal());
        // 科技层/网格层随最近距离淡入淡出
        float fadeTarget = computeOverlayFadeTarget();
        overlayFade += (fadeTarget - overlayFade) * Math.min(1f, dt * 10f);
        // 摄像机焦点过渡动画（点击行星后平滑飞过去）
        if (camTransT < 1.0f) {
            camTransT = Math.min(1.0f, camTransT + dt / 0.55f);
            float t = camTransT;
            float s = t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            focalX = camFromX + (wp[0] - camFromX) * s;
            focalZ = camFromZ + (wp[2] - camFromZ) * s;
        } else {
            focalX = wp[0]; focalZ = wp[2];
        }
        GuiGraphics g = guiContext.graphics;
        int vx = (int) getPositionX(), vy = (int) getPositionY();
        int vw = (int) getSizeWidth(), vh = (int) getSizeHeight();
        g.fill(vx, vy, vx + vw, vy + vh, 0xFF020208);
        g.flush();
        drawStarfield(g, vx, vy, vw, vh);
        float cx = vx + vw / 2f, cy = vy + vh / 2f;
        float focalDesired = Math.min(vw, vh) * 0.9f;
        float fov = 2f * (float) Math.atan((vh / 2f) / focalDesired);
        float focal = (vh / 2f) / (float) Math.tan(fov / 2f);
        lastFocal = focal; lastCx = cx; lastCy = cy;
        lastMouseX = guiContext.mouseX; lastMouseY = guiContext.mouseY;
        float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        float cosX = (float) Math.cos(pitch), sinX = (float) Math.sin(pitch);
        Matrix4f idMat = new Matrix4f();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix(); mvs.identity(); RenderSystem.applyModelViewMatrix();
        Matrix4f proj = new Matrix4f().perspective(fov, (float) vw / (float) vh, 0.01f, 3000f);
        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
        RenderSystem.clearDepth(1.0f); RenderSystem.clear(0x100, false);
        List<RenderTask> tasks = new ArrayList<>();
        for (int pi = 0; pi < solarSystem.size(); pi++) {
            float[] pos = solarSystem.worldPos(pi, simTime);
            float dwx = pos[0] - focalX, dwz = pos[2] - focalZ;
            float rz1 = -dwx * sinY + dwz * cosY;
            float camDist = dist - rz1 * cosX;
            Planet p2 = solarSystem.get(pi);
            for (PlanetLayer layer : p2.layers()) {
                if (!layer.visible()) continue;
                // 网格（线框）只在焦点行星显示
                if (layer.type() == PlanetLayerType.WIREFRAME && pi != focalIndex) continue;
                tasks.add(new RenderTask(pi, layer.type().name(), layer.radius(), p2.resolveRotationSpeed(layer), camDist, dwx, dwz, p2.resolveGeometry(layer)));
            }
        }
        tasks.sort(Comparator.comparingDouble(t -> -t.camDist));
        for (RenderTask t : tasks) if (t.type.equals("BASE") || t.type.equals("CLOUD")) drawLayer(idMat, t, cosY, sinY, cosX, sinX, focal, cx, cy);
        drawOrbitalRings(idMat, cosY, sinY, cosX, sinX, focal, cx, cy);
        drawBeltBand(idMat, 50f, 65f, 5, cosY, sinY, cosX, sinX, 0.60f, 0.50f, 0.40f, 0.10f);
        drawBeltBand(idMat, 230f, 280f, 4, cosY, sinY, cosX, sinX, 0.45f, 0.50f, 0.60f, 0.06f);
        drawScatteredRocks(idMat, asteroidPos, cosY, sinY, cosX, sinX);
        drawScatteredRocks(idMat, kuiperPos, cosY, sinY, cosX, sinX);
        RenderSystem.depthMask(false);
        for (RenderTask t : tasks) if (!t.type.equals("BASE") && !t.type.equals("CLOUD") && !t.type.equals("TECH")) drawLayer(idMat, t, cosY, sinY, cosX, sinX, focal, cx, cy);
        RenderSystem.depthMask(false);
        boolean hasWireframe = false;
        boolean hasTech = false;
        for (PlanetLayer l : solarSystem.get(focalIndex).layers()) {
            if (l.type() == PlanetLayerType.WIREFRAME && l.visible()) hasWireframe = true;
            if (l.type() == PlanetLayerType.TECH) hasTech = true;
        }
        if (hasWireframe) {
            // 预计算拾取网格的屏幕坐标（与 PolyhedronView 一致：每帧一次，拾取直接用）
            Planet fp = solarSystem.get(focalIndex);
            PlanetLayer gridL = gridLayer(fp);
            float pickR = gridL != null ? gridL.radius() : 0;
            tileMesh = gridL != null ? fp.resolveGeometry(gridL) : fp.baseMesh();
            float selfAngle = (gridL != null ? fp.resolveRotationSpeed(gridL) : 0) * simTime;
            float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);
            float[] wp2 = solarSystem.worldPos(focalIndex, simTime);
            float pdwx = wp2[0] - focalX, pdwz = wp2[2] - focalZ;
            currentTilt = fp.axialTilt();
            int nv = tileMesh.vertices.length;
            tileSx = new float[nv]; tileSy = new float[nv]; tileZ = new float[nv]; tileDist = dist;
            for (int i = 0; i < nv; i++) {
                float[] cam = camera(tileMesh.vertices[i], pickR, pdwx, pdwz, sc, ss, cosY, sinY, cosX, sinX);
                float[] scr = screen(cam, focal, cx, cy);
                tileSx[i] = scr[0]; tileSy[i] = scr[1]; tileZ[i] = cam[2];
            }
            if (hasTech) drawTechMarkers(g, idMat, cosY, sinY, cosX, sinX, focal, cx, cy);
            updateHover(guiContext);
            drawTechHighlight(idMat, cosY, sinY, cosX, sinX, focal, cx, cy);
        } else { hoveredTile = -1; hoverAlpha = 0; tileMesh = null; }
        RenderSystem.setProjectionMatrix(oldProj, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
        mvs.popMatrix(); RenderSystem.applyModelViewMatrix(); RenderSystem.setShaderColor(1, 1, 1, 1);
        // drawLabels 暂时禁用，后续做附加UI时再启用
    }
    private void drawStarfield(GuiGraphics g, int vx, int vy, int vw, int vh) {
        // TODO: 加载天空盒贴图后替换
        if (skyLoc != null) {
            float uOff = -yaw / (2.0f * (float) Math.PI) * SKY_W;
            float vOff = -pitch / (float) Math.PI * SKY_H;
            float zoom = 1.1f;
            int drawH = (int)(vh * zoom);
            int drawW = (int)(drawH * 2.0f);
            int dx0 = vx + (vw - drawW) / 2;
            int dy0 = vy + (vh - drawH) / 2;
            for (int tile = -1; tile <= 1; tile++) {
                g.blit(skyLoc, dx0 + tile * drawW, dy0, uOff + tile * SKY_W, vOff, drawW, drawH, SKY_W, SKY_H);
            }
        }
    }
    private void drawSunGlow(Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focalLength) {
        int sunIdx = 0;
        float[] pos = solarSystem.worldPos(sunIdx, simTime);
        float dwx = pos[0] - focalX, dwz = pos[2] - focalZ;
        float rx = dwx * cosY + dwz * sinY;
        float rz1 = -dwx * sinY + dwz * cosY;
        float ry2 = -rz1 * sinX;
        float rz = rz1 * cosX;
        float camZ = rz - dist;
        if (camZ > -0.1f) return;
        float sunR = 0;
        for (PlanetLayer l : solarSystem.get(sunIdx).layers()) if (l.type() == PlanetLayerType.BASE) sunR = l.radius();
        if (sunR < 0.01f) return;
        // 太阳球心在相机空间的坐标（与渲染/拾取同一套变换）
        // 光晕盘放在太阳前表面处，并保持深度测试开启 —— 这样光晕会被其他行星遮挡，不会穿透
        float gx = rx, gy = ry2, gz = camZ + sunR * 0.85f;
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(0x0302, 1, 0x0302, 1);
        RenderSystem.depthMask(false);
        RenderSystem.enableDepthTest();
        // 单层径向渐变光晕：中心亮、边缘透明，避免多层同心圆
        float gr = sunR * 2.0f; // 太阳变大后光晕不需要 6 倍半径，避免吞没内行星
        float alpha = 0.40f;
        float cr = 1.0f, cg = 0.90f, cb = 0.65f;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(mat, gx, gy, gz).setColor(cr, cg, cb, alpha);
        int seg = 48;
        for (int j = 0; j <= seg; j++) {
            float a = (float)(Math.PI * 2 * j / seg);
            bb.addVertex(mat, gx + (float) Math.cos(a) * gr, gy + (float) Math.sin(a) * gr, gz)
               .setColor(cr * 0.25f, cg * 0.25f, cb * 0.25f, 0);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        RenderSystem.blendFunc(0x0302, 0x0303);
        RenderSystem.depthMask(true);
    }
    private void drawOrbitalRings(Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        // 轨道：用连续的 TRIANGLE_STRIP 环带，每点沿切线垂直方向偏移，横截面连续一致
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.depthMask(false); RenderSystem.enableDepthTest();
        int segments = 256;
        for (int pi = 0; pi < solarSystem.size(); pi++) {
            Planet p = solarSystem.get(pi);
            float orbR = p.orbitalRadius();
            if (orbR < 0.01f) continue;
            float alpha = (pi == focalIndex) ? 0.90f : 0.55f;
            float[] rgb = (pi == focalIndex) ? new float[]{0.50f, 0.78f, 1.0f} : new float[]{0.45f, 0.62f, 0.85f};
            // 线宽随轨道半径增大，保证远近轨道在屏幕上都有 1~3px
            boolean isMoon = p.parentId() >= 0;
              float hw = isMoon ? (0.03f + orbR * 0.0004f) : (0.06f + orbR * 0.0006f);
            // 预计算相机空间点（闭合环：多算两个点用于首尾衔接）
            int n = segments + 1;
            float[] px = new float[n + 1], py = new float[n + 1], pz = new float[n + 1];
            boolean[] valid = new boolean[n + 1];
            // 卫星轨道围绕母星，行星轨道围绕太阳
            float orbitCx = 0, orbitCz = 0;
            if (p.parentId() >= 0) {
                float[] pp = solarSystem.worldPos(p.parentId(), simTime);
                orbitCx = pp[0]; orbitCz = pp[2];
            }
            for (int i = 0; i < n; i++) {
                float angle = (float) Math.PI * 2 * i / segments;
                float wx = orbitCx + (float) Math.cos(angle) * orbR;
                float wz = orbitCz + (float) Math.sin(angle) * orbR;
                float dwx = wx - focalX, dwz = wz - focalZ;
                float rx = dwx * cosY + dwz * sinY;
                float rz1 = -dwx * sinY + dwz * cosY;
                float ry2 = -rz1 * sinX; float rz = rz1 * cosX;
                float pzc = rz - dist;
                px[i] = rx; py[i] = ry2; pz[i] = pzc;
                valid[i] = pzc < -0.05f;
            }
            // 闭合：第 n 个点 = 第 0 个点
            px[n] = px[0]; py[n] = py[0]; pz[n] = pz[0]; valid[n] = valid[0];
            // 预计算每个点的左右偏移（切线在相机空间 xy 平面的垂直方向）
            float[] lx = new float[n + 1], ly = new float[n + 1], rx2 = new float[n + 1], ry2 = new float[n + 1];
            for (int i = 0; i <= n; i++) {
                int prev = (i == 0) ? n - 1 : i - 1;
                int next = (i == n) ? 1 : i + 1;
                float dx = px[next] - px[prev], dy = py[next] - py[prev];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1e-4f) { lx[i] = ly[i] = rx2[i] = ry2[i] = 0; continue; }
                float nx = -dy / len * hw, ny = dx / len * hw;
                lx[i] = px[i] - nx; ly[i] = py[i] - ny;
                rx2[i] = px[i] + nx; ry2[i] = py[i] + ny;
            }
            // 分连续段绘制 TRIANGLE_STRIP（相机后方的点整段跳过，前方各段完整显示）
            for (int start = 0; start <= n; ) {
                if (!valid[start]) { start++; continue; }
                int end = start;
                while (end + 1 <= n && valid[end + 1]) end++;
                if (end > start) {
                    BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
                    for (int i = start; i <= end; i++) {
                        bb.addVertex(mat, lx[i], ly[i], pz[i]).setColor(rgb[0], rgb[1], rgb[2], alpha);
                        bb.addVertex(mat, rx2[i], ry2[i], pz[i]).setColor(rgb[0], rgb[1], rgb[2], alpha);
                    }
                    BufferUploader.drawWithShader(bb.buildOrThrow());
                }
                start = end + 1;
            }
        }
        RenderSystem.depthMask(true);
    }
    /** Compute moon shadow on planet surface vertex. Returns 0 (no shadow) to 1 (full shadow). */
    /** Quick check: does planet pi have any shadow-casting body (moon or parent)? */
    /** Draw a translucent flat ring band (background layer for belts) */
    private void drawBeltBand(Matrix4f mat, float innerR, float outerR, int bands,
            float cosY, float sinY, float cosX, float sinX,
            float cr, float cg, float cb, float baseAlpha) {
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false); RenderSystem.enableDepthTest();
        float savedTilt = currentTilt; currentTilt = 0;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float[] c00 = new float[3], c01 = new float[3], c11 = new float[3], c10 = new float[3];
        float[] p00 = new float[3], p01 = new float[3], p11 = new float[3], p10 = new float[3];
        int segs = 128;
        for (int b = 0; b < bands; b++) {
            float t0 = (float) b / bands, t1 = (float) (b + 1) / bands;
            float r0 = innerR + (outerR - innerR) * t0;
            float r1 = innerR + (outerR - innerR) * t1;
            float alpha = baseAlpha * (1f - 0.3f * Math.abs(t0 + t1 - 1f));
            for (int s = 0; s < segs; s++) {
                float a0 = (float) Math.PI * 2 * s / segs;
                float a1 = (float) Math.PI * 2 * (s + 1) / segs;
                float x0 = (float) Math.cos(a0), z0 = (float) Math.sin(a0);
                float x1 = (float) Math.cos(a1), z1 = (float) Math.sin(a1);
                float ddx = -focalX, ddz = -focalZ;
                p00[0] = x0 * r0; p00[1] = 0; p00[2] = z0 * r0;
                p01[0] = x0 * r1; p01[1] = 0; p01[2] = z0 * r1;
                p11[0] = x1 * r1; p11[1] = 0; p11[2] = z1 * r1;
                p10[0] = x1 * r0; p10[1] = 0; p10[2] = z1 * r0;
                cameraTo(c00, p00, 1, ddx, ddz, 1, 0, cosY, sinY, cosX, sinX);
                cameraTo(c01, p01, 1, ddx, ddz, 1, 0, cosY, sinY, cosX, sinX);
                cameraTo(c11, p11, 1, ddx, ddz, 1, 0, cosY, sinY, cosX, sinX);
                cameraTo(c10, p10, 1, ddx, ddz, 1, 0, cosY, sinY, cosX, sinX);
                if (c00[2] > 0 && c01[2] > 0 && c11[2] > 0 && c10[2] > 0) continue;
                bb.addVertex(mat, c00[0], c00[1], c00[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c01[0], c01[1], c01[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c11[0], c11[1], c11[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c00[0], c00[1], c00[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c11[0], c11[1], c11[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c10[0], c10[1], c10[2]).setColor(cr, cg, cb, alpha);
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
        currentTilt = savedTilt;
        RenderSystem.depthMask(true);
    }
    /** Render 3D rock polyhedra scattered in a belt — filled faces + wireframe edges */
    /** Render 3D rock polyhedra scattered in a belt — 真3D: cameraTo() + GPU透视投影 */
    private void drawScatteredRocks(Matrix4f mat, float[][] particles,
            float cosY, float sinY, float cosX, float sinX) {
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true); RenderSystem.enableDepthTest();
        float savedTilt = currentTilt; currentTilt = 0;
        // mesh selected per-rock below
        float[] cam = new float[3];
        // 第一遍：填充面
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < particles.length; i++) {
            float[] p = particles[i];
            float angle = p[0] + simTime * 0.006f;
            float radius = p[1], yPos = p[2], sz = p[3];
            float tiltA = p[4], tiltB = p[5];
            float cr = p[6], cg = p[7], cb = p[8];
            int seed = (int) p[8]; Polyhedron mesh = rockCache.getOrDefault((long)seed, null);
            if (mesh == null) { mesh = Polyhedron.rock((long)seed, 0.8f); rockCache.put((long)seed, mesh); }
            float dwx = (float) Math.cos(angle) * radius - focalX;
            float dwz = (float) Math.sin(angle) * radius - focalZ;
            lighting.updateForWorldPos(dwx + focalX, dwz + focalZ, cosY, sinY, cosX, sinX);
            float cosTA = (float) Math.cos(tiltA), sinTA = (float) Math.sin(tiltA);
            float cosTB = (float) Math.cos(tiltB), sinTB = (float) Math.sin(tiltB);
            for (int f = 0; f < mesh.faces.length; f++) {
                int[] fv = mesh.faces[f];
                // 计算面法线用于光照
                float fnx = 0, fny = 0, fnz = 0;
                for (int v : fv) { fnx += mesh.vertices[v][0]; fny += mesh.vertices[v][1]; fnz += mesh.vertices[v][2]; }
                float fnLen = (float) Math.sqrt(fnx*fnx + fny*fny + fnz*fnz);
                if (fnLen > 1e-5f) { fnx /= fnLen; fny /= fnLen; fnz /= fnLen; }
                // 碎石自转
                float rfnx = fnx * cosTA - fny * sinTA;
                float rfny = fnx * sinTA + fny * cosTA;
                float rfnz = fnz;
                float nwx = rfnx * cosTB + rfnz * sinTB;
                float nwy = rfny;
                float nwz = -rfnx * sinTB + rfnz * cosTB;
                float ndotl = nwx * lighting.dirX() + nwy * lighting.dirY() + nwz * lighting.dirZ();
                float lit = PlanetLighting.AMBIENT + (1 - PlanetLighting.AMBIENT) * lighting.direct(ndotl, 0);
                lit = Math.max(0.25f, lit);
                float lr = cr * lit, lg = cg * lit, lb = cb * lit;
                // 用 cameraTo() 投影每个顶点 — 跟行星完全一致的真3D管线
                boolean behind = false;
                float[] vsx = new float[fv.length], vsy = new float[fv.length], vsz = new float[fv.length];
                for (int v = 0; v < fv.length; v++) {
                    float[] vtx = mesh.vertices[fv[v]];
                    // apply tiltA then tiltB
                    float tlx = vtx[0] * sz, tly = vtx[1] * sz, tlz = vtx[2] * sz;
                    float tlx2 = tlx * cosTA - tly * sinTA, tly2 = tlx * sinTA + tly * cosTA;
                    float flx = tlx2 * cosTB + tlz * sinTB;
                    float fly = tly2;
                    float flz = -tlx2 * sinTB + tlz * cosTB;
                    // build a fake planet layer vertex for cameraTo()
                    // cameraTo needs: v[0]=lx, v[1]=ly, v[2]=lz, layerR=1, dwx, dwz, sc=cos(rotAngle), ss=sin(rotAngle)
                    float[] localV = { flx, fly + yPos, flz };
                    cameraTo(cam, localV, 1f, dwx, dwz, 1f, 0f, cosY, sinY, cosX, sinX);
                    vsx[v] = cam[0]; vsy[v] = cam[1]; vsz[v] = cam[2];
                    if (cam[2] > -0.02f) behind = true;
                }
                if (behind) continue;
                for (int v = 1; v + 1 < fv.length; v++) {
                    bb.addVertex(mat, vsx[0], vsy[0], vsz[0]).setColor(lr, lg, lb, 1f);
                    bb.addVertex(mat, vsx[v], vsy[v], vsz[v]).setColor(lr, lg, lb, 1f);
                    bb.addVertex(mat, vsx[v+1], vsy[v+1], vsz[v+1]).setColor(lr, lg, lb, 1f);
                }
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);

        currentTilt = savedTilt;
        RenderSystem.depthMask(true);
    }
    private float minDistForFocal() {
        Planet fp = solarSystem.get(focalIndex);
        float r = 0;
        for (PlanetLayer l : fp.layers()) if (l.type() == PlanetLayerType.BASE) r = l.radius();
        if (r < 0.01f) r = 1.0f;
        return Math.max(1.5f, r * 1.6f + 0.5f);
    }
    private float computeOverlayFadeTarget() {
        Planet fp = solarSystem.get(focalIndex);
        float baseR = 0;
        for (PlanetLayer l : fp.layers()) if (l.type() == PlanetLayerType.BASE) baseR = l.radius();
        if (baseR < 0.01f) baseR = 1f;
        float farD = baseR * 6f + 1f;
        float nearD = baseR * 2f + 0.5f;
        float t = (farD - dist) / (farD - nearD);
        t = clamp(t, 0, 1);
        return t * t * (3f - 2f * t);
    }
    private void drawLayer(Matrix4f mat, RenderTask t, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        Planet p = solarSystem.get(t.pi);
        currentTilt = p.axialTilt();
        lighting.updateForBody(solarSystem, t.pi, simTime, cosY, sinY, cosX, sinX);
        Polyhedron mesh = t.mesh;
        float selfAngle = t.selfRot * simTime;
        float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);
        if (t.type.equals("WIREFRAME")) { drawWireframe(mat, mesh, t.layerR, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy, 0.35f * overlayFade); return; }
        if (t.type.equals("CLOUD")) { drawCloudLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy); return; }
        if (t.type.equals("ATMOSPHERE")) { drawAtmosphereLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy); return; }
        if (t.type.equals("BASE")) { drawBaseLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX); return; }
        if (t.type.equals("RING")) { drawRing(mat, t, sc, ss, cosY, sinY, cosX, sinX); return; }
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            for (int j = 1; j + 1 < fv.length; j++) {
                for (int k : new int[]{0, j, j + 1}) {
                    float[] cam = camera(mesh.vertices[fv[k]], t.layerR, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
                    float[] c = hsvToRgb(((p.name().hashCode() >> 8) & 0xFF) / 255f * 360, 0.5f, 0.6f);
                    bb.addVertex(mat, cam[0], cam[1], cam[2]).setColor(c[0], c[1], c[2], 1f);
                }
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }
    private void drawBaseLayer(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        Polyhedron mesh = t.mesh;
        float[][] albedo = faceColors[t.pi];
        int n = mesh.vertices.length;
        int nf = mesh.faces.length;
        float[] bx = new float[n], by = new float[n], bz = new float[n];
        // Per-vertex lighting result (face-independent)
        float[] directV = new float[n], specV = new float[n];
        float[] rimWV = new float[n], rimCV = new float[n], shadowBV = new float[n], reflV = new float[n];
        float[] cam = new float[3];
        float[] centerCam = new float[3];
        SurfaceLight sl = new SurfaceLight();
        cameraTo(centerCam, new float[]{0, 0, 0}, 1, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
        boolean isSun = (t.pi == 0);
        for (int i = 0; i < n; i++) {
            cameraTo(cam, mesh.vertices[i], t.layerR, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
            bx[i] = cam[0]; by[i] = cam[1]; bz[i] = cam[2];
            float vnx = bx[i] - centerCam[0], vny = by[i] - centerCam[1], vnz = bz[i] - centerCam[2];
            float vlen = (float) Math.sqrt(vnx * vnx + vny * vny + vnz * vnz);
            if (vlen > 1e-5f) { vnx /= vlen; vny /= vlen; vnz /= vlen; }
            if (isSun) {
                directV[i] = 1f; specV[i] = 0; rimWV[i] = 0; rimCV[i] = 0; shadowBV[i] = 0; reflV[i] = 0;
                continue;
            }
            float ndotl = vnx * lighting.dirX() + vny * lighting.dirY() + vnz * lighting.dirZ();
            float shadow = shadowModel.hasShadow(t.pi)
                    ? shadowModel.occlusion(t.pi, mesh.vertices[i], t.layerR, sc, ss, currentTilt, simTime) : 0;
            // 母星反射光（地照）：世界方向 -> 相机空间
            float reflStrength = 0;
            float reflCx = 0, reflCy = 0, reflCz = 0;
            float[] reflWorld = new float[3];
            if (solarSystem.get(t.pi).parentId() >= 0) {
                reflStrength = shadowModel.parentReflection(t.pi, mesh.vertices[i], t.layerR, sc, ss, currentTilt, simTime, reflWorld);
                // rotate world direction to camera space (same as updateLightDir's camera rotation)
                float rrx = reflWorld[0] * cosY + reflWorld[2] * sinY;
                float rrz1 = -reflWorld[0] * sinY + reflWorld[2] * cosY;
                reflCy = reflWorld[1] * cosX - rrz1 * sinX;
                reflCz = reflWorld[1] * sinX + rrz1 * cosX;
                reflCx = rrx;
            }
            // 视线方向：表面 -> 相机（相机空间相机在原点）
            lighting.evaluate(vnx, vny, vnz, -bx[i], -by[i], -bz[i], shadow,
                    reflCx, reflCy, reflCz, reflStrength, sl);
            directV[i] = sl.direct; specV[i] = sl.specular;
            rimWV[i] = sl.rimWarm; rimCV[i] = sl.rimCool; shadowBV[i] = sl.shadowBlue;
            reflV[i] = sl.reflected;
        }
        // ---- emit triangles: per-face albedo -> polygon tile style ----
        boolean drew = false;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float[] colTmp = new float[3];
        for (int f = 0; f < nf; f++) {
            int[] fv = mesh.faces[f];
            float fcx = 0, fcy = 0, fcz = 0;
            for (int v : fv) { fcx += bx[v]; fcy += by[v]; fcz += bz[v]; }
            fcx /= fv.length; fcy /= fv.length; fcz /= fv.length;
            // Back-face culling for convex sphere: C*P <= |P|^2
            float dp = centerCam[0] * fcx + centerCam[1] * fcy + centerCam[2] * fcz;
            float pp = fcx * fcx + fcy * fcy + fcz * fcz;
            if (dp <= pp) continue;
            // Per-face albedo -> polygon tile style
            float[] alb = albedo[f];
            float ccR = 0, ccG = 0, ccB = 0;
            float[] vR = new float[fv.length], vG = new float[fv.length], vB = new float[fv.length];
            for (int k = 0; k < fv.length; k++) {
                int vi = fv[k];
                sl.set(directV[vi], specV[vi], rimWV[vi], rimCV[vi], shadowBV[vi], reflV[vi]);
                lighting.colorize(alb, sl, colTmp);
                vR[k] = colTmp[0]; vG[k] = colTmp[1]; vB[k] = colTmp[2];
                ccR += vR[k]; ccG += vG[k]; ccB += vB[k];
            }
            ccR /= fv.length; ccG /= fv.length; ccB /= fv.length;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                drew = true;
                bb.addVertex(mat, fcx, fcy, fcz).setColor(ccR, ccG, ccB, 1f);
                bb.addVertex(mat, bx[fv[k]], by[fv[k]], bz[fv[k]]).setColor(vR[k], vG[k], vB[k], 1f);
                bb.addVertex(mat, bx[fv[a1]], by[fv[a1]], bz[fv[a1]]).setColor(vR[a1], vG[a1], vB[a1], 1f);
            }
        }
        if (drew) BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    private void drawCloudLayer(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        Polyhedron mesh = t.mesh;
        // Per-layer noise seed (cached): same planet, different layer -> different pattern
        long layerSeed = 0x5EED1234L + t.pi * 0x1234567L + (long)(t.layerR * 1000) * 0x9E3779B9L;
        Noise3 layerNoise = layerNoiseCache.get(layerSeed);
        if (layerNoise == null) {
            layerNoise = new Noise3(layerSeed);
            layerNoiseCache.put(layerSeed, layerNoise);
        }
        // Per-layer density: each successive cloud layer is sparser
        int cloudIdx = 0;
        for (PlanetLayer cl : solarSystem.get(t.pi).layers())
            if (cl.type() == PlanetLayerType.CLOUD && cl.radius() < t.layerR - 1e-4f) cloudIdx++;
        float densityFactor = 0.42f + cloudIdx * 0.08f;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        boolean drew = false;
        float[] cxs = new float[6], cys = new float[6], czs = new float[6];
        float[] cam = new float[3];
        float[] cloudCenterCam = new float[3];
        cameraTo(cloudCenterCam, new float[]{0, 0, 0}, 1, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            float nx = 0, ny = 0, nz = 0;
            for (int k = 0; k < fv.length; k++) {
                float[] v = mesh.vertices[fv[k]];
                nx += v[0]; ny += v[1]; nz += v[2];
                cameraTo(cam, v, t.layerR, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
                cxs[k] = cam[0]; cys[k] = cam[1]; czs[k] = cam[2];
            }
            float nlen = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nlen < 1e-6f) continue;
            nx /= nlen; ny /= nlen; nz /= nlen;
            float lat = Math.abs(ny);
            float threshold = densityFactor + lat * 0.18f;
            float cloudVal = layerNoise.fbm(nx * 2.5f + 7.3f, ny * 2.5f + 13.7f, nz * 2.5f + 3.1f);
            if (cloudVal < threshold) continue;
            // backface culling for convex sphere: C·P <= |P|² means back-facing
            {
                float fcx2 = (cxs[0] + cxs[1] + cxs[2]) / 3f;
                float fcy2 = (cys[0] + cys[1] + cys[2]) / 3f;
                float fcz2 = (czs[0] + czs[1] + czs[2]) / 3f;
                float dp = cloudCenterCam[0] * fcx2 + cloudCenterCam[1] * fcy2 + cloudCenterCam[2] * fcz2;
                float pp = fcx2 * fcx2 + fcy2 * fcy2 + fcz2 * fcz2;
                if (dp <= pp) continue;
            }
            float nX = cxs[0] - cloudCenterCam[0] + cxs[1] - cloudCenterCam[0] + cxs[2] - cloudCenterCam[0];
            float nY = cys[0] - cloudCenterCam[1] + cys[1] - cloudCenterCam[1] + cys[2] - cloudCenterCam[1];
            float nZ = czs[0] - cloudCenterCam[2] + czs[1] - cloudCenterCam[2] + czs[2] - cloudCenterCam[2];
            float nLen = (float) Math.sqrt(nX * nX + nY * nY + nZ * nZ);
            if (nLen > 1e-5f) { nX /= nLen; nY /= nLen; nZ /= nLen; }
            float ndotlC = nX * lighting.dirX() + nY * lighting.dirY() + nZ * lighting.dirZ();
            float cloudShadow = shadowModel.hasShadow(t.pi)
                    ? shadowModel.occlusion(t.pi, mesh.vertices[mesh.faces[f][0]], t.layerR, sc, ss, currentTilt, simTime) : 0;
            float shade = PlanetLighting.AMBIENT + (1 - PlanetLighting.AMBIENT) * lighting.direct(ndotlC, cloudShadow);
            float fa = 0.55f * shade;
            float fcx = 0, fcy = 0, fcz = 0;
            for (int k = 0; k < fv.length; k++) { fcx += cxs[k]; fcy += cys[k]; fcz += czs[k]; }
            fcx /= fv.length; fcy /= fv.length; fcz /= fv.length;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                bb.addVertex(mat, fcx, fcy, fcz).setColor(0.95f, 0.97f, 1.0f, fa);
                bb.addVertex(mat, cxs[k], cys[k], czs[k]).setColor(1f, 1f, 1f, fa);
                bb.addVertex(mat, cxs[a1], cys[a1], czs[a1]).setColor(1f, 1f, 1f, fa);
            }
            drew = true;
        }
        if (drew) BufferUploader.drawWithShader(bb.buildOrThrow());
    }
    private float[] atmosphereColor(int pi) {
        return switch (pi) {
            case 0 -> new float[]{1.0f, 0.80f, 0.35f}; // 恒星：暖橙描边
            case 2 -> new float[]{0.90f, 0.80f, 0.50f}; // 金星：奶油黄
            case 3 -> new float[]{0.25f, 0.55f, 1.00f}; // 地球：蓝
            case 5 -> new float[]{0.80f, 0.50f, 0.35f}; // 火星：淡红
            case 8 -> new float[]{0.80f, 0.65f, 0.45f}; // 木星：暖褐
            case 13 -> new float[]{0.85f, 0.75f, 0.55f}; // 土星：淡金
            case 14 -> new float[]{0.85f, 0.55f, 0.25f}; // 土卫六：橙色
            case 16 -> new float[]{0.55f, 0.75f, 0.85f}; // 天王星：淡青
            case 17 -> new float[]{0.35f, 0.55f, 0.90f}; // 海王星：蓝
            default -> new float[]{0.45f, 0.60f, 0.90f};
        };
    }
    private void drawAtmosphereLayer(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        Polyhedron mesh = t.mesh;
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float[] cxs = new float[6], cys = new float[6], czs = new float[6];
        float[] cam = new float[3];
        float[] atmoCenterCam = new float[3];
        cameraTo(atmoCenterCam, new float[]{0, 0, 0}, 1, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
        float[] atmColor = atmosphereColor(t.pi);
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            for (int k = 0; k < fv.length; k++) {
                cameraTo(cam, mesh.vertices[fv[k]], t.layerR, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
                cxs[k] = cam[0]; cys[k] = cam[1]; czs[k] = cam[2];
            }
            float ccx = 0, ccy = 0, ccz = 0;
            for (int k = 0; k < fv.length; k++) { ccx += cxs[k]; ccy += cys[k]; ccz += czs[k]; }
            ccx /= fv.length; ccy /= fv.length; ccz /= fv.length;
            float anx = ccx - atmoCenterCam[0], any = ccy - atmoCenterCam[1], anz = ccz - atmoCenterCam[2];
            float anLen = (float) Math.sqrt(anx * anx + any * any + anz * anz);
            if (anLen < 1e-5f) continue;
            anx /= anLen; any /= anLen; anz /= anLen;
            float vnx = atmoCenterCam[0] - ccx, vny = atmoCenterCam[1] - ccy, vnz = atmoCenterCam[2] - ccz;
            float vLen = (float) Math.sqrt(vnx * vnx + vny * vny + vnz * vnz);
            if (vLen < 1e-5f) continue;
            vnx /= vLen; vny /= vLen; vnz /= vLen;
            float NdotV = Math.max(0, anx * vnx + any * vny + anz * vnz);
            float rim = 1f - NdotV; rim = rim * rim;
            float sunDot = Math.max(0, anx * lighting.dirX() + any * lighting.dirY() + anz * lighting.dirZ());
            float atmoShadow = shadowModel.hasShadow(t.pi)
                    ? shadowModel.occlusion(t.pi, mesh.faces[f].length > 0 ? mesh.vertices[mesh.faces[f][0]] : new float[]{0,0,0}, t.layerR, sc, ss, currentTilt, simTime) : 0;
            boolean isStar = solarSystem.get(t.pi).visual().isGlowing();
            float sunFactor = isStar ? 1f : sunDot * lighting.intensity() * (1f - atmoShadow);
            float alpha = isStar ? rim * 0.55f : rim * sunFactor * 0.35f;
            if (alpha < 0.003f) continue;
            float r = atmColor[0], g = atmColor[1], b = atmColor[2];
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                bb.addVertex(mat, ccx, ccy, ccz).setColor(r, g, b, alpha);
                bb.addVertex(mat, cxs[k], cys[k], czs[k]).setColor(r, g, b, alpha);
                bb.addVertex(mat, cxs[a1], cys[a1], czs[a1]).setColor(r, g, b, alpha);
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
        RenderSystem.defaultBlendFunc();
    }
    private void drawRing(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        // Ring: flat disc in equatorial plane (y=0), tilted with the planet's axial tilt
        float baseR = 0;
        for (PlanetLayer l : solarSystem.get(t.pi).layers()) if (l.type() == PlanetLayerType.BASE) baseR = l.radius();
        float innerR = Math.max(baseR * 1.15f, t.layerR * 0.65f);
        float outerR = t.layerR;
        int bands = 24;
        int segs = 96;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int b = 0; b < bands; b++) {
            float t0 = (float) b / bands, t1 = (float) (b + 1) / bands;
            float r0 = innerR + (outerR - innerR) * t0;
            float r1 = innerR + (outerR - innerR) * t1;
            // Cassini division gap (only Saturn has the prominent gap)
            float gap0 = 0.42f, gap1 = 0.50f;
            float alpha;
            if (t0 >= gap0 && t1 <= gap1) alpha = 0f;
            else if (t0 < gap0 && t1 > gap0) alpha = 0.15f;
            else if (t0 < gap1 && t1 > gap1) alpha = 0.15f;
            else {
                float mid = (t0 + t1) / 2f;
                alpha = 0.35f - 0.15f * Math.abs(mid - 0.3f);
            }
            // 冰巨行星的环更淡
            if (t.pi == 16 || t.pi == 17) alpha *= 0.55f;
            if (alpha < 0.01f) continue;
            float cr, cg, cb;
            if (t.pi == 13) { // 土星：暖金色
                cr = 0.82f + 0.08f * (float) Math.sin(t0 * 40f);
                cg = 0.72f + 0.06f * (float) Math.cos(t0 * 55f);
                cb = 0.55f + 0.10f * (float) Math.sin(t0 * 70f);
            } else if (t.pi == 16) { // 天王星：淡青灰
                cr = 0.55f; cg = 0.75f; cb = 0.82f;
            } else if (t.pi == 17) { // 海王星：蓝白
                cr = 0.45f; cg = 0.68f; cb = 0.92f;
            } else {
                cr = 0.75f; cg = 0.70f; cb = 0.65f;
            }
            float[] c00 = new float[3], c01 = new float[3], c11 = new float[3], c10 = new float[3];
            float[] p00 = new float[3], p01 = new float[3], p11 = new float[3], p10 = new float[3];
            for (int s = 0; s < segs; s++) {
                float a0 = (float) Math.PI * 2 * s / segs;
                float a1 = (float) Math.PI * 2 * (s + 1) / segs;
                float x0 = (float) Math.cos(a0), z0 = (float) Math.sin(a0);
                float x1 = (float) Math.cos(a1), z1 = (float) Math.sin(a1);
                p00[0] = x0 * r0; p00[1] = 0; p00[2] = z0 * r0;
                p01[0] = x0 * r1; p01[1] = 0; p01[2] = z0 * r1;
                p11[0] = x1 * r1; p11[1] = 0; p11[2] = z1 * r1;
                p10[0] = x1 * r0; p10[1] = 0; p10[2] = z1 * r0;
                cameraTo(c00, p00, 1, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
                cameraTo(c01, p01, 1, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
                cameraTo(c11, p11, 1, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
                cameraTo(c10, p10, 1, t.dwx, t.dwz, sc, ss, cosY, sinY, cosX, sinX);
                // Skip if entirely behind the camera
                if (c00[2] > 0 && c01[2] > 0 && c11[2] > 0 && c10[2] > 0) continue;
                bb.addVertex(mat, c00[0], c00[1], c00[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c01[0], c01[1], c01[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c11[0], c11[1], c11[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c00[0], c00[1], c00[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c11[0], c11[1], c11[2]).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, c10[0], c10[1], c10[2]).setColor(cr, cg, cb, alpha);
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
    }

    private void drawWireframe(Matrix4f mat, Polyhedron mesh, float layerR, float dwx, float dwz, float sc, float ss, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy, float alpha) {
        List<int[]> edges = edgeCache.computeIfAbsent(mesh, SolarSystemView::buildEdges);
        float hw = 0.005f;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float[] c0 = new float[3], c1 = new float[3];
        for (int[] e : edges) {
            cameraTo(c0, mesh.vertices[e[0]], layerR, dwx, dwz, sc, ss, cosY, sinY, cosX, sinX);
            cameraTo(c1, mesh.vertices[e[1]], layerR, dwx, dwz, sc, ss, cosY, sinY, cosX, sinX);
            addQuad3D(bb, mat, c0[0], c0[1], c0[2], c1[0], c1[1], c1[2], hw, 0.55f, 0.85f, 1.0f, alpha);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }
    private static boolean addQuad3D(BufferBuilder bb, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, float hw, float r, float g, float b, float a) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-4f) return false;
        float nx = -dy / len * hw, ny = dx / len * hw;
        bb.addVertex(m, x0 - nx, y0 - ny, z0).setColor(r, g, b, a);
        bb.addVertex(m, x0 + nx, y0 + ny, z0).setColor(r, g, b, a);
        bb.addVertex(m, x1 - nx, y1 - ny, z1).setColor(r, g, b, a);
        bb.addVertex(m, x0 + nx, y0 + ny, z0).setColor(r, g, b, a);
        bb.addVertex(m, x1 + nx, y1 + ny, z1).setColor(r, g, b, a);
        bb.addVertex(m, x1 - nx, y1 - ny, z1).setColor(r, g, b, a);
        return true;
    }
    private float[] camera(float[] v, float layerR, float dwx, float dwz, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        // 世界坐标（自转 + 轴倾角 + 行星位置偏移），与 PolyhedronView 相同的先 yaw 后 pitch 相机变换
        float lx = (v[0] * sc - v[2] * ss) * layerR;
        float lz = (v[0] * ss + v[2] * sc) * layerR;
        float ly = v[1] * layerR;
        float ct = (float) Math.cos(currentTilt), st = (float) Math.sin(currentTilt);
        float wx = lx * ct - ly * st + dwx;
        float wz = lz + dwz;
        float wy = lx * st + ly * ct;
        float rx = wx * cosY + wz * sinY;
        float rz1 = -wx * sinY + wz * cosY;
        float ry2 = wy * cosX - rz1 * sinX;
        float rz = wy * sinX + rz1 * cosX;
        return new float[]{rx, ry2, rz - dist};
    }
    private void cameraTo(float[] out, float[] v, float layerR, float dwx, float dwz, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        float lx = (v[0] * sc - v[2] * ss) * layerR;
        float lz = (v[0] * ss + v[2] * sc) * layerR;
        float ly = v[1] * layerR;
        float ct = (float) Math.cos(currentTilt), st = (float) Math.sin(currentTilt);
        float wx = lx * ct - ly * st + dwx;
        float wz = lz + dwz;
        float wy = lx * st + ly * ct;
        float rx = wx * cosY + wz * sinY;
        float rz1 = -wx * sinY + wz * cosY;
        float ry2 = wy * cosX - rz1 * sinX;
        float rz = wy * sinX + rz1 * cosX;
        out[0] = rx; out[1] = ry2; out[2] = rz - dist;
    }
    private float[] screen(float[] cam, float focal, float cx, float cy) {
        float d = Math.max(-cam[2], 0.15f);
        return new float[]{cx + cam[0] * focal / d, cy - cam[1] * focal / d, cam[2]};
    }
    private float[] screenToCam(float sx, float sy, float focal, float cx, float cy, float z) {
        float camX = (sx - cx) * (-z) / focal;
        float camY = -(sy - cy) * (-z) / focal;
        return new float[]{camX, camY, z};
    }
    private void drawTechMarkers(GuiGraphics g2, Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focalLength, float cx, float cy) {
        // 与 PolyhedronView 一致：科技项用 3D 半透明面 + 亮边绘制在 TECH 层
        Planet fp = solarSystem.get(focalIndex);
        // Unified: tech markers / wireframe / selection all use WIREFRAME's mesh+radius
        PlanetLayer gridL = gridLayer(fp);
        if (gridL == null) return;
        float gridR = gridL.radius();
        Polyhedron gridMesh = fp.resolveGeometry(gridL);
        float[] wp = solarSystem.worldPos(focalIndex, simTime);
        float dwx = wp[0] - focalX, dwz = wp[2] - focalZ;
        float selfAngle = fp.resolveRotationSpeed(gridL) * simTime;
        float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);
        currentTilt = fp.axialTilt();

        if (overlayFade < 0.01f) return;
        List<TechNode> focalNodes = fp.techNodes();
        // 兼容模式：如果星球没有配置专属科技项，则使用全局列表（旧行为）
        if (focalNodes.isEmpty() && !nodes.isEmpty()) focalNodes = nodes;
        int count = Math.min(gridMesh.faces.length, focalNodes.size());
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        boolean drew = false;
        for (int f = 0; f < count; f++) {
            int[] fv = gridMesh.faces[f];
            int col = tierColor(focalNodes.get(f).tier());
            float cr = ((col >> 16) & 0xFF) / 255f;
            float cg = ((col >> 8) & 0xFF) / 255f;
            float cb = (col & 0xFF) / 255f;
            // 面顶点相机坐标
            float[] px = new float[fv.length], py = new float[fv.length], pz = new float[fv.length];
            float ccx = 0, ccy = 0, ccz = 0;
            boolean behind = false;
            for (int k = 0; k < fv.length; k++) {
                float[] cam = camera(gridMesh.vertices[fv[k]], gridR, dwx, dwz, sc, ss, cosY, sinY, cosX, sinX);
                px[k] = cam[0]; py[k] = cam[1]; pz[k] = cam[2];
                ccx += cam[0]; ccy += cam[1]; ccz += cam[2];
                if (cam[2] > 0) behind = true;
            }
            if (behind) continue;
            ccx /= fv.length; ccy /= fv.length; ccz /= fv.length;
            // 半透明面
            float fa = 0.28f * overlayFade;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                bb.addVertex(mat, ccx, ccy, ccz).setColor(cr, cg, cb, fa);
                bb.addVertex(mat, px[k], py[k], pz[k]).setColor(cr, cg, cb, fa);
                bb.addVertex(mat, px[a1], py[a1], pz[a1]).setColor(cr, cg, cb, fa);
            }
            // 亮边
            float ea = 0.8f * overlayFade; float hw = 0.006f;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                addQuad3D(bb, mat, px[k], py[k], pz[k], px[a1], py[a1], pz[a1], hw, cr, cg, cb, ea);
            }
            drew = true;
        }
        if (drew) BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** Resolve the grid layer (WIREFRAME) used by wireframe / tech markers / picking / highlight. */
    private PlanetLayer gridLayer(Planet p) {
        for (PlanetLayer l : p.layers()) if (l.type() == PlanetLayerType.WIREFRAME) return l;
        return null;
    }

    private void updateHover(GUIContext ctx) {
        hoveredTile = -1;
        if (tileMesh == null || tileSx == null) return;
        // 科技层还没淡入到可见程度时，不允许盲点隐藏的科技项
        if (overlayFade < 0.5f) return;
        float mx = ctx.localMouseX, my = ctx.localMouseY;
        float bestDist = Float.MAX_VALUE;
        for (int f = 0; f < tileMesh.faces.length; f++) {
            int[] fv = tileMesh.faces[f];
            float avgZ = 0;
            for (int v : fv) avgZ += tileZ[v];
            avgZ /= fv.length;
            // Only pick front-facing faces: camera-z closer than sphere center means front
            if (avgZ < -tileDist) continue;
            // face center in screen space
            float fcx = 0, fcy = 0;
            for (int v : fv) { fcx += tileSx[v]; fcy += tileSy[v]; }
            fcx /= fv.length; fcy /= fv.length;
            float dx = mx - fcx, dy = my - fcy;
            float dist = dx * dx + dy * dy;
            // face radius in screen space (avg distance from center to vertices)
            float faceR2 = 0;
            for (int v : fv) {
                float vx = tileSx[v] - fcx, vy = tileSy[v] - fcy;
                faceR2 += vx * vx + vy * vy;
            }
            faceR2 /= fv.length;
            // allow mouse slightly outside the face for easier targeting
            if (dist < faceR2 * 1.8f && dist < bestDist) {
                bestDist = dist;
                hoveredTile = f;
            }
        }
    }
    // pointInPoly no longer used (replaced by distance-based above)

    private void drawTechHighlight(Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focalLength, float cx, float cy) {
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
        float fadeIn = 1f - (float) Math.pow(1f - Math.min(1f, elapsed / 0.12f), 3);
        hoverAlpha += ((active ? fadeIn : 0f) - hoverAlpha) * Math.min(1f, dt * (active ? 18f : 8f));
        if (hoverAlpha < 0.005f) { hoverAlpha = 0; chaseFace = -1; chaseActive = false; return; }
        float a = hoverAlpha * (0.85f + 0.15f * (float) Math.sin(elapsed * 4.5f)) * overlayFade;

        Planet fp = solarSystem.get(focalIndex);
        currentTilt = fp.axialTilt();
        PlanetLayer gridL = gridLayer(fp);
        if (gridL == null) return;
        float wireR = gridL.radius();
        Polyhedron mesh = fp.resolveGeometry(gridL);

        if (f >= 0 && f != chaseFace) {
            boolean firstEver = (chaseFace == -1);
            chaseFace = f; chaseActive = true; chaseFaceVerts = mesh.faces[f].length;
            int[] fv = mesh.faces[f];
            if (firstEver) {
                for (int i = 0; i < fv.length; i++) {
                    chaseWx[i] = mesh.vertices[fv[i]][0];
                    chaseWy[i] = mesh.vertices[fv[i]][1];
                    chaseWz[i] = mesh.vertices[fv[i]][2];
                }
                chaseWMx = 0; chaseWMy = 0; chaseWMz = 0;
                for (int v : fv) { chaseWMx += mesh.vertices[v][0]; chaseWMy += mesh.vertices[v][1]; chaseWMz += mesh.vertices[v][2]; }
                chaseWMx /= fv.length; chaseWMy /= fv.length; chaseWMz /= fv.length;
                if (fv.length < 6) { chaseWx[5] = chaseWMx; chaseWy[5] = chaseWMy; chaseWz[5] = chaseWMz; }
                chaseActive = false;
            }
        }
        if (f < 0) { chaseFace = -1; chaseActive = false; return; }
        if (chaseFace < 0) return;

        int[] fv = mesh.faces[chaseFace];
        int drawN = fv.length;

        if (chaseActive) {
            float ch = 1f - (float) Math.exp(-dt / CHASE_TAU);
            float maxD2 = 0;
            for (int i = 0; i < fv.length; i++) {
                float ttx = mesh.vertices[fv[i]][0];
                float tty = mesh.vertices[fv[i]][1];
                float ttz = mesh.vertices[fv[i]][2];
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
                chaseWx[i] = mesh.vertices[fv[i]][0];
                chaseWy[i] = mesh.vertices[fv[i]][1];
                chaseWz[i] = mesh.vertices[fv[i]][2];
            }
            chaseWMx = 0; chaseWMy = 0; chaseWMz = 0;
            for (int v : fv) { chaseWMx += mesh.vertices[v][0]; chaseWMy += mesh.vertices[v][1]; chaseWMz += mesh.vertices[v][2]; }
            chaseWMx /= fv.length; chaseWMy /= fv.length; chaseWMz /= fv.length;
            if (fv.length < 6) { chaseWx[5] = chaseWMx; chaseWy[5] = chaseWMy; chaseWz[5] = chaseWMz; }
        }

        float[] wp = solarSystem.worldPos(focalIndex, simTime);
        float dwx = wp[0] - focalX, dwz = wp[2] - focalZ;
        float selfAngle = fp.resolveRotationSpeed(gridL) * simTime;
        float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);

        float[] pcx = new float[6], pcy = new float[6], pcz = new float[6];
        for (int i = 0; i < drawN; i++) {
            float[] cam = camera(new float[]{chaseWx[i], chaseWy[i], chaseWz[i]}, wireR, dwx, dwz, sc, ss, cosY, sinY, cosX, sinX);
            pcx[i] = cam[0]; pcy[i] = cam[1]; pcz[i] = cam[2];
        }
        float[] cm = camera(new float[]{chaseWMx, chaseWMy, chaseWMz}, wireR, dwx, dwz, sc, ss, cosY, sinY, cosX, sinX);

        float cr, cg, cb;
        List<TechNode> hNodes = solarSystem.get(focalIndex).techNodes();
        if (hNodes.isEmpty() && !nodes.isEmpty()) hNodes = nodes;
        if (chaseFace >= 0 && chaseFace < hNodes.size()) {
            int col = tierColor(hNodes.get(chaseFace).tier());
            cr = ((col >> 16) & 0xFF) / 255f; cg = ((col >> 8) & 0xFF) / 255f; cb = (col & 0xFF) / 255f;
        } else if (chaseFace >= 0) {
            cr = Math.min(1f, faceColors[focalIndex][chaseFace][0] * 1.5f);
            cg = Math.min(1f, faceColors[focalIndex][chaseFace][1] * 1.5f);
            cb = Math.min(1f, faceColors[focalIndex][chaseFace][2] * 1.5f);
        } else { cr = 1; cg = 1; cb = 1; }

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float fa = 0.22f * a;
        if (fa > 0.01f) {
            for (int k = 0; k < drawN; k++) {
                int a1 = (k + 1) % drawN;
                bb.addVertex(mat, cm[0], cm[1], cm[2]).setColor(cr, cg, cb, fa);
                bb.addVertex(mat, pcx[k], pcy[k], pcz[k]).setColor(cr, cg, cb, fa);
                bb.addVertex(mat, pcx[a1], pcy[a1], pcz[a1]).setColor(cr, cg, cb, fa);
            }
        }
        float ea = 0.95f * a; float hw = 0.015f;
        for (int k = 0; k < drawN; k++) {
            int a1 = (k + 1) % drawN;
            addQuad3D(bb, mat, pcx[k], pcy[k], pcz[k], pcx[a1], pcy[a1], pcz[a1], hw, cr, cg, cb, ea);
        }
        float ca = 0.95f * a;
        for (int i = 0; i < drawN; i++) {
            int pv = (i - 1 + drawN) % drawN;
            int q = (i + 1) % drawN;
            appendCornerCap(bb, mat, pcx[i], pcy[i], pcz[i], pcx[pv], pcy[pv], pcz[pv], pcx[q], pcy[q], pcz[q], hw, cr, cg, cb, ca);
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
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
    private static void appendCornerCap(BufferBuilder bb, Matrix4f m,
                                        float vx, float vy, float vz,
                                        float px, float py, float pz,
                                        float qx, float qy, float qz,
                                        float hw, float r, float g, float b, float a) {
        float dx1 = vx - px, dy1 = vy - py;
        float len1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1);
        if (len1 < 1e-4f) return;
        float n1x = -dy1 / len1 * hw, n1y = dx1 / len1 * hw;
        float dx2 = qx - vx, dy2 = qy - vy;
        float len2 = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2);
        if (len2 < 1e-4f) return;
        float n2x = -dy2 / len2 * hw, n2y = dx2 / len2 * hw;
        bb.addVertex(m, vx - n1x, vy - n1y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx + n1x, vy + n1y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx + n2x, vy + n2y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx - n1x, vy - n1y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx + n2x, vy + n2y, vz).setColor(r, g, b, a);
        bb.addVertex(m, vx - n2x, vy - n2y, vz).setColor(r, g, b, a);
    }
    private void drawLabels(GuiGraphics g, int vx, int vy, int vw, int vh, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        var font = Minecraft.getInstance().font;
        for (int pi = 0; pi < solarSystem.size(); pi++) {
            float[] pos = solarSystem.worldPos(pi, simTime);
            float dwx = pos[0] - focalX, dwz = pos[2] - focalZ;
            float rx = dwx * cosY + dwz * sinY;
            float rz1 = -dwx * sinY + dwz * cosY;
            float ry2 = -rz1 * sinX;
            float rz = rz1 * cosX;
            float camZ = rz - dist;
            if (camZ > -0.5f) continue;
            float scrX = cx + rx * focal / Math.max(-camZ, 0.01f);
            float scrY = cy - ry2 * focal / Math.max(-camZ, 0.01f);
            float sz = Math.min(1.5f, focal / Math.max(-camZ, 0.01f) * 1.2f);
            if (sz < 0.15f) continue;
            g.drawCenteredString(font, solarSystem.get(pi).name(), (int) scrX, (int) scrY, 0xFFCCCCDD);
        }
    }
    private int pickPlanet(int mx, int my, float focalLength, float pcx, float pcy) {
        float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        float cosX = (float) Math.cos(pitch), sinX = (float) Math.sin(pitch);
        int best = -1; float bestZ = 0;
        for (int pi = 0; pi < solarSystem.size(); pi++) {
            float[] pos = solarSystem.worldPos(pi, simTime);
            float dwx = pos[0] - focalX, dwz = pos[2] - focalZ;
            float rz1 = -dwx * sinY + dwz * cosY;
            float camZ = rz1 * cosX - dist;
            if (camZ > -0.2f) continue;
            float rx = dwx * cosY + dwz * sinY;
            float ry2 = -rz1 * sinX;
            Planet p = solarSystem.get(pi);
            float r = 0;
            for (PlanetLayer l : p.layers()) if (l.type() == PlanetLayerType.BASE) r = l.radius();
            float scrX = pcx + rx * focalLength / Math.max(-camZ, 0.01f);
            float scrY = pcy - ry2 * focalLength / Math.max(-camZ, 0.01f);
            float dx = mx - scrX, dy = my - scrY;
            float rPx = Math.max(14f, r * focalLength / Math.max(-camZ, 0.01f));
            if (dx * dx + dy * dy < rPx * rPx && camZ < bestZ) { best = pi; bestZ = camZ; }
        }
        return best;
    }
    private static List<int[]> buildEdges(Polyhedron mesh) {
        Map<Long, int[]> edgeMap = new HashMap<>();
        for (int[] face : mesh.faces) {
            for (int i = 0; i < face.length; i++) {
                int a = face[i], b = face[(i + 1) % face.length];
                long key = Math.min(a, b) * 100000L + Math.max(a, b);
                if (!edgeMap.containsKey(key)) edgeMap.put(key, new int[]{a, b});
            }
        }
        return new ArrayList<>(edgeMap.values());
    }
    public int getFocalIndex() { return focalIndex; }
    public float getSimTime() { return simTime; }
    public SolarSystem getSolarSystem() { return solarSystem; }

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
