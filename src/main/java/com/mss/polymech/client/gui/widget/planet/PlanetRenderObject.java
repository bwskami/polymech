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
 * 涓€棰楁槦鐞冪殑鐙珛娓叉煋瀵硅薄锛圔ASE 灞傝縼绉伙級銆?
 * <p>
 * 鎸佹湁鏄熺悆琛ㄩ潰鏁版嵁锛堢綉鏍笺€佸湴鍧?albedo銆佹潗璐ㄣ€侀珮搴﹀満锛夛紝璐熻矗鏋勫缓 BASE 灞傞潤鎬?VBO锛?
 * 骞剁敤 PlanetShaders 鐨?GPU 鍏夌収璺緞缁樺埗銆傛棫鏋勯€狅紙浠?PlanetVisual锛変繚鎸佺畝鍗曠悆浣撳洖閫€銆?
 * </p>
 */
public final class PlanetRenderObject {

    /** 涓?null 鏃惰蛋鏃х増绠€鍗曠悆浣撴覆鏌撱€?*/
    private final Planet planet;
    private final PlanetVisual visual;
    private final double radius;
    /**
     * 澶╀綋浣嶇疆锛堢背锛屾父鎴忓畤瀹欏潗鏍囩郴锛夈€?b>闈?final</b>锛氫綅缃鑳戒粠闈欐€?
     * {@code RealAstroData} 鍒囧埌 kelvin 鐨勭Н鍒嗙粨鏋滐紝鐢?
     * {@link PlanetRenderObjectFactory#refreshPositions()} 姣忓抚鍒锋柊銆?
     */
    private double posX;
    private double posY;
    private double posZ;

    /**
     * 杩佺Щ鐢細鎶婃湰瀵硅薄鐨勪綅缃埛鏂颁负褰撳墠鏉冨▉浣嶇疆銆?
     * 鐢?{@link PlanetRenderObjectFactory#refreshPositions()} 姣忓抚璋冪敤锛?
     * 鏈惎鐢?kelvin 鏉冨▉鏃朵紶鍏ョ殑灏辨槸闈欐€佸€硷紙绛夊悓璧嬪€硷紝鏃犲壇浣滅敤锛夈€?
     */
    public void updatePosition(double posX, double posY, double posZ) {
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
    }

    /** BASE 灞傝〃闈㈡暟鎹紙planet == null 鏃跺潎涓?null锛夈€?*/
    private final Polyhedron mesh;
    private final PlanetHeight planetHeight;
    private final float[][] faceColors;
    private final SurfaceMaterial[] faceMaterials;
    private final int surfaceSeed;

    /** 澶ф皵灞傚鍗婂緞锛堢背锛夈€? 琛ㄧず涓嶆覆鏌撳ぇ姘斿眰銆?*/
    private final double atmosphereRadius;

    private VertexBuffer baseVbo;
    private VertexBuffer atmoVbo;

    /** 浜戝眰妫辨煴鍘氬害锛堢浉瀵硅鏄熷崐寰勶級锛氬彧鐢熸垚椤?搴曚袱涓杈瑰舰闈紝涓嶇敓鎴愪晶澹併€?*/
    static final float CLOUD_THICKNESS_FRACTION = 0.006f;

    /** 浜戝眰锛堜粠 Planet.layers 鏀堕泦锛屽凡鎸夊崐寰勬帓搴忥級銆?*/
    private final List<PlanetLayer> cloudLayers = new ArrayList<>();
    private final Map<PlanetLayer, VertexBuffer> cloudVbos = new HashMap<>();
    private float[][] cloudLayerDensities;
    private float[][] cloudFaceNormals;
    /** 鍏夌幆灞傦紙鍦熸槦/澶╃帇鏄?娴风帇鏄燂級銆?*/
    private final List<PlanetLayer> ringLayers = new ArrayList<>();
    /** 闃村奖鎶曞皠澶╀綋锛堢湡瀹炲ぉ浣擄級銆?*/
    private final List<RealAstroData> casterBodies;

    /** 鑷浆閫熷害锛坮ad/s锛夛紝鐢ㄤ簬澶┖缁村害閲岃琛屾槦/浜戝眰鍔ㄨ捣鏉ャ€?*/
    private final float rotationSpeed;
    /** 鑷浆杞村€捐锛堝姬搴︼級锛? = 鍨傜洿榛勯亾闈€?*/
    private final float axialTilt;

    /** 澶嶇敤鐨勪复鏃舵暟鎹紝閬垮厤姣忓抚鍒嗛厤銆?*/
    private final Matrix4f modelView = new Matrix4f();
    private final float[] viewDir = new float[3];
    private final float[] localSun = new float[3];
    private final float[] localView = new float[3];
    private final float[] tmpCaster = new float[3];

    /** 鐪熷疄澶╀綋涓?GUI 鏄熷浘 SolarSystem 鐨?pi 绱㈠紩涓€鑷达紝淇濊瘉鍦拌〃/浜戝眰鍣０鍜?GUI 鏄熷浘瀹屽叏鍚屾銆?*/    private static int surfaceSeedFor(String planetName) {
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

    /** 浜戝眰鍣０绉嶅瓙浣跨敤 GUI 鏄熷浘閲岀殑鐜╁叿鍗婂緞锛岃澶┖浜戝眰鍥炬鍜?GUI 鏄熷浘瀹屽叏涓€鑷淬€?*/
    private float guiCloudSeedRadius(PlanetLayer layer) {
        double ratio = layer.radius() / radius;
        return switch (planet.name()) {
            case "earth" -> (float) (1.92 * ratio);
            case "venus" -> (float) (1.10 * ratio);
            default -> layer.radius();
        };
    }

    /** 鏃х増绠€鍗曟瀯閫狅細鍙惡甯﹁瑙夊睘鎬э紝娓叉煋鏃惰蛋 SolarSystemRenderer 鐨勭函鑹茬悆銆?*/
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
        this.axialTilt = 0f;
        this.casterBodies = List.of();
    }

    /** BASE 灞傛瀯閫狅細浣跨敤 Planet 鎼哄甫鐨勭綉鏍笺€侀鑹叉彁渚涘櫒銆侀珮搴﹀満鍜屾潗璐ㄣ€?*/
    public PlanetRenderObject(Planet planet, double radius, double posX, double posY, double posZ) {
        this(planet, radius, 0, posX, posY, posZ);
    }

    /** BASE + ATMO 鏋勯€狅細atmosphereRadius 涓哄ぇ姘斿眰澶栧崐寰勶紙绫筹級锛屽皬浜庣瓑浜?radius 鏃朵笉娓叉煋澶ф皵銆?*/
    public PlanetRenderObject(Planet planet, double radius, double atmosphereRadius,
                              double posX, double posY, double posZ) {
        this(planet, radius, atmosphereRadius, List.of(), posX, posY, posZ);
    }

    /** BASE + ATMO + 闃村奖鎶曞皠鑰呮瀯閫犮€?*/
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
        this.axialTilt = planet.axialTilt();
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

    /** 澶ф皵灞傚鍗婂緞锛堢背锛夛紱0 琛ㄧず鏃犲ぇ姘斻€?*/
    public double atmosphereRadius() {
        return atmosphereRadius;
    }

    /** 澶╀綋鍚嶇О锛屼笌 {@link RealAstroData#byId(String)} 涓€鑷达紱鏃х増绠€鍗曠悆浣撳洖閫€鏃朵负绌轰覆銆?*/
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

    /** 缁樺埗 CLOUD 灞傦紙鍗婇€忔槑锛涗笌 BASE 鍚屾壒缁樺埗锛屼繚鎸?GUI 鐨勫眰娆￠『搴忥級銆?*/
    public void renderClouds(PlanetRenderParams params) {
        if (!hasClouds() || params.lighting() == null || !PlanetShaders.isCloudReady()) return;
        for (int i = 0; i < cloudLayers.size(); i++) {
            drawCloudGpu(params, cloudLayers.get(i), i);
        }
    }

    public boolean hasRings() {
        return !ringLayers.isEmpty();
    }

    /** 缁樺埗 RING 灞傦紙鍗婇€忔槑锛涜皟鐢ㄦ柟闇€宸茶缃?depthMask(false)锛夈€?*/
    public void renderRings(PlanetRenderParams params) {
        if (!hasRings() || params.lighting() == null) return;
        for (PlanetLayer ring : ringLayers) {
            drawRingGpu(params, ring);
        }
    }

    /** 缁樺埗 ATMO 灞傦紙鍗婇€忔槑锛涜皟鐢ㄦ柟闇€宸茶缃?depthMask(false)锛夈€?*/
    public void renderAtmosphere(PlanetRenderParams params) {
        if (!hasAtmosphere() || params.lighting() == null || !PlanetShaders.isAtmoReady()) return;
        drawAtmosphereGpu(params);
    }

    /** 閲婃斁 BASE / ATMO 灞?VBO锛堢Щ闄ゆ覆鏌撳璞℃椂璋冪敤锛岄伩鍏?GPU 鍐呭瓨娉勬紡锛夈€?*/
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

    // ==================== BASE 灞傞璁＄畻 ====================

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

    // ==================== BASE 灞?VBO ====================

    /** 鏋勫缓 BASE 灞傞潤鎬?VBO锛氬眬閮ㄥ潗鏍囷紙脳radius锛? 鍦板潡 albedo + 娉曠嚎銆?*/
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

    /** 鍙戝皠涓€涓笁瑙掑舰锛屼笁涓《鐐瑰悇鑷娇鐢ㄩ璁＄畻鐨勫钩婊戞硶绾匡紙澶┖鍦烘櫙閬垮厤闈㈢墖鎰燂級銆?*/
    private static void addTriSmooth(BufferBuilder bb, Matrix4f mat,
                                     float[] p0, float[] p1, float[] p2,
                                     float[] n0, float[] n1, float[] n2,
                                     float[] alb, float alpha) {
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(n0[0], n0[1], n0[2]);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(n1[0], n1[1], n1[2]);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(alb[0], alb[1], alb[2], alpha).setNormal(n2[0], n2[1], n2[2]);
    }

    /** 鐢ㄩ珮搴﹀満鍦ㄩ《鐐规柟鍚戠殑鍒囧钩闈㈡搴﹁绠楀钩婊戝湴褰㈡硶绾匡紝閬垮厤姣忎釜涓夎闈㈠悇绠椾竴涓钩鐩存硶绾裤€?*/
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

    /** 鍙戝皠涓€涓笁瑙掑舰锛屾硶绾夸粠浣嶇Щ鍚庣殑鍑犱綍閲嶆柊璁＄畻锛堝钩闈㈢潃鑹诧級銆?*/
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


    // ==================== ATMO 灞?VBO ====================

    /** 鏋勫缓 ATMO 灞傞潤鎬?VBO锛氬叏閮ㄩ潰锛岄鑹茬櫧锛屾硶绾夸负寰勫悜/闈㈡硶绾裤€?*/
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


    // ==================== CLOUD 灞?VBO ====================

    /**
     * 涓€娆℃€ц绠楁墍鏈変簯灞傜殑閫愰潰瀵嗗害锛屽苟鍋氬叏灞€闃茬┖娲烇細
     * 鍙湁褰撴煇寮犺劯鍦ㄦ墍鏈変簯灞傞噷閮芥病鏈変簯鏃讹紝鎵嶉€夎鑴稿櫔澹板€兼渶楂樼殑閭ｅ眰琛ヤ竴涓蒋杈逛簯銆?
     * 杩欐牱鍗曞眰鍙互鏈夋礊锛屼絾鏁撮鏄熺悆涓嶄細鍑虹幇澶ц寖鍥存棤浜戝尯銆?
     */
    private void computeCloudDensities() {
        if (cloudLayerDensities != null) return;

        int faceCount = mesh.faces.length;
        int layerCount = cloudLayers.size();
        cloudLayerDensities = new float[layerCount][faceCount];
        cloudFaceNormals = new float[faceCount][];
        float[][] rawVals = new float[layerCount][faceCount];
        float[][] vs = mesh.vertices;

        // 鍏堢畻濂芥墍鏈夐潰娉曠嚎锛屾墍鏈変簯灞傚叡鐢ㄣ€?
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
                    // 妯悜闀挎潯甯︼細姘村钩鏂瑰悜棰戠巼浣庯紙浜戞潯闀匡級锛岀旱鍚戦鐜囬珮锛堜簯鏉＄煭/瀵嗭級
                    cloudVal = layerNoise.fbm(fnx * 2.0f + 7.3f, fny * 8.0f + 13.7f, fnz * 2.0f + 3.1f);
                    threshold = 0.56f;
                } else if (style == 1) {
                    // 鏁ｇ灏忓洟鍧楋細鍚勬柟鍚戦鐜囬兘杈冮珮
                    cloudVal = layerNoise.fbm(fnx * 6.0f + 11.3f, fny * 6.0f + 17.7f, fnz * 6.0f + 5.9f);
                    threshold = 0.60f;
                } else {
                    // 缁嗛暱鏉″甫/涓濈紩
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

        // 鍏ㄥ眬闃茬┖娲烇細鍙ˉ娌℃湁浠讳綍浜戝眰瑕嗙洊鐨勮劯銆?
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

        // 鎺у埗灞傞棿閲嶅彔锛氬悓涓€寮犺劯鏈€澶氫袱灞備簯瑕嗙洊锛涜嫢瓒呰繃锛屽垯鍘绘帀鍣０鍊兼渶浣庣殑灞傘€?
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
     * 鏋勫缓鍗曞眰 CLOUD 闈欐€?VBO锛欳PU 鍣０鏁撮潰鍒嗙被锛屾牳蹇冮潰/涓€鍦堝杈瑰舰杞竟闈?鍓旈櫎銆?
     * 淇濈暀鐨勯潰鐢熸垚姝ｅ杈瑰舰椤?搴曚袱涓闈紝骞跺湪浜戝尯澶栧洿杈圭晫鐢熸垚渚у銆?
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

        // 绗簩閬嶏細寤虹珛杈瑰埌闈㈢殑閭绘帴琛紝鐢ㄤ簬鍙湪澶栧洿杈圭晫鐢熸垚渚у銆?
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
     * 鍙戝皠涓€涓簯灞傛１鏌憋細鐢诲簳闈㈠拰椤堕潰涓や釜姝ｅ杈瑰舰绔潰銆?
     * 鍙湁杈圭晫杈癸紙涓€渚ф槸浜戙€佸彟涓€渚ф槸绌猴級鎵嶇敓鎴愪晶澹侊紝鍐呴儴鐩搁偦妫辨煴涔嬮棿涓嶇敓鎴愯鎺ラ潰銆?
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

        // 鍏堝簳闈紙鏇磋繙锛夊悗椤堕潰锛堟洿杩戯級锛屾櫘閫?alpha 娣峰悎椤哄簭姝ｇ‘銆?
        emitCloudCap(bb, mat, vs, fv, fnx, fny, fnz, inner, innerR, ph, hs, density);
        emitCloudCap(bb, mat, vs, fv, fnx, fny, fnz, outer, outerR, ph, hs, density);

        // 浠呭鍥磋竟鐣屼晶澹侊紝鏄剧ず妫辨煴鍘氬害锛涘唴閮ㄨ鎺ラ潰涓嶆覆鏌撱€?
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

    /** 渚у娉曠嚎鍙栧洓涓《鐐圭殑骞冲潎鏂瑰悜锛屾帴杩戣浜戝潡杈圭紭鐨勫緞鍚戞湞澶栨柟鍚戙€?*/
    private static float[] edgeNormal(float[] a, float[] b, float[] c, float[] d) {
        float x = a[0] + b[0] + c[0] + d[0];
        float y = a[1] + b[1] + c[1] + d[1];
        float z = a[2] + b[2] + c[2] + d[2];
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-6f) return new float[]{0f, 1f, 0f};
        return new float[]{x / len, y / len, z / len};
    }

    /** 鍙戝皠妫辨煴鐨勪竴涓澶氳竟褰㈢闈紙搴曢潰鎴栭《闈級銆?*/
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

    /** 鍙戝皠涓€涓簯灞備笁瑙掑舰锛氫笁涓《鐐圭粺涓€娉曠嚎鍜屽瘑搴︼紙鐢ㄤ簬渚у锛夈€?*/
    private static void addCloudTri(BufferBuilder bb, Matrix4f mat, float[] p0, float[] p1, float[] p2,
                                    float nx, float ny, float nz, float density) {
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(density, 0, 0, 1f).setNormal(nx, ny, nz);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(density, 0, 0, 1f).setNormal(nx, ny, nz);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(density, 0, 0, 1f).setNormal(nx, ny, nz);
    }

    /** 鍙戝皠涓€涓簯灞備笁瑙掑舰锛氫笁涓《鐐瑰悇鑷娇鐢ㄥ緞鍚戞硶绾垮拰瀵嗗害锛屽厜鐓ц繛缁€?*/
    private static void addCloudTri(BufferBuilder bb, Matrix4f mat, float[] p0, float[] p1, float[] p2,
                                    float[] n0, float[] n1, float[] n2,
                                    float d0, float d1, float d2) {
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(d0, 0, 0, 1f).setNormal(n0[0], n0[1], n0[2]);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(d1, 0, 0, 1f).setNormal(n1[0], n1[1], n1[2]);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(d2, 0, 0, 1f).setNormal(n2[0], n2[1], n2[2]);
    }

    /** 鎵囧舰涓夎鍖栫増鏈細涓績椤剁偣浣跨敤闈㈡硶绾垮拰涓績瀵嗗害锛岃竟缂橀《鐐逛娇鐢ㄥ悇鑷緞鍚戞硶绾垮拰瀵嗗害銆?*/
    private static void addCloudTri(BufferBuilder bb, Matrix4f mat, float[] pc, float[] p1, float[] p2,
                                    float fnx, float fny, float fnz, float[] n1, float[] n2,
                                    float dc, float d1, float d2) {
        bb.addVertex(mat, pc[0], pc[1], pc[2]).setColor(dc, 0, 0, 1f).setNormal(fnx, fny, fnz);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(d1, 0, 0, 1f).setNormal(n1[0], n1[1], n1[2]);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(d2, 0, 0, 1f).setNormal(n2[0], n2[1], n2[2]);
    }

    // ==================== GPU 缁樺埗 ====================

    private void applyCasterUniforms(ShaderInstance sh, PlanetRenderParams params) {
        applyCasterUniforms(sh, params, 0f);
    }

    /**
     * 璁剧疆闃村奖鎶曞皠澶╀綋 uniform銆?
     * 褰撹鏄?浜戝眰缁?Y 杞磋嚜杞悗锛岀潃鑹插櫒鍦ㄥ眬閮ㄧ郴閲岀畻闃村奖锛?
     * 鍥犳鎶曞皠澶╀綋鐨勭浉瀵逛綅缃篃瑕佸悓姝ユ棆杞埌灞€閮ㄧ郴銆?
     */
    private void applyCasterUniforms(ShaderInstance sh, PlanetRenderParams params, float angle) {
        int nC = Math.min(casterBodies.size(), 4);
        sh.getUniform("CasterCount").set((float) nC);
        for (int i = 0; i < 4; i++) {
            if (i < nC) {
                RealAstroData caster = casterBodies.get(i);
                // ★ 必须与天体本体**同一插值口径**（2026-09-25）：本体位置走 blockPos(data, partialTick)，
        //   投射者若仍取 gamePos（= 物理步进原始值）就会每 tick 相对本体跳一次
        //   —— 表现是"影子自己在天体表面上一跳一跳"。两者都用 partialTick 才自洽。
        double[] cwp = com.mss.polymech.space.SpaceWorld.blockPos(caster, params.partialTick());
                worldToLocalDirection((float) (cwp[0] - posX),
                        (float) (cwp[1] - posY),
                        (float) (cwp[2] - posZ),
                        angle, axialTilt, tmpCaster);
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
            // ★ 必须与天体本体**同一插值口径**（2026-09-25）：本体位置走 blockPos(data, partialTick)，
        //   投射者若仍取 gamePos（= 物理步进原始值）就会每 tick 相对本体跳一次
        //   —— 表现是"影子自己在天体表面上一跳一跳"。两者都用 partialTick 才自洽。
        double[] cwp = com.mss.polymech.space.SpaceWorld.blockPos(caster, params.partialTick());
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

    /**
     * 閾哄ソ"鐩告満 鈫?澶╀綋"鐨勬ā鍨嬬煩闃碉紙鏂规 B / S3锛岃 {@code docs/mps-clone-plan.md} 搂30.7锛夈€?
     *
     * <p><b>涓轰粈涔堝彧鐣欒繖涓€澶?/b>锛氭湰浣?/ 浜?/ 澶ф皵 / 鍏夌幆鍥涙潯缁樺埗璺緞鍘熸潵<b>鍚勫啓涓€閬?/b>
     * {@code modelView.set(view)} + {@code translate(pos 鈭?camera)}銆傚帇缂╄姹?
     * "鐩告満鐩稿鍋忕Щ涓庡ぉ浣撹嚜韬昂瀵?b>鍚屼箻</b> zoom"锛堣繖鏍疯鐩村緞鎵嶄笉鍙橈級锛屽洓澶勫悇鏀逛竴娆?
     * 灏辨槸鍥涗唤瀹炵幇 鈥斺€?鏈」鐩悆杩囪繖涓簭锛埪?9.3锛夈€?/p>
     *
     * <p>鍧囧寑缂╂斁涓庡悗缁棆杞?*鍙氦鎹?*锛屾墍浠?{@code scale} 鏀惧湪 {@code translate} 涔嬪悗鍗冲彲
     * 璁╂墍鏈夊浘灞傦紙鍚厜鐜?浜戠殑灞€閮ㄥ嚑浣曪級鍗婂緞鍚屾缂╂斁銆?/p>
     *
     * <p>鍘嬬缉鏈惎鐢ㄦ椂 {@code zoom == 1.0}锛屾湰鏂规硶涓庢敼閫犲墠<b>閫愪綅绛変环</b>
     * 锛堝彧澶氫竴娆℃诞鐐逛箻娉?脳1.0锛夈€?/p>
     */
    private void beginBodyModelView(PlanetRenderParams params) {
        modelView.set(params.viewMatrix());
        double zoom = compressionZoom(params);
        modelView.translate(
                (float) ((posX - params.cameraX()) * zoom),
                (float) ((posY - params.cameraY()) * zoom),
                (float) ((posZ - params.cameraZ()) * zoom));
        if (zoom != 1.0) {
            modelView.scale((float) zoom);
        }
    }

    /** 鏈抚璇ュぉ浣撳簲琚缉鏀剧殑姣斾緥锛堝帇缂╂湭鍚敤 鈬?鎭掍负 1.0锛夈€?*/
    private double compressionZoom(PlanetRenderParams params) {
        if (!com.mss.polymech.client.space.RenderCompression.active) {
            return 1.0;
        }
        double dx = posX - params.cameraX();
        double dy = posY - params.cameraY();
        double dz = posZ - params.cameraZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return com.mss.polymech.client.space.RenderCompression.zoomFor(dist, radius);
    }

    private void drawBaseLayerGpu(PlanetRenderParams params) {
        float angle = (float) ((rotationSpeed * params.simTime()) % (Math.PI * 2.0));
        beginBodyModelView(params);
        modelView.rotateZ(axialTilt);
        modelView.rotateY(angle);

        computeViewDir(params);
        // 鍏夌収/瑙嗙嚎鏂瑰悜杞埌杞村€?+ 鑷浆鍚庣殑灞€閮ㄧ郴锛屼繚璇佹櫒鏄忕嚎鍜岄暅闈㈤珮鍏変笉闅忚嚜杞?杞村€炬紓绉汇€?
        worldToLocalDirection(params.lighting().dirX(), params.lighting().dirY(), params.lighting().dirZ(),
                angle, axialTilt, localSun);
        worldToLocalDirection(viewDir[0], viewDir[1], viewDir[2], angle, axialTilt, localView);

        ShaderInstance sh = PlanetShaders.planetShader();
        sh.getUniform("SunDir").set(localSun[0], localSun[1], localSun[2]);
        float intensity = params.lighting().intensity();
        sh.getUniform("ViewDir").set(localView[0], localView[1], localView[2]);
        sh.getUniform("Intensity").set(intensity);
        sh.getUniform("ViewFillStrength").set(0.50f); // 澶┖涓撶敤鐩告満琛ュ厜锛孶I 璺緞鏄惧紡璁句负 0
        sh.getUniform("IsSun").set(visual.isGlowing() ? 1f : 0f);
        sh.getUniform("SunVisibility").set(computeSunVisibility(params));
        applyCasterUniforms(sh, params, angle);

        // 鍗槦鍦扮収锛氭瘮鑷韩澶х殑鎶曞皠澶╀綋浣滀负鍙嶅皠鍏夋簮锛堜緥濡傛湀鐞冨彈鍦扮悆鍙嶅厜锛?
        float reflStrength = 0f;
        float prx = 0f, pry = 0f, prz = 0f;
        for (RealAstroData caster : casterBodies) {
            if (caster.radiusMeters() > radius) {
                // ★ 必须与天体本体**同一插值口径**（2026-09-25）：本体位置走 blockPos(data, partialTick)，
        //   投射者若仍取 gamePos（= 物理步进原始值）就会每 tick 相对本体跳一次
        //   —— 表现是"影子自己在天体表面上一跳一跳"。两者都用 partialTick 才自洽。
        double[] cwp = com.mss.polymech.space.SpaceWorld.blockPos(caster, params.partialTick());
                float cwx = (float) (cwp[0] - posX);
                float cwy = (float) (cwp[1] - posY);
                float cwz = (float) (cwp[2] - posZ);
                worldToLocalDirection(cwx, cwy, cwz, angle, axialTilt, tmpCaster);
                float dist = (float) Math.sqrt(cwx * cwx + cwy * cwy + cwz * cwz);
                reflStrength = Math.max(0f, Math.min(0.5f, (float) caster.radiusMeters() / Math.max(dist, 1f) * 0.8f));
                prx = tmpCaster[0]; pry = tmpCaster[1]; prz = tmpCaster[2];
                break;
            }
        }
        sh.getUniform("ParentRel").set(prx, pry, prz);
        sh.getUniform("ReflStrength").set(reflStrength);

        // 鐜奖锛氬湡鏄?澶╃帇鏄?娴风帇鏄熺殑琛屾槦鐜湪琛ㄩ潰鎶曚笅鐨勯槾褰?
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
        beginBodyModelView(params);
        modelView.rotateZ(axialTilt);
        modelView.rotateY(angle);

        worldToLocalDirection(params.lighting().dirX(), params.lighting().dirY(), params.lighting().dirZ(),
                angle, axialTilt, localSun);
        computeViewDir(params);
        worldToLocalDirection(viewDir[0], viewDir[1], viewDir[2], angle, axialTilt, localView);

        ShaderInstance sh = PlanetShaders.cloudShader();
        sh.getUniform("SunDir").set(localSun[0], localSun[1], localSun[2]);
        sh.getUniform("ViewDir").set(localView[0], localView[1], localView[2]);
        sh.getUniform("Intensity").set(params.lighting().intensity());
        sh.getUniform("ViewFillStrength").set(0.50f); // 浜戝眰涔熶娇鐢ㄥ悓涓€濂楀お绌鸿ˉ鍏?
        applyCasterUniforms(sh, params, angle);

        RenderSystem.setShader(() -> sh);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        VertexBuffer vb = getOrCreateCloudVbo(layer, cloudIdx);
        if (vb != null) {
            // 浜戝潡鏈夎竟鐣屼晶澹侊紝鍏抽棴鑳岄潰鍓旈櫎閬垮厤渚у鍥犵粫搴忓樊寮傝鍓帀锛涙繁搴︽祴璇曚粛浼氭尅浣忚儗闈簯銆?
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
        beginBodyModelView(params);

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

        beginBodyModelView(params);
        // 鍏夌幆鍜岃丹閬撻潰鍏遍潰锛氳窡鐫€琛屾槦杞村€句竴璧峰€炬枩銆?
        modelView.rotateZ(axialTilt);

        float baseR = (float) radius;
        float innerR = Math.max(baseR * 1.15f, ringLayer.radius() * 0.65f);
        float outerR = ringLayer.radius();
        int bands = 24, segs = 96;
        // 鐜槾褰卞垽瀹氬湪鐜殑灞€閮ㄧ郴绠楋細涓栫晫澶槼鏂瑰悜鍏堥€嗚酱鍊俱€?
        float ct = (float) Math.cos(axialTilt), st = (float) Math.sin(axialTilt);
        float sunX = ct * params.lighting().dirX() + st * params.lighting().dirY();
        float sunY = -st * params.lighting().dirX() + ct * params.lighting().dirY();
        float sunZ = params.lighting().dirZ();

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

    /** 鐜笂涓€鐐规槸鍚﹁惤鍦ㄨ鏄熸湰褰遍噷銆傝繑鍥?0..1 闃村奖鍥犲瓙銆?*/
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

    /** 缁?Y 杞存棆杞竴涓柟鍚?浣嶇疆鍚戦噺锛堜笌 modelView.rotateY 鍚屼晶鎵嬫€э級銆?*/
    private static void rotateY(float x, float y, float z, float angle, float[] out) {
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        out[0] = c * x + s * z;
        out[1] = y;
        out[2] = -s * x + c * z;
    }

    /**
     * 涓栫晫鏂瑰悜/鐩稿浣嶇疆 -> 鏄熺悆灞€閮ㄧ郴銆?
     * 涓?BASE/CLOUD 鐨?modelView 椤哄簭瀵瑰簲锛氬厛缁?Z 杞磋酱鍊撅紝鍐嶇粫 Y 杞磋嚜杞€?
     * 閫嗗彉鎹㈤『搴忎负 R_y(-angle) * R_z(-tilt)銆?
     */
    private static void worldToLocalDirection(float x, float y, float z,
                                              float angle, float tilt, float[] out) {
        float ct = (float) Math.cos(tilt);
        float st = (float) Math.sin(tilt);
        float x1 = ct * x + st * y;
        float y1 = -st * x + ct * y;
        rotateY(x1, y1, z, -angle, out);
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
