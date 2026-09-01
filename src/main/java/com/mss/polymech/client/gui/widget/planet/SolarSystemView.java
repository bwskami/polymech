package com.mss.polymech.client.gui.widget.planet;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mss.polymech.Polymech;
import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public class SolarSystemView extends UIElement {
    // 光照方向已改为每个行星实时计算（向太阳方向），旧常量不再使用
    // ===== skybox =====
    // 新的 cubemap 格式：单张 4x3 十字布局贴图。0 号只给星图；1~11 随机分配给各恒星系。
    static final ResourceLocation[] SKYBOX_CUBEMAPS = new ResourceLocation[12];
    static {
        for (int i = 0; i < 12; i++) {
            SKYBOX_CUBEMAPS[i] = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID,
                    "textures/gui/skybox/cubemap/cubemap_space" + i + ".png");
        }
    }
    static final float SKY_R = 1500f;
    static final boolean DEBUG_SKYBOX_ORIENTATION = true;

    SolarSystem solarSystem;
    final Consumer<TechNode> onSelect;
    final CameraController camera = new CameraController();
    float simTime;
    float lastMouseX, lastMouseY;
    boolean dragging;
    boolean freeView;
    boolean galaxyMapMode;
    IntConsumer onStarSelect;
    int dragButton = -1;
    boolean dragMoved;
    int lastMX, lastMY;
    long lastNano = System.nanoTime();
    double fpsSmooth = 60.0;
    int focalIndex = 3;
    int systemIndex = 0;
    float[][][] faceColors;
    SurfaceMaterial[][] faceMaterials;
    /** 每个星球是否含有海洋材质面片（需要海洋层+陆地层分离渲染）。 */
    boolean[] hasOcean;
    PlanetHeight[] planetHeights;
    /** 细分光照网格缓存：每种 baseMesh 一份（不同分辨率的星球各自一份）。 */
    java.util.Map<Polyhedron, ShadedBase> shadedBaseCache = new java.util.HashMap<>();
    /** 细分网格及 子三角->原地块 映射。 */
    static class ShadedBase {
        final Polyhedron mesh;
        final int[] faceParent;
        final int[] vertexParent;
        ShadedBase(Polyhedron mesh, int[] faceParent, int[] vertexParent) {
            this.mesh = mesh; this.faceParent = faceParent; this.vertexParent = vertexParent;
        }
    }
    /** 面内细分级数：每个扇形三角 -> 4^N。 */
    static final int SHADE_SUBDIV = 2;
    /** BASE 层光照走 GPU 着色器；置 false 回退到 CPU 逐顶点路径（等价实现，作为兜底）。 */
    static final boolean GPU_LIGHTING = true;
    // GPU 刚体变换 / 局部系光照 的复用临时量（避免每帧分配）
    final Matrix4f mvTmp = new Matrix4f();
    /** Identity matrix reused every frame (avoids per-frame allocation). */
    final Matrix4f idMat = new Matrix4f();
    /** Skybox view rotation (rotation only, no translation). */
    final Matrix4f skyRot = new Matrix4f();
    /** Reusable vec3 for worldPosTo calls from PlanetLayerDrawer/OrbitalDrawer (avoids per-call allocation). */
    final float[] _tmpWp1 = new float[3], _tmpWp2 = new float[3];
    final float[] lightLocal = new float[3], viewLocal = new float[3];
    final float[] reflLocal = new float[3], reflWorld = new float[3];
    final float[] mvOrigin = new float[3], mvAxisX = new float[3], mvAxisY = new float[3], mvAxisZ = new float[3];
    // 逐顶点光照结果缓存（按细分网格尺寸，复用于所有层，避免每帧分配）
    float[] lDirect, lSpec, lLimb, lRimW, lRimC, lShadowB, lRefl;
    float[] lColR, lColG, lColB;
    static final float[] ORIGIN3 = {0,0,0}, AXIS_X3 = {1,0,0}, AXIS_Y3 = {0,1,0}, AXIS_Z3 = {0,0,1};
    final java.util.HashMap<Long, Noise3> layerNoiseCache = new java.util.HashMap<>();
    final Map<Polyhedron, List<int[]>> edgeCache = new HashMap<>();
    final HashMap<Long, Polyhedron> rockCache = new HashMap<>();

    static final int ASTEROID_COUNT = 300;
    static final int KUIPER_COUNT = 90;
    float[][] asteroidPos;  // [i]={angle,radius,y,size,tiltA,tiltB,r,g,b}
    float[][] kuiperPos;

    int hoveredTile = -1;
    int selectedTile = -1;
    float hoverAlpha;
    long hoverStartNano;
    long lastHighlightNano = System.nanoTime();
    int chaseFace = -1;
    final float[] chaseWx = new float[6], chaseWy = new float[6], chaseWz = new float[6];
    float chaseWMx, chaseWMy, chaseWMz;
    boolean chaseActive;
    int chaseFaceVerts;
    float[] pickSx, pickSy, pickDepth;
    int pickCount;
    float[] tileSx, tileSy, tileZ;
    float tileDist;
    /** Reusable arrays for tile screen coord computation (avoids per-frame allocation). */
    final float[] tmpCam = new float[3];
    private double sceneMsSmooth = 0;
    private final List<RenderTask> taskList = new ArrayList<>(48);
    private final float[] _taskWp = new float[3];
    float currentTilt;
    float overlayFade = 1f;
    final PlanetLighting lighting = new PlanetLighting();
    ShadowModel shadowModel;
    Polyhedron tileMesh;
    // 超空间航道箭头（边境上的可点击箭头）
    private final float[] arrowSx = new float[8], arrowSy = new float[8];
    private final int[] arrowTargets = new int[8];
    private int arrowCount;
    private int hoveredArrow = -1;
    private final float[] _tmpCam2 = new float[3], _tmpScr = new float[3];
    private final float[] _laneA = new float[3], _laneB = new float[3];
    private final float[] _glintCam = new float[3];

    // ---- rendering subsystems (extracted) ----
    PlanetLayerDrawer layerDrawer;
    OrbitalDrawer orbitalDrawer;
    TechTreeDrawer techTreeDrawer;

    public SolarSystemView(SolarSystem solarSystem, Consumer<TechNode> onSelect) {
        this.onSelect = onSelect;
        rebuildScene(solarSystem);
        float[] wp0 = solarSystem.worldPos(focalIndex, 0);
        camera.setFocal(wp0[0], wp0[2]);
        addEventListener(UIEvents.MOUSE_DOWN, e -> {
            dragging = true; dragButton = e.button; dragMoved = false;
            lastMX = (int) e.x; lastMY = (int) e.y; camera.stopInertia();
            // 按下中键立即进入自由视角，无需等待拖动阈值
            if (e.button == 2 && !freeView) {
                freeView = true; selectedTile = -1; hoveredTile = -1;
            }
        });
        addEventListener(UIEvents.MOUSE_UP, e -> dragging = false);
        addEventListener(UIEvents.MOUSE_MOVE, e -> {
            if (!dragging) return;
            int mx = (int) e.x, my = (int) e.y;
            if (Math.abs(mx - lastMX) + Math.abs(my - lastMY) > 4) dragMoved = true;
            if (dragButton == 0 || dragButton == 1) {
                // 左键/右键长按拖拽：转动视角（拖拽松手不触发点击）
                camera.rotate(mx - lastMX, my - lastMY);
            } else if (dragButton == 2) {
                // 中键拖拽：按下即已进入自由视角，移动立即生效（无需阈值）
                camera.pan(mx - lastMX, my - lastMY);
                camera.clampFocal(systemBorderRadius());
            }
            lastMX = mx; lastMY = my;
        });
        addEventListener(UIEvents.MOUSE_WHEEL, e -> { camera.zoom(e.deltaY, minDistForFocal()); });
        addEventListener(UIEvents.CLICK, e -> {
            if (e.button != 0) {
                // 只有左键单击会触发选择/锁定；右键单击无事发生，中键单击也仅用于拖拽
                return;
            }
            // 长按拖拽后松手不算点击：左键拖动只转动视角，不触发选中/锁定
            if (dragMoved) return;
            if (galaxyMapMode && onStarSelect != null) {
                int pi = pickPlanet((int) lastMouseX, (int) lastMouseY, camera.focalLength(), camera.cx(), camera.cy());
                if (pi >= 0) onStarSelect.accept(pi);
                return;
            }
            if (freeView) {
                // 自由视角下：左键点击箭头同样可以切换星系（切换后保持自由视角）
                for (int i = 0; i < arrowCount; i++) {
                    float adx = (int) e.x - arrowSx[i], ady = (int) e.y - arrowSy[i];
                    if (adx * adx + ady * ady < 14 * 14) {
                        switchToSystem(arrowTargets[i]);
                        return;
                    }
                }
                // 自由视角下：左键点击星球重新锁定摄像机到该星球
                int pi = pickPlanet((int) lastMouseX, (int) lastMouseY, camera.focalLength(), camera.cx(), camera.cy());
                if (pi >= 0) {
                    freeView = false;
                    camera.stopInertia();
                    focalIndex = pi;
                    camera.ensureMinDist(minDistForFocal());
                    camera.beginTransition(solarSystem.worldPos(focalIndex, simTime)[0], solarSystem.worldPos(focalIndex, simTime)[2]);
                    selectedTile = -1;
                    hoveredTile = -1;
                }
                return;
            }
            if (hoveredTile >= 0) {
                selectedTile = hoveredTile;
                List<TechNode> fn = solarSystem.get(focalIndex).techNodes();
                if (hoveredTile >= 0 && hoveredTile < fn.size()) onSelect.accept(fn.get(hoveredTile));
            } else {
                for (int i = 0; i < arrowCount; i++) {
                    float adx = (int) e.x - arrowSx[i], ady = (int) e.y - arrowSy[i];
                    if (adx * adx + ady * ady < 14 * 14) {
                        switchToSystem(arrowTargets[i]);
                        return;
                    }
                }
                int pi = pickPlanet((int) lastMouseX, (int) lastMouseY, camera.focalLength(), camera.cx(), camera.cy());
                if (pi >= 0 && pi != focalIndex) {
                    camera.stopInertia();
                    focalIndex = pi;
                    camera.ensureMinDist(minDistForFocal());
                    camera.beginTransition(solarSystem.worldPos(focalIndex, simTime)[0], solarSystem.worldPos(focalIndex, simTime)[2]);
                    selectedTile = -1;
                    hoveredTile = -1;
                }
            }
        });
    }

    /** 星图模式：把星图看作一个“大恒星系”，复用星球/图层/着色器渲染。 */
    public SolarSystemView(SolarSystem solarSystem, Consumer<TechNode> onSelect, IntConsumer onStarSelect) {
        this(solarSystem, onSelect);
        this.galaxyMapMode = true;
        this.onStarSelect = onStarSelect;
        this.systemIndex = -1; // 不绘制恒星系边境/航道箭头/小行星带
        this.asteroidPos = new float[0][9];
        this.kuiperPos = new float[0][9];
        // 初始相机对准整张星图的包围盒中心
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < solarSystem.size(); i++) {
            float[] wp = solarSystem.worldPos(i, 0);
            minX = Math.min(minX, wp[0]); maxX = Math.max(maxX, wp[0]);
            minY = Math.min(minY, wp[1]); maxY = Math.max(maxY, wp[1]);
            minZ = Math.min(minZ, wp[2]); maxZ = Math.max(maxZ, wp[2]);
        }
        float cx = (minX + maxX) / 2f;
        float cy = (minY + maxY) / 2f;
        float cz = (minZ + maxZ) / 2f;
        float maxR = 0;
        for (int i = 0; i < solarSystem.size(); i++) {
            float[] wp = solarSystem.worldPos(i, 0);
            float dx = wp[0] - cx, dy = wp[1] - cy, dz = wp[2] - cz;
            maxR = Math.max(maxR, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        camera.setFocal(cx, cy, cz);
        camera.setDist(Math.max(30f, maxR * 2.2f));
    }

    void rebuildScene(SolarSystem solarSystem) {
        this.solarSystem = solarSystem;
        int n = solarSystem.size();
        this.faceColors = new float[n][][];
        this.faceMaterials = new SurfaceMaterial[n][];
        this.hasOcean = new boolean[n];
        this.planetHeights = new PlanetHeight[n];

        this.shadowModel = new ShadowModel(solarSystem);
        this.layerDrawer = new PlanetLayerDrawer(this);
        this.orbitalDrawer = new OrbitalDrawer(this);
        this.techTreeDrawer = new TechTreeDrawer(this);
        for (int i = 0; i < n; i++) {
            Polyhedron mesh = solarSystem.get(i).baseMesh();
            faceColors[i] = new float[mesh.faces.length][3];
            faceMaterials[i] = new SurfaceMaterial[mesh.faces.length];
            planetHeights[i] = new PlanetHeight(i, mesh, solarSystem.get(i).heightScale());
            precomputeColorsInto(i, mesh, faceColors[i]);
            precomputeMaterialsInto(i, mesh, faceMaterials[i]);
            for (SurfaceMaterial m : faceMaterials[i]) if (m == SurfaceMaterial.OCEAN) hasOcean[i] = true;
            if (hasOcean[i]) planetHeights[i].clampToSea = true;
        }
        ShadedBase sunShaded = shadedBaseFor(solarSystem.get(0).baseMesh());
        int vn = sunShaded.mesh.vertices.length;
        lDirect = new float[vn]; lSpec = new float[vn]; lLimb = new float[vn];
        lRimW = new float[vn]; lRimC = new float[vn]; lShadowB = new float[vn]; lRefl = new float[vn];
        lColR = new float[vn]; lColG = new float[vn]; lColB = new float[vn];
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
        if (systemIndex != 0) {
            asteroidPos = new float[0][9];
            kuiperPos = new float[0][9];
        }
    }

    /** 切换到另一个恒星系（每个星系独立场景）。 */
    void switchToSystem(int newIndex) {
        if (newIndex < 0 || newIndex >= StarSystemCatalog.size() || newIndex == systemIndex) return;
        systemIndex = newIndex;
        // 清空旧场景资源
        layerDrawer.closeVBOs();
        orbitalDrawer.closeVBOs();
        shadedBaseCache.clear();
        layerNoiseCache.clear();
        edgeCache.clear();
        rockCache.clear();
        tileMesh = null;
        selectedTile = -1;
        hoveredTile = -1;
        hoverAlpha = 0;
        // 生成新场景
        SolarSystem sys = newIndex == 0
                ? SolarSystem.createDefault()
                : SolarSystemGenerator.generate(StarSystemCatalog.get(newIndex));
        rebuildScene(sys);
        focalIndex = 0; // 进入新星系先聚焦恒星
        float starR = 0;
        for (PlanetLayer l : solarSystem.get(0).layers()) if (l.type() == PlanetLayerType.BASE) { starR = l.radius(); break; }
        float[] wp = solarSystem.worldPos(0, 0);
        camera.setFocal(wp[0], wp[2]);
        camera.stopInertia();
    }

    void precomputeColorsInto(int pi, Polyhedron mesh, float[][] fc) {
        Planet p = solarSystem.get(pi);
        long seed = 0x5EED1234L + pi * 0x1234567L;
        var noise = new Noise3(seed);
        PlanetColorProvider provider = p.colorProvider();
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            float cx = 0, cy = 0, cz = 0;
            for (int v : fv) { cx += mesh.vertices[v][0]; cy += mesh.vertices[v][1]; cz += mesh.vertices[v][2]; }
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            cx /= len; cy /= len; cz /= len;
            float lat = Math.abs(cy);
            float height = planetHeights[pi].rawHeight(cx, cy, cz);
            float[] color = provider.compute(f, cx, cy, cz, lat, height, noise);
            fc[f][0] = clamp(color[0], 0, 1);
            fc[f][1] = clamp(color[1], 0, 1);
            fc[f][2] = clamp(color[2], 0, 1);
        }
    }

    void precomputeMaterialsInto(int pi, Polyhedron mesh, SurfaceMaterial[] fm) {
        Planet p = solarSystem.get(pi);
        long seed = 0x5EED1234L + pi * 0x1234567L;
        var noise = new Noise3(seed);
        PlanetColorProvider provider = p.colorProvider();
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            float cx = 0, cy = 0, cz = 0;
            for (int v : fv) { cx += mesh.vertices[v][0]; cy += mesh.vertices[v][1]; cz += mesh.vertices[v][2]; }
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (len < 1e-6f) len = 1f;
            cx /= len; cy /= len; cz /= len;
            fm[f] = provider.material(f, cx, cy, cz, Math.abs(cy), planetHeights[pi].rawHeight(cx, cy, cz), noise);
        }
    }

    /**
     * General-purpose: probe cameraTo() with origin + 3 axes, reconstruct a 4x4 matrix
     * that transforms local-space coords to camera space (identical to cameraTo, zero error).
     */
    void buildTransformMatrix(float dwx, float dwy, float dwz, float sc, float ss, float tilt, Matrix4f out) {
        camera.cameraTo(mvOrigin, ORIGIN3, 1f, dwx, dwy, dwz, sc, ss, tilt);
        camera.cameraTo(mvAxisX, AXIS_X3, 1f, dwx, dwy, dwz, sc, ss, tilt);
        camera.cameraTo(mvAxisY, AXIS_Y3, 1f, dwx, dwy, dwz, sc, ss, tilt);
        camera.cameraTo(mvAxisZ, AXIS_Z3, 1f, dwx, dwy, dwz, sc, ss, tilt);
        float c0x = mvAxisX[0]-mvOrigin[0], c0y = mvAxisX[1]-mvOrigin[1], c0z = mvAxisX[2]-mvOrigin[2];
        float c1x = mvAxisY[0]-mvOrigin[0], c1y = mvAxisY[1]-mvOrigin[1], c1z = mvAxisY[2]-mvOrigin[2];
        float c2x = mvAxisZ[0]-mvOrigin[0], c2y = mvAxisZ[1]-mvOrigin[1], c2z = mvAxisZ[2]-mvOrigin[2];
        out.set(c0x,c0y,c0z,0f, c1x,c1y,c1z,0f, c2x,c2y,c2z,0f, mvOrigin[0],mvOrigin[1],mvOrigin[2],1f);
    }

    /** Shorthand: build transform for a RenderTask's planet. */
    void buildModelView(RenderTask t, float sc, float ss, Matrix4f out) {
        buildTransformMatrix(t.dwx, t.dwy, t.dwz, sc, ss, currentTilt, out);
    }

    /** 相机空间方向 -> 星球局部系方向（模型视图旋转部分的转置）。 */
    void toLocalDir(Matrix4f mv, float cx, float cy, float cz, float[] out) {
        out[0] = mv.m00()*cx + mv.m01()*cy + mv.m02()*cz;
        out[1] = mv.m10()*cx + mv.m11()*cy + mv.m12()*cz;
        out[2] = mv.m20()*cx + mv.m21()*cy + mv.m22()*cz;
    }

    /** 构建细分光照网格：每个地块扇形三角再细分，保地块 albedo、光照更平滑。
     *  顶点按 (位置,地块) 去重：边界顶点按地块各留一份——保住地块棱线，
     *  且每顶点唯一属于一个地块，可逐顶点预计算最终色（免去按面重复 colorize）。 */
    ShadedBase buildShadedBase(Polyhedron tileMesh, int level) {
        List<float[]> verts = new ArrayList<>();
        HashMap<Long, Integer> dedup = new HashMap<>();
        List<int[]> faces = new ArrayList<>();
        List<Integer> parents = new ArrayList<>();
        List<Integer> vertParents = new ArrayList<>();
        float[][] tv = tileMesh.vertices;
        for (int t = 0; t < tileMesh.faces.length; t++) {
            int[] tile = tileMesh.faces[t];
            float cx = 0, cy = 0, cz = 0;
            for (int v : tile) { cx += tv[v][0]; cy += tv[v][1]; cz += tv[v][2]; }
            float cl = (float) Math.sqrt(cx*cx + cy*cy + cz*cz);
            float[] c = {cx/cl, cy/cl, cz/cl};
            for (int j = 0; j < tile.length; j++) {
                int a = tile[j], b = tile[(j+1) % tile.length];
                subdivideSph(c, tv[a], tv[b], level, t, verts, dedup, faces, parents, vertParents);
            }
        }
        int[] pa = new int[faces.size()];
        for (int i = 0; i < faces.size(); i++) pa[i] = parents.get(i);
        int[] vpa = new int[verts.size()];
        for (int i = 0; i < verts.size(); i++) vpa[i] = vertParents.get(i);
        return new ShadedBase(Polyhedron.of(verts.toArray(new float[0][]), faces.toArray(new int[0][])), pa, vpa);
    }

    /** 获取某 baseMesh 对应的细分网格（按面数选择细分级别，保持约 1 万面）。 */
    ShadedBase shadedBaseFor(Polyhedron baseMesh) {
        return shadedBaseCache.computeIfAbsent(baseMesh, m -> {
            int level = m.faces.length >= 2000 ? 1 : SHADE_SUBDIV;
            return buildShadedBase(m, level);
        });
    }
    private void subdivideSph(float[] p1, float[] p2, float[] p3, int level, int parent,
                              List<float[]> verts, HashMap<Long, Integer> dedup, List<int[]> faces,
                              List<Integer> parents, List<Integer> vertParents) {
        if (level == 0) {
            faces.add(new int[]{addSphVert(p1, parent, verts, dedup, vertParents),
                                addSphVert(p2, parent, verts, dedup, vertParents),
                                addSphVert(p3, parent, verts, dedup, vertParents)});
            parents.add(parent);
            return;
        }
        float[] m12 = sphMid(p1, p2), m23 = sphMid(p2, p3), m31 = sphMid(p3, p1);
        subdivideSph(p1, m12, m31, level-1, parent, verts, dedup, faces, parents, vertParents);
        subdivideSph(m12, p2, m23, level-1, parent, verts, dedup, faces, parents, vertParents);
        subdivideSph(m31, m23, p3, level-1, parent, verts, dedup, faces, parents, vertParents);
        subdivideSph(m12, m23, m31, level-1, parent, verts, dedup, faces, parents, vertParents);
    }
    static int addSphVert(float[] p, int parent, List<float[]> verts, HashMap<Long, Integer> dedup, List<Integer> vertParents) {
        long key = sphKey(p[0], p[1], p[2]) * 1024L + parent;
        Integer idx = dedup.get(key);
        if (idx != null) return idx;
        verts.add(new float[]{p[0], p[1], p[2]});
        vertParents.add(parent);
        dedup.put(key, verts.size() - 1);
        return verts.size() - 1;
    }
    static long sphKey(float x, float y, float z) {
        long qx = Math.round(x * 1e5f) + 100000L;
        long qy = Math.round(y * 1e5f) + 100000L;
        long qz = Math.round(z * 1e5f) + 100000L;
        return qx + qy * 200001L + qz * 200001L * 200001L;
    }
    static float[] sphMid(float[] a, float[] b) {
        float mx = a[0]+b[0], my = a[1]+b[1], mz = a[2]+b[2];
        float l = (float) Math.sqrt(mx*mx + my*my + mz*mz);
        return new float[]{mx/l, my/l, mz/l};
    }

    static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s, x = c * (1 - Math.abs(((h / 60) % 2) - 1)), m = v - c;
        float r, g, b;
        if (h < 60) { r = c; g = x; b = 0; } else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; } else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; } else { r = c; g = 0; b = x; }
        return new float[]{r + m, g + m, b + m};
    }
    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    static float easeOutCubic(float t) { return 1f - (1f - t) * (1f - t) * (1f - t); }
    static class RenderTask {
        final int pi; final String type; final float layerR, selfRot, camDist, dwx, dwy, dwz;
        final Polyhedron mesh;
        /** BASE 层的地块 albedo（按地块索引）；其他层为 null。 */
        final float[][] albedo;
        /** 细分网格的 子三角->原地块 映射；非细分网格为 null（面号即地块号）。 */
        final int[] faceParent;
        /** 细分网格的 顶点->原地块 映射；非细分网格为 null。 */
        final int[] vertexParent;
        RenderTask(int pi, String type, float layerR, float selfRot, float camDist, float dwx, float dwy, float dwz, Polyhedron mesh, float[][] albedo, int[] faceParent, int[] vertexParent) {
            this.pi = pi; this.type = type; this.layerR = layerR; this.selfRot = selfRot; this.camDist = camDist; this.dwx = dwx; this.dwy = dwy; this.dwz = dwz; this.mesh = mesh; this.albedo = albedo; this.faceParent = faceParent; this.vertexParent = vertexParent;
        }
    }
    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1.0e9f;
        lastNano = now;
        if (dt > 0.1f) dt = 0.1f;
        simTime += dt;
        fpsSmooth = fpsSmooth * 0.9 + (dt > 0 ? 1.0 / dt : 0) * 0.1;
        float[] wp = solarSystem.worldPos(focalIndex, simTime);
        camera.ensureMinDist(minDistForFocal());
        // 科技层/网格层随最近距离淡入淡出
        float fadeTarget = computeOverlayFadeTarget();
        overlayFade += (fadeTarget - overlayFade) * Math.min(1f, dt * 10f);
        // 摄像机旋转惯性 + 焦点过渡动画（点击行星后平滑飞过去）。
        // 自由视角下不追踪原焦点星球，让右键拖拽平移的摄像机保持在原位。
        camera.updateInertia(dt, dragging);
        if (!freeView && !galaxyMapMode) {
            camera.updateTransition(dt, wp[0], wp[2]);
        }
        GuiGraphics g = guiContext.graphics;
        int vx = (int) getPositionX(), vy = (int) getPositionY();
        int vw = (int) getSizeWidth(), vh = (int) getSizeHeight();
        g.fill(vx, vy, vx + vw, vy + vh, 0xFF020208);
        g.flush();
        camera.updateProjection(vw, vh);
        float focal = camera.focalLength(), cx = camera.cx(), cy = camera.cy();
        lastMouseX = guiContext.mouseX; lastMouseY = guiContext.mouseY;
        float cosY = camera.cosY(), sinY = camera.sinY(), cosX = camera.cosX(), sinX = camera.sinX();
        long sceneStart = System.nanoTime();
        // idMat is a field (avoids per-frame allocation)
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
        Matrix4f oldProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix(); mvs.identity(); RenderSystem.applyModelViewMatrix();
        float fov = 2f * (float) Math.atan((vh / 2f) / focal);
        Matrix4f proj = new Matrix4f().perspective(fov, (float) vw / (float) vh, 0.01f, 3000f);
        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
        RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
        RenderSystem.clearDepth(1.0f); RenderSystem.clear(0x100, false);
        drawSkybox(idMat);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableDepthTest(); RenderSystem.depthMask(true);
        List<RenderTask> tasks = taskList;
        tasks.clear();
        for (int pi = 0; pi < solarSystem.size(); pi++) {
            float[] pos = solarSystem.worldPosTo(_taskWp, pi, simTime);
            float dwx = pos[0] - camera.focalX(), dwy = pos[1], dwz = pos[2] - camera.focalZ();
            float rz1 = -dwx * sinY + dwz * cosY;
            float camDist = camera.dist() - (dwy - camera.focalY()) * sinX - rz1 * cosX;
            Planet p2 = solarSystem.get(pi);
            for (PlanetLayer layer : p2.layers()) {
                if (!layer.visible()) continue;
                // 网格（线框）只在焦点行星显示
                if (layer.type() == PlanetLayerType.WIREFRAME && pi != focalIndex) continue;
                Polyhedron geo = p2.resolveGeometry(layer);
                float[][] alb = null;
                int[] fpar = null;
                int[] vpar = null;
                if (layer.type() == PlanetLayerType.BASE) {
                    alb = faceColors[pi];
                    if (pi == focalIndex) { ShadedBase sb = shadedBaseFor(solarSystem.get(pi).baseMesh()); geo = sb.mesh; fpar = sb.faceParent; vpar = sb.vertexParent; }
                } else if (layer.type() == PlanetLayerType.ATMOSPHERE) {
                    // 所有星球大气统一用 goldberg 拓扑（比 sphere(16,24) 经纬网格更均匀）；
                    // 焦点星球用更高精度 shadedBase，远距离的用 baseMesh 即可。
                    geo = (pi == focalIndex) ? shadedBaseFor(solarSystem.get(pi).baseMesh()).mesh : solarSystem.get(pi).baseMesh();
                }
                tasks.add(new RenderTask(pi, layer.type().name(), layer.radius(), p2.resolveRotationSpeed(layer), camDist, dwx, dwy, dwz, geo, alb, fpar, vpar));
            }
        }
        tasks.sort(Comparator.comparingDouble(t -> -t.camDist));
        for (RenderTask t : tasks) if (t.type.equals("BASE") || t.type.equals("CLOUD")) drawLayer(idMat, t, cosY, sinY, cosX, sinX, focal, cx, cy);
        drawOrbitalRings(idMat, cosY, sinY, cosX, sinX, focal, cx, cy);
        if (systemIndex == 0) {
            drawBeltBand(idMat, 50f, 65f, 5, cosY, sinY, cosX, sinX, 0.60f, 0.50f, 0.40f, 0.10f);
            drawBeltBand(idMat, 230f, 280f, 4, cosY, sinY, cosX, sinX, 0.45f, 0.50f, 0.60f, 0.06f);
        }
        drawScatteredRocks(idMat, asteroidPos, cosY, sinY, cosX, sinX);
        drawScatteredRocks(idMat, kuiperPos, cosY, sinY, cosX, sinX);
        RenderSystem.depthMask(false);
        for (RenderTask t : tasks) if (!t.type.equals("BASE") && !t.type.equals("CLOUD") && !t.type.equals("TECH")) drawLayer(idMat, t, cosY, sinY, cosX, sinX, focal, cx, cy);
        RenderSystem.depthMask(false);
        // ── 3D 恒星光晕：在透视投影 + 深度检测下绘制，行星自然遮挡 ──
        if (!galaxyMapMode) {
            drawSunGlow(idMat);
        }
        boolean hasWireframe = false;
        boolean hasTech = false;
        for (PlanetLayer l : solarSystem.get(focalIndex).layers()) {
            if (l.type() == PlanetLayerType.WIREFRAME && l.visible()) hasWireframe = true;
            if (l.type() == PlanetLayerType.TECH) hasTech = true;
        }
        if (hasWireframe && !freeView) {
            // 预计算拾取网格的屏幕坐标（与 PolyhedronView 一致：每帧一次，拾取直接用）
            Planet fp = solarSystem.get(focalIndex);
            PlanetLayer gridL = gridLayer(fp);
            float pickR = gridL != null ? gridL.radius() : 0;
            tileMesh = gridL != null ? fp.resolveGeometry(gridL) : fp.baseMesh();
            float selfAngle = (gridL != null ? fp.resolveRotationSpeed(gridL) : 0) * simTime;
            float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);
            float[] wp2 = solarSystem.worldPos(focalIndex, simTime);
            float pdwx = wp2[0] - camera.focalX(), pdwy = wp2[1], pdwz = wp2[2] - camera.focalZ();
            currentTilt = fp.axialTilt();
            int nv = tileMesh.vertices.length;
            tileSx = new float[nv]; tileSy = new float[nv]; tileZ = new float[nv]; tileDist = camera.dist();
            for (int i = 0; i < nv; i++) {
                camera.cameraToNoAlloc(tmpCam, tileMesh.vertices[i], pickR, pdwx, pdwy, pdwz, sc, ss, currentTilt);
                float d = Math.max(-tmpCam[2], 0.15f);
                tileSx[i] = camera.cx() + tmpCam[0] * camera.focalLength() / d;
                tileSy[i] = camera.cy() - tmpCam[1] * camera.focalLength() / d;
                tileZ[i] = tmpCam[2];
            }
            updateHover(guiContext);
            drawTechMarkers(g, idMat, cosY, sinY, cosX, sinX, focal, cx, cy);
            drawTechHighlight(idMat, cosY, sinY, cosX, sinX, focal, cx, cy);
        } else { hoveredTile = -1; hoverAlpha = 0; tileMesh = null; }
        drawSystemOverlay3D(idMat);
        if (galaxyMapMode) {
            drawGalaxyHyperlanes(idMat);
            drawGalaxyStarGlows(idMat);
        }
        RenderSystem.setProjectionMatrix(oldProj, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.disableDepthTest(); RenderSystem.depthMask(false);
        mvs.popMatrix(); RenderSystem.applyModelViewMatrix(); RenderSystem.setShaderColor(1, 1, 1, 1);
        // drawLabels 暂时禁用，后续做附加UI时再启用
        drawSystemOverlay(g, vx, vy, camera.cx(), camera.cy());

        drawSkyboxOrientationDebug(g);

        // FPS
        var font = Minecraft.getInstance().font;
        String gpuStatus = (GPU_LIGHTING && PlanetShaders.isReady()) ? "GPU" : "CPU";
        String cloudStatus = PlanetShaders.isCloudReady() ? "GPU" : "CPU";
        String atmoStatus = PlanetShaders.isAtmoReady() ? "GPU" : "CPU";
        double sceneMs = (System.nanoTime() - sceneStart) / 1.0e6;
        sceneMsSmooth = sceneMsSmooth * 0.9 + sceneMs * 0.1;
        g.drawString(font, "FPS: " + (int) fpsSmooth + "  scene:" + String.format("%.2f", sceneMsSmooth) + "ms  B:" + gpuStatus + " C:" + cloudStatus + " A:" + atmoStatus,
                vx + 4, vy + 4, 0xFFFFFF00);
    }
    /** 3D 部分：绘制重力井虚线、边境虚线、超空间航道箭头（与轨道线同画法）。 */
    void drawSystemOverlay3D(Matrix4f mat) {
        if (systemIndex < 0 || systemIndex >= StarSystemCatalog.size()) return;
        StarSystem sys = StarSystemCatalog.get(systemIndex);
        float maxOrb = 0;
        for (int i = 0; i < solarSystem.size(); i++)
            maxOrb = Math.max(maxOrb, solarSystem.get(i).orbitalRadius());
        float starR = 0;
        for (PlanetLayer l : solarSystem.get(0).layers())
            if (l.type() == PlanetLayerType.BASE) { starR = l.radius(); break; }
        float gravR = Math.max(maxOrb * 1.15f, starR * 5f);
        float borderR = gravR * 1.45f;
        float hw = Math.max(0.12f, borderR * 0.002f);

        orbitalDrawer.drawDashedWorldCircle(mat, gravR, hw, 0.20f, 0.53f, 0.67f, 0.55f, 8, 4);
        orbitalDrawer.drawDashedWorldCircle(mat, borderR, hw, 1f, 1f, 1f, 0.45f, 10, 5);

        for (int h : sys.hyperlanes) {
            if (h < 0 || h >= StarSystemCatalog.size()) continue;
            StarSystem tgt = StarSystemCatalog.get(h);
            float dx = tgt.x - sys.x, dz = tgt.z - sys.z;
            float ang = (float) Math.atan2(dz, dx);
            orbitalDrawer.drawHyperlaneArrow3D(mat, ang, borderR, borderR * 0.08f, hw * 1.2f, 1f, 0.80f, 0.10f, 0.85f);
        }
    }

    /** 2D 部分：记录箭头屏幕位置，并绘制悬浮的星系名。 */
    /** 星图模式下的超空间航道：相机空间 3D 条带（始终面向相机，固定屏幕宽度）。 */
    private void drawGalaxyHyperlanes(Matrix4f mat) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < StarSystemCatalog.size(); i++) {
            StarSystem a = StarSystemCatalog.get(i);
            for (int h : a.hyperlanes) {
                if (h <= i || h >= StarSystemCatalog.size()) continue;
                StarSystem b = StarSystemCatalog.get(h);
                camera.worldToCamera(_laneA, a.x * SolarSystem.GALAXY_MAP_SCALE, a.y * SolarSystem.GALAXY_MAP_SCALE, a.z * SolarSystem.GALAXY_MAP_SCALE, -camera.focalX(), -camera.focalZ());
                camera.worldToCamera(_laneB, b.x * SolarSystem.GALAXY_MAP_SCALE, b.y * SolarSystem.GALAXY_MAP_SCALE, b.z * SolarSystem.GALAXY_MAP_SCALE, -camera.focalX(), -camera.focalZ());
                if (_laneA[2] > -0.2f || _laneB[2] > -0.2f) continue;
                // 透视正确：世界/相机空间宽度固定，投影后近宽远窄
                float hw = 0.05f;
                float dx = _laneB[0] - _laneA[0];
                float dy = _laneB[1] - _laneA[1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1e-4f) continue;
                float px = -dy / len * hw;
                float py = dx / len * hw;
                float a0x = _laneA[0] - px, a0y = _laneA[1] - py;
                float a1x = _laneA[0] + px, a1y = _laneA[1] + py;
                float b0x = _laneB[0] - px, b0y = _laneB[1] - py;
                float b1x = _laneB[0] + px, b1y = _laneB[1] + py;
                float[] ca = solarSystem.get(i).visual().baseColor();
                float[] cb = solarSystem.get(h).visual().baseColor();
                bb.addVertex(idMat, a0x, a0y, _laneA[2]).setColor(ca[0], ca[1], ca[2], 0.85f);
                bb.addVertex(idMat, a1x, a1y, _laneA[2]).setColor(ca[0], ca[1], ca[2], 0.85f);
                bb.addVertex(idMat, b1x, b1y, _laneB[2]).setColor(cb[0], cb[1], cb[2], 0.85f);
                bb.addVertex(idMat, a0x, a0y, _laneA[2]).setColor(ca[0], ca[1], ca[2], 0.85f);
                bb.addVertex(idMat, b1x, b1y, _laneB[2]).setColor(cb[0], cb[1], cb[2], 0.85f);
                bb.addVertex(idMat, b0x, b0y, _laneB[2]).setColor(cb[0], cb[1], cb[2], 0.85f);
            }
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** 星图模式下的恒星辉光：与恒星系内 drawSunGlow 相同的日冕光晕。 */
    private void drawGalaxyStarGlows(Matrix4f mat) {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        for (int i = 0; i < solarSystem.size(); i++) {
            Planet p = solarSystem.get(i);
            if (!p.visual().isGlowing()) continue;
            float[] wp = solarSystem.worldPosTo(_tmpWp1, i, simTime);
            camera.worldToCamera(_glintCam, wp[0], wp[1], wp[2], -camera.focalX(), -camera.focalZ());
            if (_glintCam[2] > -0.2f) continue;
            float baseR = 0;
            for (PlanetLayer l : p.layers()) if (l.type() == PlanetLayerType.BASE) { baseR = l.radius(); break; }
            if (baseR < 0.01f) continue;
            float[] bc = p.visual().baseColor();
            float gr = bc[0] * 0.65f + 0.35f;
            float gg = bc[1] * 0.65f + 0.35f;
            float gb = bc[2] * 0.65f + 0.35f;
            orbitalDrawer.drawGlowHalo(mat, _glintCam[0], _glintCam[1], _glintCam[2],
                    baseR * 1.00f, baseR * 2.40f, 0.35f, gr, gg, gb);
        }
        RenderSystem.defaultBlendFunc();
    }

    void drawSystemOverlay(GuiGraphics g, int vx, int vy, float pcx, float pcy) {
        if (systemIndex < 0 || systemIndex >= StarSystemCatalog.size()) return;
        StarSystem sys = StarSystemCatalog.get(systemIndex);
        float fX = camera.focalX(), fZ = camera.focalZ();
        float maxOrb = 0;
        for (int i = 0; i < solarSystem.size(); i++)
            maxOrb = Math.max(maxOrb, solarSystem.get(i).orbitalRadius());
        float starR = 0;
        for (PlanetLayer l : solarSystem.get(0).layers())
            if (l.type() == PlanetLayerType.BASE) { starR = l.radius(); break; }
        float gravR = Math.max(maxOrb * 1.15f, starR * 5f);
        float borderR = gravR * 1.45f;

        arrowCount = 0;
        hoveredArrow = -1;
        for (int h : sys.hyperlanes) {
            if (h < 0 || h >= StarSystemCatalog.size() || arrowCount >= arrowSx.length) continue;
            StarSystem tgt = StarSystemCatalog.get(h);
            float dx = tgt.x - sys.x, dz = tgt.z - sys.z;
            float ang = (float) Math.atan2(dz, dx);
            float wx = (float) Math.cos(ang) * borderR;
            float wz = (float) Math.sin(ang) * borderR;
            camera.worldToCamera(_tmpCam2, wx, 0, wz, -fX, -fZ);
            if (_tmpCam2[2] > -0.3f) continue;
            camera.toScreenNoAlloc(_tmpScr, _tmpCam2);
            float sx = _tmpScr[0], sy = _tmpScr[1];
            if (sx < -50 || sy < -50 || sx > 9999 || sy > 9999) continue;
            arrowSx[arrowCount] = sx;
            arrowSy[arrowCount] = sy;
            arrowTargets[arrowCount] = h;
            float mdx = lastMouseX - sx, mdy = lastMouseY - sy;
            if (mdx * mdx + mdy * mdy < 16 * 16) hoveredArrow = arrowCount;
            arrowCount++;
        }
        if (hoveredArrow >= 0) {
            StarSystem tgt = StarSystemCatalog.get(arrowTargets[hoveredArrow]);
            var font = Minecraft.getInstance().font;
            g.drawString(font, tgt.name + " (" + tgt.nameEn + ")", vx + (int) lastMouseX + 12, vy + (int) lastMouseY - 6, 0xFFFFFF00);
        }
    }

    private void drawSkybox(Matrix4f mat) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

        // 天空盒只跟随相机旋转，不跟随相机平移（相当于无限远）。
        skyRot.identity().rotateX(camera.pitch()).rotateY(camera.yaw());
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.mul(skyRot);
        RenderSystem.applyModelViewMatrix();

        ResourceLocation tex = currentSkyboxTexture();
        float r = SKY_R;
        float cu = 1f / 4f, cv = 1f / 3f;
        // 标准 4x3 十字 cubemap：
        // 上：行0列1；左：行1列0；前：行1列1；右：行1列2；后：行1列3；下：行2列1。
        drawSkyFace(mat, tex, cu, cv, cu * 2f, cv * 2f,
                -r, r, -r,  r, r, -r,  r, -r, -r,  -r, -r, -r); // front
        drawSkyFace(mat, tex, cu * 3f, cv, cu * 4f, cv * 2f,
                 r, r, r, -r, r, r, -r, -r, r,  r, -r, r); // back
        drawSkyFace(mat, tex, 0f, cv, cu, cv * 2f,
                -r, r, r, -r, r, -r, -r, -r, -r, -r, -r, r); // left
        drawSkyFace(mat, tex, cu * 2f, cv, cu * 3f, cv * 2f,
                 r, r, -r,  r, r, r,  r, -r, r,  r, -r, -r); // right
        drawSkyFace(mat, tex, cu, 0f, cu * 2f, cv,
                -r, r, r,  r, r, r,  r, r, -r, -r, r, -r); // top
        drawSkyFace(mat, tex, cu, cv * 2f, cu * 2f, cv * 3f,
                -r, -r, -r, r, -r, -r, r, -r, r, -r, -r, r); // bottom

        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.depthMask(true);
    }

    /** 临时调试：把每个天空盒面的中心/四边朝向画在屏幕上，用于确认哪一面反了。 */
    private void drawSkyboxOrientationDebug(GuiGraphics g) {
        if (!DEBUG_SKYBOX_ORIENTATION) return;
        float focal = camera.focalLength();
        float cx = camera.cx(), cy = camera.cy();
        var font = Minecraft.getInstance().font;
        drawSkyDirLabel(g, font, "FRONT", 0, 0, -1, cx, cy, focal, 0xFFFFFF00);
        drawSkyDirLabel(g, font, "BACK", 0, 0, 1, cx, cy, focal, 0xFFFFFF00);
        drawSkyDirLabel(g, font, "LEFT", -1, 0, 0, cx, cy, focal, 0xFFFFFF00);
        drawSkyDirLabel(g, font, "RIGHT", 1, 0, 0, cx, cy, focal, 0xFFFFFF00);
        drawSkyDirLabel(g, font, "TOP", 0, 1, 0, cx, cy, focal, 0xFFFFFF00);
        drawSkyDirLabel(g, font, "BOTTOM", 0, -1, 0, cx, cy, focal, 0xFFFFFF00);

        // 四边方向标记：T=纹理上边，B=纹理下边，L=纹理左边，R=纹理右边
        int tCol = 0xFFFFFF00, bCol = 0xFFFFA500, lCol = 0xFF00FFFF, rCol = 0xFFFF00FF;
        // front
        drawSkyDirLabel(g, font, "T", 0, 1, -1, cx, cy, focal, tCol);
        drawSkyDirLabel(g, font, "B", 0, -1, -1, cx, cy, focal, bCol);
        drawSkyDirLabel(g, font, "L", -1, 0, -1, cx, cy, focal, lCol);
        drawSkyDirLabel(g, font, "R", 1, 0, -1, cx, cy, focal, rCol);
        // back
        drawSkyDirLabel(g, font, "T", 0, 1, 1, cx, cy, focal, tCol);
        drawSkyDirLabel(g, font, "B", 0, -1, 1, cx, cy, focal, bCol);
        drawSkyDirLabel(g, font, "L", 1, 0, 1, cx, cy, focal, lCol);
        drawSkyDirLabel(g, font, "R", -1, 0, 1, cx, cy, focal, rCol);
        // left
        drawSkyDirLabel(g, font, "T", -1, 1, 0, cx, cy, focal, tCol);
        drawSkyDirLabel(g, font, "B", -1, -1, 0, cx, cy, focal, bCol);
        drawSkyDirLabel(g, font, "L", -1, 0, 1, cx, cy, focal, lCol);
        drawSkyDirLabel(g, font, "R", -1, 0, -1, cx, cy, focal, rCol);
        // right
        drawSkyDirLabel(g, font, "T", 1, 1, 0, cx, cy, focal, tCol);
        drawSkyDirLabel(g, font, "B", 1, -1, 0, cx, cy, focal, bCol);
        drawSkyDirLabel(g, font, "L", 1, 0, -1, cx, cy, focal, lCol);
        drawSkyDirLabel(g, font, "R", 1, 0, 1, cx, cy, focal, rCol);
        // top
        drawSkyDirLabel(g, font, "T", 0, 1, 1, cx, cy, focal, tCol);
        drawSkyDirLabel(g, font, "B", 0, 1, -1, cx, cy, focal, bCol);
        drawSkyDirLabel(g, font, "L", -1, 1, 0, cx, cy, focal, lCol);
        drawSkyDirLabel(g, font, "R", 1, 1, 0, cx, cy, focal, rCol);
        // bottom
        drawSkyDirLabel(g, font, "T", 0, -1, -1, cx, cy, focal, tCol);
        drawSkyDirLabel(g, font, "B", 0, -1, 1, cx, cy, focal, bCol);
        drawSkyDirLabel(g, font, "L", -1, -1, 0, cx, cy, focal, lCol);
        drawSkyDirLabel(g, font, "R", 1, -1, 0, cx, cy, focal, rCol);
    }

    private void drawSkyDirLabel(GuiGraphics g, net.minecraft.client.gui.Font font, String text,
                                 float dx, float dy, float dz,
                                 float cx, float cy, float focal, int color) {
        var v = new org.joml.Vector3f(dx, dy, dz);
        skyRot.transformDirection(v);
        if (v.z > -0.05f) return;
        float sx = cx + v.x * focal / -v.z;
        float sy = cy - v.y * focal / -v.z;
        g.drawString(font, text, (int) sx, (int) sy, color);
    }

    /** 选择天空盒 cubemap：星图固定 0 号，恒星系按目录 seed 在 1~11 之间稳定分配。 */
    private ResourceLocation currentSkyboxTexture() {
        if (galaxyMapMode) return SKYBOX_CUBEMAPS[0];
        int idx = 1;
        if (systemIndex >= 0 && systemIndex < StarSystemCatalog.size()) {
            long seed = StarSystemCatalog.get(systemIndex).seed;
            idx = 1 + (int) ((seed >>> 16) % 11L);
        }
        return SKYBOX_CUBEMAPS[idx];
    }

    private void drawSkyFace(Matrix4f mat, ResourceLocation texture,
                             float u0, float v0, float u1, float v1,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(mat, ax, ay, az).setUv(u0, v0);
        bb.addVertex(mat, bx, by, bz).setUv(u1, v0);
        bb.addVertex(mat, cx, cy, cz).setUv(u1, v1);
        bb.addVertex(mat, ax, ay, az).setUv(u0, v0);
        bb.addVertex(mat, cx, cy, cz).setUv(u1, v1);
        bb.addVertex(mat, dx, dy, dz).setUv(u0, v1);
        RenderSystem.setShaderTexture(0, texture);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }


    /**
     * 太阳屏幕空间光晕：先把恒星中心投影到屏幕，再以屏幕坐标画径向渐变光晕。
     * <p>
     * 屏幕空间绘制保证光晕永远与恒星投影中心同心（3D 公告板在透视下会椭圆化偏移）。
     * 光晕模拟的是镜头泛光（bloom），发生在镜头/传感器上，不被前景天体遮挡——与真实摄影一致。
     */
    private void drawSunGlow(Matrix4f mat) {
        orbitalDrawer.drawSunGlow(mat);
    }



    // ---- RenderType-like state helpers (encapsulate common GL state) ----
    static void setupTransparentBlend() {
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false); RenderSystem.enableDepthTest();
    }
    static void teardownTransparent() { RenderSystem.depthMask(true); }
    static void setupAdditiveBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.depthMask(false); RenderSystem.enableDepthTest();
    }
    static void teardownAdditiveBlend() {
        RenderSystem.depthMask(true); RenderSystem.defaultBlendFunc();
    }
    private void drawOrbitalRings(Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        orbitalDrawer.drawOrbitalRings(mat, cosY, sinY, cosX, sinX, focal, cx, cy);
    }
    /** Compute moon shadow on planet surface vertex. Returns 0 (no shadow) to 1 (full shadow). */
    /** Quick check: does planet pi have any shadow-casting body (moon or parent)? */
    /** Draw a translucent flat ring band (background layer for belts) */
    private void drawBeltBand(Matrix4f mat, float innerR, float outerR, int bands,
            float cosY, float sinY, float cosX, float sinX,
            float r, float g, float b, float a) {
        orbitalDrawer.drawBeltBand(mat, innerR, outerR, bands, cosY, sinY, cosX, sinX, r, g, b, a);
    }
    /** Render 3D rock polyhedra scattered in a belt — filled faces + wireframe edges */
    /** Render 3D rock polyhedra scattered in a belt — 真3D: cameraTo() + GPU透视投影 */
    private void drawScatteredRocks(Matrix4f mat, float[][] particles,
            float cosY, float sinY, float cosX, float sinX) {
        orbitalDrawer.drawScatteredRocks(mat, particles, cosY, sinY, cosX, sinX);
    }
    private float minDistForFocal() {
        Planet fp = solarSystem.get(focalIndex);
        float r = 0;
        for (PlanetLayer l : fp.layers()) if (l.type() == PlanetLayerType.BASE) r = l.radius();
        if (r < 0.01f) r = 1.0f;
        return Math.max(1.5f, r * 1.6f + 0.5f);
    }

    /** 当前恒星系的自由视角边界半径：与 3D/2D 边境虚线同源（gravR * 1.45）。 */
    private float systemBorderRadius() {
        float maxOrb = 0;
        for (int i = 0; i < solarSystem.size(); i++)
            maxOrb = Math.max(maxOrb, solarSystem.get(i).orbitalRadius());
        float starR = 0;
        for (PlanetLayer l : solarSystem.get(0).layers())
            if (l.type() == PlanetLayerType.BASE) { starR = l.radius(); break; }
        float gravR = Math.max(maxOrb * 1.15f, starR * 5f);
        return gravR * 1.45f;
    }
    private float computeOverlayFadeTarget() {
        Planet fp = solarSystem.get(focalIndex);
        float baseR = 0;
        for (PlanetLayer l : fp.layers()) if (l.type() == PlanetLayerType.BASE) baseR = l.radius();
        if (baseR < 0.01f) baseR = 1f;
        float farD = baseR * 6f + 1f;
        float nearD = baseR * 2f + 0.5f;
        float t = (farD - camera.dist()) / (farD - nearD);
        t = clamp(t, 0, 1);
        return t * t * (3f - 2f * t);
    }
    private void drawLayer(Matrix4f mat, RenderTask t, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        Planet p = solarSystem.get(t.pi);
        currentTilt = p.axialTilt();
        lighting.updateForBody(solarSystem, t.pi, simTime, cosY, sinY, cosX, sinX);
        float selfAngle = t.selfRot * simTime;
        float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);
        switch (t.type) {
            case "BASE" -> drawBaseLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX);
            case "CLOUD" -> drawCloudLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy);
            case "ATMOSPHERE" -> drawAtmosphereLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy);
            case "RING" -> drawRing(mat, t, sc, ss, cosY, sinY, cosX, sinX);
            case "WIREFRAME" -> drawWireframe(mat, t.mesh, t.layerR, t.dwx, t.dwy, t.dwz, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy, 0.35f * overlayFade);
            default -> {
                BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
                for (int f = 0; f < t.mesh.faces.length; f++) {
                    int[] fv = t.mesh.faces[f];
                    for (int j = 1; j + 1 < fv.length; j++) {
                        for (int k : new int[]{0, j, j + 1}) {
                            float[] camPos = camera.camera(t.mesh.vertices[fv[k]], t.layerR, t.dwx, t.dwy, t.dwz, sc, ss, currentTilt);
                            float[] c = hsvToRgb(((p.name().hashCode() >> 8) & 0xFF) / 255f * 360, 0.5f, 0.6f);
                            bb.addVertex(mat, camPos[0], camPos[1], camPos[2]).setColor(c[0], c[1], c[2], 1f);
                        }
                    }
                }
                BufferUploader.drawWithShader(bb.buildOrThrow());
            }
        }
    }
    private void drawBaseLayer(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawBaseLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }

    /** BASE 层 GPU 光照：只发 局部坐标+地块albedo+径向法线，光照/阴影全在顶点着色器里算。 */
    private void drawBaseLayerGPU(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawBaseLayerGPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }

    private void drawBaseLayerCPU(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawBaseLayerCPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }

    private void drawCloudLayer(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        layerDrawer.drawCloudLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy);
    }

    /** 空安全的 uniform 设置：着色器可能优化掉未使用的 uniform。 */
    static void setUniform(ShaderInstance sh, String name, float... values) {
        Uniform u = sh.getUniform(name);
        if (u != null) u.set(values);
    }

    /** CLOUD 层 GPU：局部坐标+径向法线+密度阈值(Color.r)，噪声/光照/阴影全在片元着色器。 */
    private void drawCloudLayerGPU(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawCloudLayerGPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }

    /** CLOUD 层 CPU 回退。 */
    private void drawCloudLayerCPU(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawCloudLayerCPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }
    float[] atmosphereColor(int pi) {
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
        layerDrawer.drawAtmosphereLayer(mat, t, sc, ss, cosY, sinY, cosX, sinX, focal, cx, cy);
    }

    /** ATMO 层 GPU：局部坐标+径向法线，rim/日照/阴影全在片元着色器。 */
    private void drawAtmosphereLayerGPU(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawAtmosphereLayerGPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }

    /** ATMO 层 CPU 回退。 */
    private void drawAtmosphereLayerCPU(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawAtmosphereLayerCPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }
    private void drawRing(Matrix4f mat, RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        layerDrawer.drawRing(mat, t, sc, ss, cosY, sinY, cosX, sinX);
    }

    private void drawWireframe(Matrix4f mat, Polyhedron mesh, float layerR, float dwx, float dwy, float dwz, float sc, float ss, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy, float alpha) {
        List<int[]> edges = edgeCache.computeIfAbsent(mesh, SolarSystemView::buildEdges);
        float hw = 0.012f;
        // GPU transform: build modelview, send local-space coords
        buildTransformMatrix(dwx, dwy, dwz, sc, ss, currentTilt, mvTmp);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.set(mvTmp);
        RenderSystem.applyModelViewMatrix();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float R = layerR;
        float[][] vs = mesh.vertices;
        for (int[] e : edges) {
            float[] v0 = vs[e[0]], v1 = vs[e[1]];
            float x0 = v0[0]*R, y0 = v0[1]*R, z0 = v0[2]*R;
            float x1 = v1[0]*R, y1 = v1[1]*R, z1 = v1[2]*R;
            addQuad3DLocal(bb, mat, x0, y0, z0, x1, y1, z1, hw, 0.55f, 0.85f, 1.0f, alpha);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
    }
    /** 3D-local-space quad: perpendicular via cross(edge, normal) — tangent to sphere surface. */
    static boolean addQuad3DLocal(BufferBuilder bb, Matrix4f m,
                                  float x0, float y0, float z0,
                                  float x1, float y1, float z1,
                                  float hw, float r, float g, float b, float a) {
        float ex = x1-x0, ey = y1-y0, ez = z1-z0;
        float mx = (x0+x1)/2, my = (y0+y1)/2, mz = (z0+z1)/2;
        float ml = (float) Math.sqrt(mx*mx+my*my+mz*mz);
        if (ml < 1e-5f) return false;
        mx /= ml; my /= ml; mz /= ml;
        float px = ey*mz - ez*my, py = ez*mx - ex*mz, pz = ex*my - ey*mx;
        float pl = (float) Math.sqrt(px*px+py*py+pz*pz);
        if (pl < 1e-5f) return false;
        px /= pl; py /= pl; pz /= pl;
        float nx = px*hw, ny = py*hw, nz = pz*hw;
        bb.addVertex(m, x0-nx, y0-ny, z0-nz).setColor(r, g, b, a);
        bb.addVertex(m, x0+nx, y0+ny, z0+nz).setColor(r, g, b, a);
        bb.addVertex(m, x1-nx, y1-ny, z1-nz).setColor(r, g, b, a);
        bb.addVertex(m, x0+nx, y0+ny, z0+nz).setColor(r, g, b, a);
        bb.addVertex(m, x1+nx, y1+ny, z1+nz).setColor(r, g, b, a);
        bb.addVertex(m, x1-nx, y1-ny, z1-nz).setColor(r, g, b, a);
        return true;
    }
    /** Corner cap for local-space wireframe edges on a sphere surface. */
    static void appendCornerCapLocal(BufferBuilder bb, Matrix4f m,
                                     float vx, float vy, float vz,
                                     float px0, float py0, float pz0,
                                     float qx, float qy, float qz,
                                     float hw, float r, float g, float b, float a) {
        // Normal at vertex (sphere surface)
        float nl = (float) Math.sqrt(vx*vx+vy*vy+vz*vz);
        if (nl < 1e-5f) return;
        float nx = vx/nl, ny = vy/nl, nz = vz/nl;
        // Edge directions
        float e1x = vx-px0, e1y = vy-py0, e1z = vz-pz0;
        float e2x = qx-vx, e2y = qy-vy, e2z = qz-vz;
        // Project to tangent plane & cross with normal → perpendiculars
        float d1 = e1x*nx+e1y*ny+e1z*nz; e1x -= d1*nx; e1y -= d1*ny; e1z -= d1*nz;
        float d2 = e2x*nx+e2y*ny+e2z*nz; e2x -= d2*nx; e2y -= d2*ny; e2z -= d2*nz;
        float p1x = e1y*nz-e1z*ny, p1y = e1z*nx-e1x*nz, p1z = e1x*ny-e1y*nx;
        float p2x = e2y*nz-e2z*ny, p2y = e2z*nx-e2x*nz, p2z = e2x*ny-e2y*nx;
        float l1 = (float) Math.sqrt(p1x*p1x+p1y*p1y+p1z*p1z);
        float l2 = (float) Math.sqrt(p2x*p2x+p2y*p2y+p2z*p2z);
        if (l1 > 1e-5f) { p1x/=l1; p1y/=l1; p1z/=l1; }
        if (l2 > 1e-5f) { p2x/=l2; p2y/=l2; p2z/=l2; }
        p1x*=hw; p1y*=hw; p1z*=hw; p2x*=hw; p2y*=hw; p2z*=hw;
        bb.addVertex(m, vx-p1x, vy-p1y, vz-p1z).setColor(r,g,b,a);
        bb.addVertex(m, vx+p1x, vy+p1y, vz+p1z).setColor(r,g,b,a);
        bb.addVertex(m, vx+p2x, vy+p2y, vz+p2z).setColor(r,g,b,a);
        bb.addVertex(m, vx-p1x, vy-p1y, vz-p1z).setColor(r,g,b,a);
        bb.addVertex(m, vx+p2x, vy+p2y, vz+p2z).setColor(r,g,b,a);
        bb.addVertex(m, vx-p2x, vy-p2y, vz-p2z).setColor(r,g,b,a);
    }
    static boolean addQuad3D(BufferBuilder bb, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, float hw, float r, float g, float b, float a) {
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


    private void drawTechMarkers(GuiGraphics g2, Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focalLength, float cx, float cy) {
        techTreeDrawer.drawTechMarkers(g2, mat, cosY, sinY, cosX, sinX, focalLength, cx, cy);
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
        techTreeDrawer.drawTechHighlight(mat, cosY, sinY, cosX, sinX, focalLength, cx, cy);
    }

    static int tierColor(int tier) {
        return switch (tier) {
            case 0 -> 0x4FC3F7;
            case 1 -> 0x81C784;
            case 2 -> 0xBA68C8;
            case 3 -> 0xFFB74D;
            default -> 0x4DB6AC;
        };
    }
    static void appendCornerCap(BufferBuilder bb, Matrix4f m,
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
        techTreeDrawer.drawLabels(g, vx, vy, vw, vh, cosY, sinY, cosX, sinX, focal, cx, cy);
    }
    private int pickPlanet(int mx, int my, float focalLength, float pcx, float pcy) {
        return techTreeDrawer.pickPlanet(mx, my, focalLength, pcx, pcy);
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
    @Override
    protected void onRemoved() {
        super.onRemoved();
        if (layerDrawer != null) layerDrawer.closeVBOs();
        if (orbitalDrawer != null) orbitalDrawer.closeVBOs();
    }

    /** 供星图/外部界面跳转到指定恒星系。 */
    public void enterSystem(int newIndex) {
        if (newIndex >= 0 && newIndex < StarSystemCatalog.size()) {
            switchToSystem(newIndex);
        }
    }

    public int getSystemIndex() { return systemIndex; }
    public int getFocalIndex() { return focalIndex; }
    public float getSimTime() { return simTime; }
    public SolarSystem getSolarSystem() { return solarSystem; }


}