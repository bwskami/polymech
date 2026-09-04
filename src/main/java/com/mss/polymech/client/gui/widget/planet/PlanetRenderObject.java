package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.techtree.Polyhedron;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一颗星球的独立渲染对象（BASE 层迁移）。
 * <p>
 * 持有星球表面数据（网格、地块 albedo、材质、高度场），负责构建 BASE 层静态 VBO，
 * 并用 PlanetShaders 的 GPU 光照路径绘制。旧构造（仅 PlanetVisual）保持简单球体回退。
 * </p>
 */
public final class PlanetRenderObject {

    /** 为 null 时走旧版简单球体渲染。 */
    private final Planet planet;
    private final PlanetVisual visual;
    private final double radius;
    private final double posX;
    private final double posY;
    private final double posZ;

    /** BASE 层表面数据（planet == null 时均为 null）。 */
    private final Polyhedron mesh;
    private final PlanetHeight planetHeight;
    private final float[][] faceColors;
    private final SurfaceMaterial[] faceMaterials;
    private final int surfaceSeed;

    /** 大气层外半径（米）。0 表示不渲染大气层。 */
    private final double atmosphereRadius;

    private VertexBuffer baseVbo;
    private VertexBuffer atmoVbo;

    /** 云层棱柱厚度（相对行星半径）：只生成顶/底两个多边形面，不生成侧壁。 */
    static final float CLOUD_THICKNESS_FRACTION = 0.006f;

    /** 云层（从 Planet.layers 收集，已按半径排序）。 */
    private final List<PlanetLayer> cloudLayers = new ArrayList<>();
    private final Map<PlanetLayer, VertexBuffer> cloudVbos = new HashMap<>();
    private float[][] cloudLayerDensities;
    private float[][] cloudFaceNormals;
    /** 光环层（土星/天王星/海王星）。 */
    private final List<PlanetLayer> ringLayers = new ArrayList<>();
    /** 阴影投射天体（真实天体）。 */
    private final List<RealAstroData> casterBodies;

    /** 自转速度（rad/s），用于太空维度里让行星/云层动起来。 */
    private final float rotationSpeed;

    /** 复用的临时数据，避免每帧分配。 */
    private final Matrix4f modelView = new Matrix4f();
    private final float[] viewDir = new float[3];
    private final float[] localSun = new float[3];
    private final float[] localView = new float[3];
    private final float[] tmpCaster = new float[3];

    /** 真实天体与 GUI 星图 SolarSystem 的 pi 索引一致，保证地表/云层噪声和 GUI 星图完全同款。 */
    private static int surfaceSeedFor(String planetName) {
        return switch (planetName) {
            case "sun" -> 0;
            case "mercury" -> 1;
            case "venus" -> 2;
            case "earth" -> 3;
            case "moon" -> 4;
            case "mars" -> 5;
            case "jupiter" -> 8;
            case "saturn" -> 13;
            case "uranus" -> 16;
            case "neptune" -> 17;
            default -> Math.floorMod(planetName.hashCode(), 1_000_000);
        };
    }

    /** 云层噪声种子使用 GUI 星图里的玩具半径，让太空云层图案和 GUI 星图完全一致。 */
    private float guiCloudSeedRadius(PlanetLayer layer) {
        double ratio = layer.radius() / radius;
        return switch (planet.name()) {
            case "earth" -> (float) (1.92 * ratio);
            case "venus" -> (float) (1.10 * ratio);
            default -> layer.radius();
        };
    }

    /** 旧版简单构造：只携带视觉属性，渲染时走 SolarSystemRenderer 的纯色球。 */
    public PlanetRenderObject(PlanetVisual visual, double radius, double posX, double posY, double posZ) {
        this.planet = null;
        this.visual = visual;
        this.radius = radius;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.mesh = null;
        this.planetHeight = null;
        this.faceColors = null;
        this.faceMaterials = null;
        this.surfaceSeed = 0;
        this.atmosphereRadius = 0;
        this.rotationSpeed = 0f;
        this.casterBodies = List.of();
    }

    /** BASE 层构造：使用 Planet 携带的网格、颜色提供器、高度场和材质。 */
    public PlanetRenderObject(Planet planet, double radius, double posX, double posY, double posZ) {
        this(planet, radius, 0, posX, posY, posZ);
    }

    /** BASE + ATMO 构造：atmosphereRadius 为大气层外半径（米），小于等于 radius 时不渲染大气。 */
    public PlanetRenderObject(Planet planet, double radius, double atmosphereRadius,
                              double posX, double posY, double posZ) {
        this(planet, radius, atmosphereRadius, List.of(), posX, posY, posZ);
    }

    /** BASE + ATMO + 阴影投射者构造。 */
    public PlanetRenderObject(Planet planet, double radius, double atmosphereRadius,
                              List<RealAstroData> casterBodies,
                              double posX, double posY, double posZ) {
        this.planet = planet;
        this.visual = planet.visual();
        this.radius = radius;
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.mesh = planet.baseMesh();
        this.surfaceSeed = surfaceSeedFor(planet.name());
        this.planetHeight = new PlanetHeight(surfaceSeed, mesh, planet.heightScale(), 5.0f);
        this.faceColors = new float[mesh.faces.length][3];
        this.faceMaterials = new SurfaceMaterial[mesh.faces.length];
        this.atmosphereRadius = (atmosphereRadius > radius && visual.hasAtmosphere()) ? atmosphereRadius : 0;
        this.rotationSpeed = planet.defaultRotationSpeed();
        this.casterBodies = List.copyOf(casterBodies);
        for (PlanetLayer layer : planet.layers()) {
            if (layer.type() == PlanetLayerType.CLOUD && layer.radius() > radius) {
                cloudLayers.add(layer);
            } else if (layer.type() == PlanetLayerType.RING) {
                ringLayers.add(layer);
            }
        }
        precomputeSurface();
    }

    public PlanetVisual visual() {
        return visual;
    }

    public double radius() {
        return radius;
    }

    public double posX() {
        return posX;
    }

    public double posY() {
        return posY;
    }

    public double posZ() {
        return posZ;
    }

    /** 大气层外半径（米）；0 表示无大气。 */
    public double atmosphereRadius() {
        return atmosphereRadius;
    }

    /** 天体名称，与 {@link RealAstroData#byId(String)} 一致；旧版简单球体回退时为空串。 */
    public String planetName() {
        return planet != null ? planet.name() : "";
    }

    public void render(PlanetRenderParams params) {
        if (radius <= 0 || visual.baseColor() == null) return;
        if (planet == null || !PlanetShaders.isReady() || params.lighting() == null) {
            SolarSystemRenderer.renderBody(
                    params.viewMatrix(),
                    posX - params.cameraX(),
                    posY - params.cameraY(),
                    posZ - params.cameraZ(),
                    radius,
                    visual);
            return;
        }
        drawBaseLayerGpu(params);
    }

    public boolean hasAtmosphere() {
        return atmosphereRadius > 0 && visual.atmosphereColor() != null;
    }

    public boolean hasClouds() {
        return !cloudLayers.isEmpty();
    }

    /** 绘制 CLOUD 层（半透明；与 BASE 同批绘制，保持 GUI 的层次顺序）。 */
    public void renderClouds(PlanetRenderParams params) {
        if (!hasClouds() || params.lighting() == null || !PlanetShaders.isCloudReady()) return;
        for (int i = 0; i < cloudLayers.size(); i++) {
            drawCloudGpu(params, cloudLayers.get(i), i);
        }
    }

    public boolean hasRings() {
        return !ringLayers.isEmpty();
    }

    /** 绘制 RING 层（半透明；调用方需已设置 depthMask(false)）。 */
    public void renderRings(PlanetRenderParams params) {
        if (!hasRings() || params.lighting() == null) return;
        for (PlanetLayer ring : ringLayers) {
            drawRingGpu(params, ring);
        }
    }

    /** 绘制 ATMO 层（半透明；调用方需已设置 depthMask(false)）。 */
    public void renderAtmosphere(PlanetRenderParams params) {
        if (!hasAtmosphere() || params.lighting() == null || !PlanetShaders.isAtmoReady()) return;
        drawAtmosphereGpu(params);
    }

    /** 释放 BASE / ATMO 层 VBO（移除渲染对象时调用，避免 GPU 内存泄漏）。 */
    public void close() {
        if (baseVbo != null) {
            baseVbo.close();
            baseVbo = null;
        }
        if (atmoVbo != null) {
            atmoVbo.close();
            atmoVbo = null;
        }
        for (VertexBuffer vb : cloudVbos.values()) {
            vb.close();
        }
        cloudVbos.clear();
    }

    // ==================== BASE 层预计算 ====================

    private void precomputeSurface() {
        long seed = 0x5EED1234L + surfaceSeed * 0x1234567L;
        Noise3 noise = new Noise3(seed);
        PlanetColorProvider provider = planet.colorProvider();
        boolean hasOcean = false;
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            float cx = 0, cy = 0, cz = 0;
            for (int v : fv) {
                cx += mesh.vertices[v][0];
                cy += mesh.vertices[v][1];
                cz += mesh.vertices[v][2];
            }
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (len < 1e-6f) len = 1f;
            cx /= len;
            cy /= len;
            cz /= len;
            float height = planetHeight.rawHeight(cx, cy, cz);
            float[] color = provider.compute(f, cx, cy, cz, Math.abs(cy), height, noise);
            faceColors[f][0] = clamp(color[0], 0f, 1f);
            faceColors[f][1] = clamp(color[1], 0f, 1f);
            faceColors[f][2] = clamp(color[2], 0f, 1f);
            SurfaceMaterial material = provider.material(f, cx, cy, cz, Math.abs(cy), height, noise);
            faceMaterials[f] = material;
            if (material == SurfaceMaterial.OCEAN) hasOcean = true;
        }
        if (hasOcean) planetHeight.clampToSea = true;
    }

    // ==================== BASE 层 VBO ====================

    /** 构建 BASE 层静态 VBO：局部坐标（×radius）+ 地块 albedo + 法线。 */
    private VertexBuffer getOrCreateBaseVbo() {
        if (baseVbo != null) return baseVbo;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        Matrix4f mat = new Matrix4f();
        float R = (float) radius;
        float[][] vs = mesh.vertices;
        float hs = planet.heightScale();
        PlanetHeight ph = planetHeight;
        float[][] smoothN = null;
        float[][] dispV = null;
        if (hs > 0f) {
            smoothN = new float[mesh.vertices.length][];
            dispV = new float[mesh.vertices.length][];
            for (int i = 0; i < mesh.vertices.length; i++) {
                dispV[i] = ph.displaced(vs[i][0], vs[i][1], vs[i][2], R);
                smoothN[i] = smoothTerrainNormal(vs[i][0], vs[i][1], vs[i][2], R, ph, hs);
            }
        }
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            int kn = fv.length;
            float fnx = 0, fny = 0, fnz = 0;
            for (int v : fv) {
                fnx += vs[v][0];
                fny += vs[v][1];
                fnz += vs[v][2];
            }
            float fl = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (fl < 1e-6f) continue;
            fnx /= fl;
            fny /= fl;
            fnz /= fl;
            float[] alb = faceColors[f];
            SurfaceMaterial material = faceMaterials[f];
            float specAlpha = (material == SurfaceMaterial.OCEAN || material == SurfaceMaterial.ICE) ? 1f : 0f;
            if (hs > 0f) {
                if (kn == 3) {
                    addTriSmooth(bb, mat,
                            dispV[fv[0]], dispV[fv[1]], dispV[fv[2]],
                            smoothN[fv[0]], smoothN[fv[1]], smoothN[fv[2]], alb, specAlpha);
                } else {
                    float[] pc = ph.displaced(fnx, fny, fnz, R);
                    float[] nc = smoothTerrainNormal(fnx, fny, fnz, R, ph, hs);
                    for (int k = 0; k < kn; k++) {
                        int a1 = (k + 1) % kn;
                        addTriSmooth(bb, mat, pc, dispV[fv[k]], dispV[fv[a1]],
                                nc, smoothN[fv[k]], smoothN[fv[a1]], alb, specAlpha);
                    }
                }
            } else if (kn == 3) {
                for (int k = 0; k < 3; k++) {
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(alb[0], alb[1], alb[2], specAlpha)
                            .setNormal(vs[vi][0], vs[vi][1], vs[vi][2]);
                }
            } else {
                float cx = fnx * R, cy = fny * R, cz = fnz * R;
                for (int k = 0; k < kn; k++) {
                    int a1 = (k + 1) % kn;
                    bb.addVertex(mat, cx, cy, cz).setColor(alb[0], alb[1], alb[2], specAlpha).setNormal(fnx, fny, fnz);
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(alb[0], alb[1], alb[2], specAlpha).setNormal(vs[vi][0], vs[vi][1], vs[vi][2]);
                    int vj = fv[a1];
                    bb.addVertex(mat, vs[vj][0] * R, vs[vj][1] * R, vs[vj][2] * R)
                            .setColor(alb[0], alb[1], alb[2], specAlpha).setNormal(vs[vj][0], vs[vj][1], vs[vj][2]);
                }
            }
        }
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(bb.buildOrThrow());
        VertexBuffer.unbind();
        baseVbo = vb;
        return vb;
    }

    /** 发射一个三角形，三个顶点各自使用预计算的平滑法线（太空场景避免面片感）。 */
    private static void addTriSmooth(BufferBuilder bb, Matrix4f mat,
                                     float[] p0, float[] p1, float[] p2,
                                     float[] n0, float[] n1, float[] n2,
                                     float[] alb, float alpha) {
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(n0[0], n0[1], n0[2]);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(n1[0], n1[1], n1[2]);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(n2[0], n2[1], n2[2]);
    }

    /** 用高度场在顶点方向的切平面梯度计算平滑地形法线，避免每个三角面各算一个平直法线。 */
    private static float[] smoothTerrainNormal(float x, float y, float z, float R, PlanetHeight ph, float hs) {
        float[] v = normalize3(new float[]{x, y, z});
        float h0 = ph.rawHeight(v[0], v[1], v[2]);
        float r0 = R * (1f + hs * h0);
        float[] p0 = {v[0] * r0, v[1] * r0, v[2] * r0};

        float[] up = Math.abs(v[1]) < 0.9f ? new float[]{0f, 1f, 0f} : new float[]{1f, 0f, 0f};
        float[] t1 = normalize3(cross3(up, v));
        float[] t2 = cross3(v, t1);
        float eps = 0.015f;

        float[] v1 = normalize3(new float[]{v[0] + t1[0] * eps, v[1] + t1[1] * eps, v[2] + t1[2] * eps});
        float[] v2 = normalize3(new float[]{v[0] + t2[0] * eps, v[1] + t2[1] * eps, v[2] + t2[2] * eps});
        float h1 = ph.rawHeight(v1[0], v1[1], v1[2]);
        float h2 = ph.rawHeight(v2[0], v2[1], v2[2]);
        float r1 = R * (1f + hs * h1);
        float r2 = R * (1f + hs * h2);
        float[] p1 = {v1[0] * r1, v1[1] * r1, v1[2] * r1};
        float[] p2 = {v2[0] * r2, v2[1] * r2, v2[2] * r2};

        float[] n = cross3(sub3(p1, p0), sub3(p2, p0));
        n = normalize3(n);
        if (n[0] * v[0] + n[1] * v[1] + n[2] * v[2] < 0f) {
            n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2];
        }
        return n;
    }

    private static float[] normalize3(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-6f) return new float[]{0f, 1f, 0f};
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static float[] cross3(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static float[] sub3(float[] a, float[] b) {
        return new float[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    /** 发射一个三角形，法线从位移后的几何重新计算（平面着色）。 */
    private static void addTriFlat(BufferBuilder bb, Matrix4f mat, float[] p0, float[] p1, float[] p2, float[] alb, float alpha) {
        float ux = p1[0] - p0[0], uy = p1[1] - p0[1], uz = p1[2] - p0[2];
        float vx = p2[0] - p0[0], vy = p2[1] - p0[1], vz = p2[2] - p0[2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float nl = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-6f) {
            nx = 0;
            ny = 1;
            nz = 0;
        } else {
            nx /= nl;
            ny /= nl;
            nz /= nl;
        }
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(nx, ny, nz);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(nx, ny, nz);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(nx, ny, nz);
    }


    // ==================== ATMO 层 VBO ====================

    /** 构建 ATMO 层静态 VBO：全部面，颜色白，法线为径向/面法线。 */
    private VertexBuffer getOrCreateAtmoVbo() {
        if (atmoVbo != null) return atmoVbo;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        Matrix4f mat = new Matrix4f();
        float R = (float) atmosphereRadius;
        float[][] vs = mesh.vertices;
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            int kn = fv.length;
            float fnx = 0, fny = 0, fnz = 0;
            for (int v : fv) {
                fnx += vs[v][0];
                fny += vs[v][1];
                fnz += vs[v][2];
            }
            float fl = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (fl < 1e-6f) continue;
            fnx /= fl;
            fny /= fl;
            fnz /= fl;
            if (kn == 3) {
                for (int k = 0; k < 3; k++) {
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(1f, 1f, 1f, 1f).setNormal(vs[vi][0], vs[vi][1], vs[vi][2]);
                }
            } else {
                float cnx = fnx * R, cny = fny * R, cnz = fnz * R;
                for (int k = 0; k < kn; k++) {
                    int a1 = (k + 1) % kn;
                    bb.addVertex(mat, cnx, cny, cnz).setColor(1f, 1f, 1f, 1f).setNormal(fnx, fny, fnz);
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(1f, 1f, 1f, 1f).setNormal(vs[vi][0], vs[vi][1], vs[vi][2]);
                    int vj = fv[a1];
                    bb.addVertex(mat, vs[vj][0] * R, vs[vj][1] * R, vs[vj][2] * R)
                            .setColor(1f, 1f, 1f, 1f).setNormal(vs[vj][0], vs[vj][1], vs[vj][2]);
                }
            }
        }
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(bb.buildOrThrow());
        VertexBuffer.unbind();
        atmoVbo = vb;
        return vb;
    }


    // ==================== CLOUD 层 VBO ====================

    /**
     * 一次性计算所有云层的逐面密度，并做全局防空洞：
     * 只有当某张脸在所有云层里都没有云时，才选该脸噪声值最高的那层补一个软边云。
     * 这样单层可以有洞，但整颗星球不会出现大范围无云区。
     */
    private void computeCloudDensities() {
        if (cloudLayerDensities != null) return;

        int faceCount = mesh.faces.length;
        int layerCount = cloudLayers.size();
        cloudLayerDensities = new float[layerCount][faceCount];
        cloudFaceNormals = new float[faceCount][];
        float[][] rawVals = new float[layerCount][faceCount];
        float[][] vs = mesh.vertices;

        // 先算好所有面法线，所有云层共用。
        for (int f = 0; f < faceCount; f++) {
            int[] fv = mesh.faces[f];
            float fnx = 0, fny = 0, fnz = 0;
            for (int v : fv) {
                fnx += vs[v][0];
                fny += vs[v][1];
                fnz += vs[v][2];
            }
            float fl = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (fl < 1e-6f) continue;
            cloudFaceNormals[f] = new float[]{fnx / fl, fny / fl, fnz / fl};
        }

        for (int li = 0; li < layerCount; li++) {
            PlanetLayer layer = cloudLayers.get(li);
            float seedLayerR = guiCloudSeedRadius(layer);
            long layerSeed = 0x5EED1234L + surfaceSeed * 0x1234567L + (long) (seedLayerR * 1000.0) * 0x9E3779B9L;
            Noise3 layerNoise = new Noise3(layerSeed);
            float[] faceDensity = cloudLayerDensities[li];
            float[] raw = rawVals[li];

            for (int f = 0; f < faceCount; f++) {
                float[] fn = cloudFaceNormals[f];
                if (fn == null) continue;
                float fnx = fn[0], fny = fn[1], fnz = fn[2];

                float cloudVal;
                float threshold;
                int style = li % 3;
                if (style == 0) {
                    // 横向长条带：水平方向频率低（云条长），纵向频率高（云条短/密）
                    cloudVal = layerNoise.fbm(fnx * 2.0f + 7.3f, fny * 8.0f + 13.7f, fnz * 2.0f + 3.1f);
                    threshold = 0.56f;
                } else if (style == 1) {
                    // 散碎小团块：各方向频率都较高
                    cloudVal = layerNoise.fbm(fnx * 6.0f + 11.3f, fny * 6.0f + 17.7f, fnz * 6.0f + 5.9f);
                    threshold = 0.60f;
                } else {
                    // 细长条带/丝缕
                    float a = layerNoise.fbm(fnx * 2.5f + 3.1f, fny * 12.0f + 9.2f, fnz * 2.5f + 5.7f);
                    float b = layerNoise.fbm(fnx * 8.0f + 17.3f, fny * 8.0f + 2.9f, fnz * 8.0f + 11.1f);
                    cloudVal = a * 0.75f + b * 0.25f;
                    threshold = 0.64f;
                }
                threshold += Math.abs(fny) * 0.08f;

                raw[f] = cloudVal;
                if (cloudVal >= threshold) {
                    faceDensity[f] = 1f;
                } else if (cloudVal >= threshold - 0.05f) {
                    faceDensity[f] = 0.45f;
                }
            }
        }

        // 全局防空洞：只补没有任何云层覆盖的脸。
        for (int f = 0; f < faceCount; f++) {
            boolean any = false;
            for (int li = 0; li < layerCount; li++) {
                if (cloudLayerDensities[li][f] > 0f) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                int best = 0;
                float bestRaw = -1f;
                for (int li = 0; li < layerCount; li++) {
                    if (rawVals[li][f] > bestRaw) {
                        bestRaw = rawVals[li][f];
                        best = li;
                    }
                }
                if (bestRaw >= 0f) cloudLayerDensities[best][f] = 0.45f;
            }
        }

        // 控制层间重叠：同一张脸最多两层云覆盖；若超过，则去掉噪声值最低的层。
        for (int f = 0; f < faceCount; f++) {
            int covered = 0;
            for (int li = 0; li < layerCount; li++) {
                if (cloudLayerDensities[li][f] > 0f) covered++;
            }
            while (covered > 2) {
                int worst = -1;
                float worstRaw = Float.MAX_VALUE;
                for (int li = 0; li < layerCount; li++) {
                    if (cloudLayerDensities[li][f] > 0f && rawVals[li][f] < worstRaw) {
                        worstRaw = rawVals[li][f];
                        worst = li;
                    }
                }
                if (worst == -1) break;
                cloudLayerDensities[worst][f] = 0f;
                covered--;
            }
        }
    }

    /**
     * 构建单层 CLOUD 静态 VBO：CPU 噪声整面分类，核心面/一圈多边形软边面/剔除。
     * 保留的面生成正多边形顶/底两个端面，并在云区外围边界生成侧壁。
     */
    private VertexBuffer getOrCreateCloudVbo(PlanetLayer layer, int cloudIdx) {
        VertexBuffer cached = cloudVbos.get(layer);
        if (cached != null) return cached;

        float layerR = layer.radius();

        computeCloudDensities();
        float[] faceDensity = cloudLayerDensities[cloudIdx];
        float[][] faceNormal = cloudFaceNormals;

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        Matrix4f mat = new Matrix4f();
        float R = layerR;
        float halfT = CLOUD_THICKNESS_FRACTION * 0.5f * (float) radius;
        float innerR = Math.max(R - halfT, (float) radius * 1.001f);
        float outerR = R + halfT;
        float[][] vs = mesh.vertices;
        float hs = planet.heightScale();
        PlanetHeight ph = planetHeight;
        int faceCount = mesh.faces.length;

        // 第二遍：建立边到面的邻接表，用于只在外围边界生成侧壁。
        java.util.HashMap<Long, int[]> edgeToFaces = new java.util.HashMap<>();
        for (int f = 0; f < faceCount; f++) {
            int[] fv = mesh.faces[f];
            for (int k = 0; k < fv.length; k++) {
                int a = fv[k], b = fv[(k + 1) % fv.length];
                long key = edgeKey(a, b);
                int[] arr = edgeToFaces.get(key);
                if (arr == null) {
                    arr = new int[]{-1, -1};
                    edgeToFaces.put(key, arr);
                }
                if (arr[0] == -1) arr[0] = f;
                else arr[1] = f;
            }
        }

        int emitted = 0;
        for (int f = 0; f < faceCount; f++) {
            float density = faceDensity[f];
            if (density <= 0f) continue;
            int[] fv = mesh.faces[f];
            int kn = fv.length;
            float[] fn = faceNormal[f];
            if (fn == null) continue;
            float fnx = fn[0], fny = fn[1], fnz = fn[2];

            boolean[] boundary = new boolean[kn];
            for (int k = 0; k < kn; k++) {
                int a = fv[k], b = fv[(k + 1) % kn];
                int[] arr = edgeToFaces.get(edgeKey(a, b));
                if (arr == null) {
                    boundary[k] = true;
                    continue;
                }
                int nb = arr[0] == f ? arr[1] : arr[0];
                boundary[k] = nb < 0 || faceDensity[nb] <= 0f;
            }

            emitCloudSlab(bb, mat, vs, fv, fnx, fny, fnz, innerR, outerR, ph, hs, density, boundary);
            emitted++;
        }
        if (emitted == 0) return null;
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(bb.buildOrThrow());
        VertexBuffer.unbind();
        cloudVbos.put(layer, vb);
        return vb;
    }



    /**
     * 发射一个云层棱柱：画底面和顶面两个正多边形端面。
     * 只有边界边（一侧是云、另一侧是空）才生成侧壁，内部相邻棱柱之间不生成衔接面。
     */
    private void emitCloudSlab(BufferBuilder bb, Matrix4f mat, float[][] vs, int[] fv,
                               float fnx, float fny, float fnz,
                               float innerR, float outerR, PlanetHeight ph, float hs,
                               float density, boolean[] boundary) {
        int kn = fv.length;
        float[][] inner = new float[kn][];
        float[][] outer = new float[kn][];
        for (int k = 0; k < kn; k++) {
            int vi = fv[k];
            inner[k] = cloudPos(vs[vi][0], vs[vi][1], vs[vi][2], innerR, ph, hs);
            outer[k] = cloudPos(vs[vi][0], vs[vi][1], vs[vi][2], outerR, ph, hs);
        }

        // 先底面（更远）后顶面（更近），普通 alpha 混合顺序正确。
        emitCloudCap(bb, mat, vs, fv, fnx, fny, fnz, inner, innerR, ph, hs, density);
        emitCloudCap(bb, mat, vs, fv, fnx, fny, fnz, outer, outerR, ph, hs, density);

        // 仅外围边界侧壁，显示棱柱厚度；内部衔接面不渲染。
        for (int k = 0; k < kn; k++) {
            if (!boundary[k]) continue;
            int a1 = (k + 1) % kn;
            float[] sn = edgeNormal(inner[k], outer[k], inner[a1], outer[a1]);
            addCloudTri(bb, mat, inner[k], outer[k], outer[a1], sn[0], sn[1], sn[2], density);
            addCloudTri(bb, mat, inner[k], outer[a1], inner[a1], sn[0], sn[1], sn[2], density);
        }
    }

    private static long edgeKey(int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    /** 侧壁法线取四个顶点的平均方向，接近该云块边缘的径向朝外方向。 */
    private static float[] edgeNormal(float[] a, float[] b, float[] c, float[] d) {
        float x = a[0] + b[0] + c[0] + d[0];
        float y = a[1] + b[1] + c[1] + d[1];
        float z = a[2] + b[2] + c[2] + d[2];
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) return new float[]{0f, 1f, 0f};
        return new float[]{x / len, y / len, z / len};
    }

    /** 发射棱柱的一个正多边形端面（底面或顶面）。 */
    private void emitCloudCap(BufferBuilder bb, Matrix4f mat, float[][] vs, int[] fv,
                              float fnx, float fny, float fnz,
                              float[][] pos, float capR, PlanetHeight ph, float hs,
                              float density) {
        int kn = fv.length;
        if (kn == 3) {
            addCloudTri(bb, mat, pos[0], pos[1], pos[2],
                    vs[fv[0]], vs[fv[1]], vs[fv[2]], density, density, density);
        } else {
            float[] pc = cloudPos(fnx, fny, fnz, capR, ph, hs);
            for (int k = 0; k < kn; k++) {
                int a1 = (k + 1) % kn;
                addCloudTri(bb, mat, pc, pos[k], pos[a1],
                        fnx, fny, fnz, vs[fv[k]], vs[fv[a1]], density, density, density);
            }
        }
    }

    private float[] cloudPos(float x, float y, float z, float r, PlanetHeight ph, float hs) {
        if (hs > 0f) return ph.displaced(x, y, z, r);
        return new float[]{x * r, y * r, z * r};
    }

    /** 发射一个云层三角形：三个顶点统一法线和密度（用于侧壁）。 */
    private static void addCloudTri(BufferBuilder bb, Matrix4f mat, float[] p0, float[] p1, float[] p2,
                                    float nx, float ny, float nz, float density) {
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(density, 0, 0, 1f).setNormal(nx, ny, nz);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(density, 0, 0, 1f).setNormal(nx, ny, nz);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(density, 0, 0, 1f).setNormal(nx, ny, nz);
    }

    /** 发射一个云层三角形：三个顶点各自使用径向法线和密度，光照连续。 */
    private static void addCloudTri(BufferBuilder bb, Matrix4f mat, float[] p0, float[] p1, float[] p2,
                                    float[] n0, float[] n1, float[] n2,
                                    float d0, float d1, float d2) {
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(d0, 0, 0, 1f).setNormal(n0[0], n0[1], n0[2]);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(d1, 0, 0, 1f).setNormal(n1[0], n1[1], n1[2]);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(d2, 0, 0, 1f).setNormal(n2[0], n2[1], n2[2]);
    }

    /** 扇形三角化版本：中心顶点使用面法线和中心密度，边缘顶点使用各自径向法线和密度。 */
    private static void addCloudTri(BufferBuilder bb, Matrix4f mat, float[] pc, float[] p1, float[] p2,
                                    float fnx, float fny, float fnz, float[] n1, float[] n2,
                                    float dc, float d1, float d2) {
        bb.addVertex(mat, pc[0], pc[1], pc[2]).setColor(dc, 0, 0, 1f).setNormal(fnx, fny, fnz);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(d1, 0, 0, 1f).setNormal(n1[0], n1[1], n1[2]);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(d2, 0, 0, 1f).setNormal(n2[0], n2[1], n2[2]);
    }

    // ==================== GPU 绘制 ====================

    private void applyCasterUniforms(ShaderInstance sh, PlanetRenderParams params) {
        applyCasterUniforms(sh, params, 0f);
    }

    /**
     * 设置阴影投射天体 uniform。
     * 当行星/云层绕 Y 轴自转后，着色器在局部系里算阴影，
     * 因此投射天体的相对位置也要同步旋转到局部系。
     */
    private void applyCasterUniforms(ShaderInstance sh, PlanetRenderParams params, float angle) {
        int nC = Math.min(casterBodies.size(), 4);
        sh.getUniform("CasterCount").set((float) nC);
        for (int i = 0; i < 4; i++) {
            if (i < nC) {
                RealAstroData caster = casterBodies.get(i);
                double[] cwp = caster.realPositionAt(params.simTime());
                rotateY((float) (cwp[0] - posX),
                        (float) (cwp[1] - posY),
                        (float) (cwp[2] - posZ),
                        -angle, tmpCaster);
                sh.getUniform("CasterRel" + i).set(tmpCaster[0], tmpCaster[1], tmpCaster[2]);
                sh.getUniform("CasterRad" + i).set((float) caster.radiusMeters());
            } else {
                sh.getUniform("CasterRel" + i).set(0f, 0f, 0f);
                sh.getUniform("CasterRad" + i).set(0f);
            }
        }
    }

    private float computeSunVisibility(PlanetRenderParams params) {
        if (casterBodies.isEmpty()) return 1f;
        float sunX = params.lighting().dirX(), sunY = params.lighting().dirY(), sunZ = params.lighting().dirZ();
        float sh = occlusionAt(sunX * (float) radius, sunY * (float) radius, sunZ * (float) radius, params);
        return Math.max(0f, 1f - sh);
    }

    private float occlusionAt(float vx, float vy, float vz, PlanetRenderParams params) {
        float sunX = params.lighting().dirX(), sunY = params.lighting().dirY(), sunZ = params.lighting().dirZ();
        float maxShadow = 0f;
        for (RealAstroData caster : casterBodies) {
            double[] cwp = caster.realPositionAt(params.simTime());
            float casterRelX = (float) (cwp[0] - posX);
            float casterRelY = (float) (cwp[1] - posY);
            float casterRelZ = (float) (cwp[2] - posZ);
            float dx = vx - casterRelX;
            float dy = vy - casterRelY;
            float dz = vz - casterRelZ;
            float dotSun = dx * sunX + dy * sunY + dz * sunZ;
            if (dotSun > 0) continue;
            float perpX = dx - dotSun * sunX;
            float perpY = dy - dotSun * sunY;
            float perpZ = dz - dotSun * sunZ;
            float perpDist = (float) Math.sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ);
            float casterR = (float) caster.radiusMeters();
            float effR = casterR * (1f + Math.abs(dotSun) * 0.025f);
            if (perpDist < effR) {
                maxShadow = Math.max(maxShadow, 1f);
            } else if (perpDist < effR * 1.6f) {
                maxShadow = Math.max(maxShadow, 1f - (perpDist - effR) / (effR * 0.6f));
            }
        }
        return maxShadow;
    }

    private void drawBaseLayerGpu(PlanetRenderParams params) {
        float angle = (float) ((rotationSpeed * params.simTime()) % (Math.PI * 2.0));
        modelView.set(params.viewMatrix());
        modelView.translate(
                (float) (posX - params.cameraX()),
                (float) (posY - params.cameraY()),
                (float) (posZ - params.cameraZ()));
        modelView.rotateY(angle);

        computeViewDir(params);
        // 光照/视线方向转到旋转后的局部系，保证晨昏线和镜面高光不随自转漂移。
        rotateY(params.lighting().dirX(), params.lighting().dirY(), params.lighting().dirZ(), -angle, localSun);
        rotateY(viewDir[0], viewDir[1], viewDir[2], -angle, localView);

        ShaderInstance sh = PlanetShaders.planetShader();
        sh.getUniform("SunDir").set(localSun[0], localSun[1], localSun[2]);
        float intensity = params.lighting().intensity();
        sh.getUniform("ViewDir").set(localView[0], localView[1], localView[2]);
        sh.getUniform("Intensity").set(intensity);
        sh.getUniform("IsSun").set(visual.isGlowing() ? 1f : 0f);
        sh.getUniform("SunVisibility").set(computeSunVisibility(params));
        applyCasterUniforms(sh, params, angle);

        // 卫星地照：比自身大的投射天体作为反射光源（例如月球受地球反光）
        float reflStrength = 0f;
        float prx = 0f, pry = 0f, prz = 0f;
        for (RealAstroData caster : casterBodies) {
            if (caster.radiusMeters() > radius) {
                double[] cwp = caster.realPositionAt(params.simTime());
                float cwx = (float) (cwp[0] - posX);
                float cwy = (float) (cwp[1] - posY);
                float cwz = (float) (cwp[2] - posZ);
                rotateY(cwx, cwy, cwz, -angle, tmpCaster);
                float dist = (float) Math.sqrt(cwx * cwx + cwy * cwy + cwz * cwz);
                reflStrength = Math.max(0f, Math.min(0.5f, (float) caster.radiusMeters() / Math.max(dist, 1f) * 0.8f));
                prx = tmpCaster[0]; pry = tmpCaster[1]; prz = tmpCaster[2];
                break;
            }
        }
        sh.getUniform("ParentRel").set(prx, pry, prz);
        sh.getUniform("ReflStrength").set(reflStrength);

        // 环影：土星/天王星/海王星的行星环在表面投下的阴影
        float ringInner = 0f, ringOuter = 0f, ringShadowStrength = 0f;
        for (PlanetLayer ring : ringLayers) {
            ringOuter = ring.radius();
            ringInner = Math.max((float) radius * 1.15f, ringOuter * 0.65f);
            ringShadowStrength = "saturn".equals(planet.name()) ? 0.55f : 0.35f;
            break;
        }
        sh.getUniform("RingInner").set(ringInner);
        sh.getUniform("RingOuter").set(ringOuter);
        sh.getUniform("RingShadowStrength").set(ringShadowStrength);

        sh.getUniform("SpecularStrength").set(visual.specularStrength());
        sh.getUniform("SpecularPower").set(visual.specularPower());

        RenderSystem.setShader(() -> sh);
        RenderSystem.enableCull();
        VertexBuffer vb = getOrCreateBaseVbo();
        if (vb != null) {
            vb.bind();
            vb.drawWithShader(modelView, params.projectionMatrix(), sh);
            VertexBuffer.unbind();
        }
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private void drawCloudGpu(PlanetRenderParams params, PlanetLayer layer, int cloudIdx) {
        float angle = (float) (((layer.hasCustomRotationSpeed() ? layer.rotationSpeed() : rotationSpeed) * params.simTime()) % (Math.PI * 2.0));
        modelView.set(params.viewMatrix());
        modelView.translate(
                (float) (posX - params.cameraX()),
                (float) (posY - params.cameraY()),
                (float) (posZ - params.cameraZ()));
        modelView.rotateY(angle);

        rotateY(params.lighting().dirX(), params.lighting().dirY(), params.lighting().dirZ(), -angle, localSun);

        ShaderInstance sh = PlanetShaders.cloudShader();
        sh.getUniform("SunDir").set(localSun[0], localSun[1], localSun[2]);
        sh.getUniform("Intensity").set(params.lighting().intensity());
        applyCasterUniforms(sh, params, angle);

        RenderSystem.setShader(() -> sh);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        VertexBuffer vb = getOrCreateCloudVbo(layer, cloudIdx);
        if (vb != null) {
            // 云块有边界侧壁，关闭背面剔除避免侧壁因绕序差异被剪掉；深度测试仍会挡住背面云。
            RenderSystem.disableCull();
            vb.bind();
            vb.drawWithShader(modelView, params.projectionMatrix(), sh);
            VertexBuffer.unbind();
            RenderSystem.enableCull();
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private void drawAtmosphereGpu(PlanetRenderParams params) {
        modelView.set(params.viewMatrix());
        modelView.translate(
                (float) (posX - params.cameraX()),
                (float) (posY - params.cameraY()),
                (float) (posZ - params.cameraZ()));

        computeViewDir(params);

        ShaderInstance sh = PlanetShaders.atmoShader();
        sh.getUniform("SunDir").set(params.lighting().dirX(), params.lighting().dirY(), params.lighting().dirZ());
        sh.getUniform("ViewDir").set(viewDir[0], viewDir[1], viewDir[2]);
        sh.getUniform("Intensity").set(params.lighting().intensity());
        sh.getUniform("IsSun").set(visual.isGlowing() ? 1f : 0f);
        sh.getUniform("AtmoInner").set((float) (radius / atmosphereRadius));
        float[] atmoColor = visual.atmosphereColor();
        sh.getUniform("AtmoColor").set(atmoColor[0], atmoColor[1], atmoColor[2]);
        applyCasterUniforms(sh, params);

        RenderSystem.setShader(() -> sh);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableCull();
        VertexBuffer vb = getOrCreateAtmoVbo();
        if (vb != null) {
            vb.bind();
            vb.drawWithShader(modelView, params.projectionMatrix(), sh);
            VertexBuffer.unbind();
        }
        RenderSystem.disableCull();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    private void drawRingGpu(PlanetRenderParams params, PlanetLayer ringLayer) {
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        RenderSystem.applyModelViewMatrix();

        modelView.set(params.viewMatrix());
        modelView.translate(
                (float) (posX - params.cameraX()),
                (float) (posY - params.cameraY()),
                (float) (posZ - params.cameraZ()));

        float baseR = (float) radius;
        float innerR = Math.max(baseR * 1.15f, ringLayer.radius() * 0.65f);
        float outerR = ringLayer.radius();
        int bands = 24, segs = 96;
        float sunX = params.lighting().dirX(), sunY = params.lighting().dirY(), sunZ = params.lighting().dirZ();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int b = 0; b < bands; b++) {
            float t0 = (float) b / bands, t1 = (float) (b + 1) / bands;
            float r0 = innerR + (outerR - innerR) * t0;
            float r1 = innerR + (outerR - innerR) * t1;
            float gap0 = 0.42f, gap1 = 0.50f, alpha;
            if (t0 >= gap0 && t1 <= gap1) alpha = 0f;
            else if (t0 < gap0 && t1 > gap0) alpha = 0.25f;
            else if (t0 < gap1 && t1 > gap1) alpha = 0.25f;
            else { float mid = (t0 + t1) / 2f; alpha = 0.55f - 0.20f * Math.abs(mid - 0.3f); }
            if (planet.name().equals("uranus") || planet.name().equals("neptune")) alpha *= 0.75f;
            if (alpha < 0.01f) continue;
            float cr, cg, cb;
            if (planet.name().equals("saturn")) {
                cr = 0.82f + 0.08f * (float) Math.sin(t0 * 40f);
                cg = 0.72f + 0.06f * (float) Math.cos(t0 * 55f);
                cb = 0.55f + 0.10f * (float) Math.sin(t0 * 70f);
            } else if (planet.name().equals("uranus")) {
                cr = 0.55f; cg = 0.75f; cb = 0.82f;
            } else if (planet.name().equals("neptune")) {
                cr = 0.45f; cg = 0.68f; cb = 0.92f;
            } else {
                float[] rc = visual.ringColor();
                if (rc != null) { cr = rc[0]; cg = rc[1]; cb = rc[2]; }
                else { cr = 0.75f; cg = 0.70f; cb = 0.65f; }
            }
            for (int s = 0; s < segs; s++) {
                float a0 = (float) Math.PI * 2 * s / segs;
                float a1 = (float) Math.PI * 2 * (s + 1) / segs;
                float x0 = (float) Math.cos(a0), z0 = (float) Math.sin(a0);
                float x1 = (float) Math.cos(a1), z1 = (float) Math.sin(a1);
                float sh00 = ringShadowFactor(x0 * r0, z0 * r0, sunX, sunY, sunZ, baseR);
                float sh01 = ringShadowFactor(x0 * r1, z0 * r1, sunX, sunY, sunZ, baseR);
                float sh11 = ringShadowFactor(x1 * r1, z1 * r1, sunX, sunY, sunZ, baseR);
                float sh10 = ringShadowFactor(x1 * r0, z1 * r0, sunX, sunY, sunZ, baseR);
                float m00 = 1f - 0.50f * sh00, a00 = alpha;
                float m01 = 1f - 0.50f * sh01, a01 = alpha;
                float m11 = 1f - 0.50f * sh11, a11 = alpha;
                float m10 = 1f - 0.50f * sh10, a10 = alpha;
                bb.addVertex(modelView, x0 * r0, 0, z0 * r0).setColor(cr * m00, cg * m00, cb * m00, a00);
                bb.addVertex(modelView, x0 * r1, 0, z0 * r1).setColor(cr * m01, cg * m01, cb * m01, a01);
                bb.addVertex(modelView, x1 * r1, 0, z1 * r1).setColor(cr * m11, cg * m11, cb * m11, a11);
                bb.addVertex(modelView, x0 * r0, 0, z0 * r0).setColor(cr * m00, cg * m00, cb * m00, a00);
                bb.addVertex(modelView, x1 * r1, 0, z1 * r1).setColor(cr * m11, cg * m11, cb * m11, a11);
                bb.addVertex(modelView, x1 * r0, 0, z1 * r0).setColor(cr * m10, cg * m10, cb * m10, a10);
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);

        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
    }

    /** 环上一点是否落在行星本影里。返回 0..1 阴影因子。 */
    private static float ringShadowFactor(float x, float z, float sunX, float sunY, float sunZ, float baseR) {
        float dotP = x * sunX + z * sunZ;
        float t = -dotP;
        if (t <= 0) return 0;
        float dist2 = (x * x + z * z) - t * t;
        if (dist2 >= baseR * baseR) return 0;
        float d = (float) Math.sqrt(Math.max(0, dist2));
        if (d > baseR * 0.85f) return (baseR - d) / (baseR * 0.15f);
        return 1f;
    }

    private void computeViewDir(PlanetRenderParams params) {
        double dx = params.cameraX() - posX;
        double dy = params.cameraY() - posY;
        double dz = params.cameraZ() - posZ;
        normalize(dx, dy, dz, viewDir);
    }

    /** 绕 Y 轴旋转一个方向/位置向量（与 modelView.rotateY 同侧手性）。 */
    private static void rotateY(float x, float y, float z, float angle, float[] out) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        out[0] = c * x + s * z;
        out[1] = y;
        out[2] = -s * x + c * z;
    }

    private static void normalize(double x, double y, double z, float[] out) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-5) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        out[0] = (float) (x / len);
        out[1] = (float) (y / len);
        out[2] = (float) (z / len);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
