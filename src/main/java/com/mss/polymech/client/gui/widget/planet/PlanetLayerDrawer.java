package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import com.mss.polymech.techtree.Polyhedron;
import com.mojang.blaze3d.shaders.Uniform;
import java.util.HashMap;
import java.util.Map;


/**
 * 星球图层渲染：BASE、CLOUD、ATMOSPHERE、RING。
 */
class PlanetLayerDrawer {
    final SolarSystemView v;
    /** 行星图层静态几何 VBO 缓存（键：pi_类型_半径_网格）。 */
    private final Map<String, VertexBuffer> vboCache = new HashMap<>();

    PlanetLayerDrawer(SolarSystemView v) { this.v = v; }

    /** 释放所有缓存的 VBO（GUI 移除时调用，避免 GPU 内存泄漏）。 */
    void closeVBOs() {
        for (VertexBuffer vb : vboCache.values()) vb.close();
        vboCache.clear();
    }

    /** 构建 BASE 层静态 VBO：全部面（不做背面剔除，交给 GPU CULL），局部坐标+albedo+法线。 */
    private VertexBuffer getOrBuildBaseVBO(String key, Matrix4f mat, Polyhedron mesh, float layerR,
                                           float[][] tileAlbedo, int[] faceParent) {
        VertexBuffer cached = vboCache.get(key);
        if (cached != null) return cached;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        float R = layerR;
        float[][] vs = mesh.vertices;
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            int kn = fv.length;
            float fnx = 0, fny = 0, fnz = 0;
            for (int v : fv) { fnx += vs[v][0]; fny += vs[v][1]; fnz += vs[v][2]; }
            float fl = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (fl < 1e-6f) continue;
            fnx /= fl; fny /= fl; fnz /= fl;
            float[] alb = tileAlbedo[faceParent == null ? f : faceParent[f]];
            if (kn == 3) {
                for (int k = 0; k < 3; k++) {
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(alb[0], alb[1], alb[2], 1f)
                            .setNormal(vs[vi][0], vs[vi][1], vs[vi][2]);
                }
            } else {
                float cx = fnx * R, cy = fny * R, cz = fnz * R;
                for (int k = 0; k < kn; k++) {
                    int a1 = (k + 1) % kn;
                    bb.addVertex(mat, cx, cy, cz).setColor(alb[0], alb[1], alb[2], 1f).setNormal(fnx, fny, fnz);
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(alb[0], alb[1], alb[2], 1f).setNormal(vs[vi][0], vs[vi][1], vs[vi][2]);
                    int vj = fv[a1];
                    bb.addVertex(mat, vs[vj][0] * R, vs[vj][1] * R, vs[vj][2] * R)
                            .setColor(alb[0], alb[1], alb[2], 1f).setNormal(vs[vj][0], vs[vj][1], vs[vj][2]);
                }
            }
        }
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(bb.buildOrThrow());
        VertexBuffer.unbind();
        vboCache.put(key, vb);
        return vb;
    }

    /** 构建 CLOUD 层静态 VBO：只保留 CPU 噪声面剔除后仍保留的面（噪声输入全为静态局部量）。 */
    private VertexBuffer getOrBuildCloudVBO(String key, Matrix4f mat, Polyhedron mesh, float layerR,
                                            float densityFactor, Noise3 layerNoise) {
        VertexBuffer cached = vboCache.get(key);
        if (cached != null) return cached;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        float R = layerR;
        float[][] vs = mesh.vertices;
        int emitted = 0;
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            int kn = fv.length;
            float fnx = 0, fny = 0, fnz = 0;
            for (int v : fv) { fnx += vs[v][0]; fny += vs[v][1]; fnz += vs[v][2]; }
            float fl = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (fl < 1e-6f) continue;
            fnx /= fl; fny /= fl; fnz /= fl;
            float cloudVal = layerNoise.fbm(fnx * 2.5f + 7.3f, fny * 2.5f + 13.7f, fnz * 2.5f + 3.1f);
            float threshold = densityFactor + Math.abs(fny) * 0.18f;
            if (cloudVal < threshold) continue;
            if (kn == 3) {
                for (int k = 0; k < 3; k++) {
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(1f, 0, 0, 1f).setNormal(fnx, fny, fnz);
                }
                emitted++;
            } else {
                float cnx = fnx * R, cny = fny * R, cnz = fnz * R;
                for (int k = 0; k < kn; k++) {
                    int a1 = (k + 1) % kn;
                    bb.addVertex(mat, cnx, cny, cnz).setColor(1f, 0, 0, 1f).setNormal(fnx, fny, fnz);
                    int vi = fv[k];
                    bb.addVertex(mat, vs[vi][0] * R, vs[vi][1] * R, vs[vi][2] * R)
                            .setColor(1f, 0, 0, 1f).setNormal(fnx, fny, fnz);
                    int vj = fv[a1];
                    bb.addVertex(mat, vs[vj][0] * R, vs[vj][1] * R, vs[vj][2] * R)
                            .setColor(1f, 0, 0, 1f).setNormal(fnx, fny, fnz);
                }
                emitted++;
            }
        }
        if (emitted == 0) return null;
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(bb.buildOrThrow());
        VertexBuffer.unbind();
        vboCache.put(key, vb);
        return vb;
    }

    /** 构建 ATMO 层静态 VBO：全部面（背面剔除交给 GPU CULL），颜色白，法线为径向/面法线。 */
    private VertexBuffer getOrBuildAtmoVBO(String key, Matrix4f mat, Polyhedron mesh, float layerR) {
        VertexBuffer cached = vboCache.get(key);
        if (cached != null) return cached;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
        float R = layerR;
        float[][] vs = mesh.vertices;
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            int kn = fv.length;
            float fnx = 0, fny = 0, fnz = 0;
            for (int v : fv) { fnx += vs[v][0]; fny += vs[v][1]; fnz += vs[v][2]; }
            float fl = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (fl < 1e-6f) continue;
            fnx /= fl; fny /= fl; fnz /= fl;
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
        vboCache.put(key, vb);
        return vb;
    }

    void drawBaseLayer(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        if (SolarSystemView.GPU_LIGHTING && PlanetShaders.isReady()) {
            drawBaseLayerGPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
        } else {
            drawBaseLayerCPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
        }
    }

    /** BASE 层 GPU 光照：只发 局部坐标+地块albedo+径向法线，光照/阴影全在顶点着色器里算。 */
    void drawBaseLayerGPU(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        Polyhedron mesh = t.mesh;
        float[][] tileAlbedo = t.albedo != null ? t.albedo : v.faceColors[t.pi];
        int[] faceParent = t.faceParent;
        boolean isSun = (t.pi == 0);

        // 刚体变换交给 GPU（与 CPU 路径同一套矩阵反推，零误差）
        v.buildModelView(t, sc, ss, v.mvTmp);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.set(v.mvTmp);
        RenderSystem.applyModelViewMatrix();

        // 光向 / 视线 -> 星球局部系
        v.toLocalDir(v.mvTmp, v.lighting.dirX(), v.lighting.dirY(), v.lighting.dirZ(), v.lightLocal);
        float ccx = v.mvTmp.m30(), ccy = v.mvTmp.m31(), ccz = v.mvTmp.m32();
        float clen = (float) Math.sqrt(ccx * ccx + ccy * ccy + ccz * ccz);
        if (clen > 1e-5f) { ccx /= clen; ccy /= clen; ccz /= clen; }
        v.toLocalDir(v.mvTmp, -ccx, -ccy, -ccz, v.viewLocal);

        // ---- 光照 uniforms ----
        ShaderInstance sh = PlanetShaders.planetShader();
        sh.getUniform("SunDir").set(v.lightLocal[0], v.lightLocal[1], v.lightLocal[2]);
        sh.getUniform("ViewDir").set(v.viewLocal[0], v.viewLocal[1], v.viewLocal[2]);
        sh.getUniform("Intensity").set(v.lighting.intensity());
        sh.getUniform("IsSun").set(isSun ? 1f : 0f);
        float globalSun = isSun ? 1f : v.shadowModel.globalSunVisibility(t.pi, t.layerR, sc, ss, v.currentTilt, v.simTime);
        sh.getUniform("SunVisibility").set(globalSun);

        // ---- 阴影 uniforms：遮挡天体相对位置（世界系 -> 局部系）----
        float[] planetWP = v.solarSystem.worldPosTo(v._tmpWp1, t.pi, v.simTime);
        int[] cast = v.shadowModel.casters(t.pi);
        int nC = isSun ? 0 : Math.min(cast.length, 4);
        sh.getUniform("CasterCount").set((float) nC);
        float ct = (float) Math.cos(v.currentTilt), st = (float) Math.sin(v.currentTilt);
        for (int i = 0; i < 4; i++) {
            Uniform uRel = sh.getUniform("CasterRel" + i);
            Uniform uRad = sh.getUniform("CasterRad" + i);
            if (i < nC) {
                int qi = cast[i];
                float[] cWP = v.solarSystem.worldPosTo(v._tmpWp2, qi, v.simTime);
                float cwX = cWP[0] - planetWP[0], cwZ = cWP[2] - planetWP[2];
                // 逆旋转：逆轴倾角 -> 逆自转（occlusion 正向为 自转->轴倾角）
                float lx1 = cwX * ct, ly1 = -cwX * st, lz1 = cwZ;
                uRel.set(lx1 * sc + lz1 * ss, ly1, -lx1 * ss + lz1 * sc);
                uRad.set(v.shadowModel.bodyRadius(qi));
            } else {
                uRel.set(0f, 0f, 0f);
                uRad.set(0f);
            }
        }

        // ---- 母星反射光（仅卫星）----
        float reflStrength = 0, prx = 0, pry = 0, prz = 0;
        int parentId = v.solarSystem.get(t.pi).parentId();
        if (!isSun && parentId >= 0) {
            float[] pWP = v.solarSystem.worldPosTo(v._tmpWp2, parentId, v.simTime);
            float cwX = pWP[0] - planetWP[0], cwZ = pWP[2] - planetWP[2];
            float dist = (float) Math.sqrt(cwX * cwX + cwZ * cwZ);
            float parentR = v.shadowModel.bodyRadius(parentId);
            reflStrength = Math.max(0f, Math.min(0.5f, parentR / Math.max(dist, 1f) * 0.8f));
            float lx1 = cwX * ct, ly1 = -cwX * st, lz1 = cwZ;
            prx = lx1 * sc + lz1 * ss; pry = ly1; prz = -lx1 * ss + lz1 * sc;
        }
        sh.getUniform("ParentRel").set(prx, pry, prz);
        sh.getUniform("ReflStrength").set(reflStrength);

        // ---- 发射：局部坐标 + albedo + 法线，光照全交给 GPU（VBO 缓存静态几何）----
        String vboKey = "BASE_" + t.pi + "_" + t.layerR + (mesh == v.shadedBase ? "_S" : "_B");
        VertexBuffer vb = getOrBuildBaseVBO(vboKey, mat, mesh, t.layerR, tileAlbedo, faceParent);
        if (vb != null) {
            RenderSystem.setShader(() -> PlanetShaders.planetShader());
            RenderSystem.enableCull();
            vb.bind();
            vb.drawWithShader(v.mvTmp, RenderSystem.getProjectionMatrix(), PlanetShaders.planetShader());
            VertexBuffer.unbind();
            RenderSystem.disableCull();
        }
        RenderSystem.setShader(GameRenderer::getPositionColorShader);   // 还原，供后续云层/大气使用
        // 还原模型视图为单位阵
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
    }

    void drawBaseLayerCPU(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {

        Polyhedron mesh = t.mesh;
        float[][] tileAlbedo = t.albedo != null ? t.albedo : v.faceColors[t.pi];
        int[] faceParent = t.faceParent;
        int n = mesh.vertices.length;
        int nf = mesh.faces.length;
        boolean isSun = (t.pi == 0);

        // ---- 刚体变换交给 GPU：用 cameraTo 精确反推模型视图矩阵（零误差）----
        v.buildModelView(t, sc, ss, v.mvTmp);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.set(v.mvTmp);
        RenderSystem.applyModelViewMatrix();

        // ---- 光向 / 视线 -> 星球局部系（同一矩阵转置导出，逐位一致）----
        v.toLocalDir(v.mvTmp, v.lighting.dirX(), v.lighting.dirY(), v.lighting.dirZ(), v.lightLocal);
        float ccx = v.mvTmp.m30(), ccy = v.mvTmp.m31(), ccz = v.mvTmp.m32();   // 球心（相机空间）
        float clen = (float) Math.sqrt(ccx * ccx + ccy * ccy + ccz * ccz);
        if (clen > 1e-5f) { ccx /= clen; ccy /= clen; ccz /= clen; }
        v.toLocalDir(v.mvTmp, -ccx, -ccy, -ccz, v.viewLocal);                 // 球心 -> 相机

        // ---- 逐顶点局部系光照（法线=单位球径向，静态，CPU 不再做 cameraTo）----
        float[] directV = v.lDirect, specV = v.lSpec, limbV = v.lLimb;
        float[] rimWV = v.lRimW, rimCV = v.lRimC, shadowBV = v.lShadowB, reflV = v.lRefl;
        SurfaceLight sl = new SurfaceLight();
        boolean hasShadow = v.shadowModel.hasShadow(t.pi);
        boolean hasParent = v.solarSystem.get(t.pi).parentId() >= 0;
        float globalSun = isSun ? 1f : v.shadowModel.globalSunVisibility(t.pi, t.layerR, sc, ss, v.currentTilt, v.simTime);
        for (int i = 0; i < n; i++) {
            float nx = mesh.vertices[i][0], ny = mesh.vertices[i][1], nz = mesh.vertices[i][2];
            if (isSun) {
                directV[i] = 1f; specV[i] = 0; rimWV[i] = 0; rimCV[i] = 0; shadowBV[i] = 0; reflV[i] = 0;
                float limbDot = Math.max(0, nx * v.viewLocal[0] + ny * v.viewLocal[1] + nz * v.viewLocal[2]);
                limbV[i] = 0.50f + 0.50f * (float) Math.pow(limbDot, 1.4f);
                continue;
            }
            float shadow = hasShadow
                    ? v.shadowModel.occlusion(t.pi, mesh.vertices[i], t.layerR, sc, ss, v.currentTilt, v.simTime) : 0;
            float reflStrength = 0, rx = 0, ry = 0, rz = 0;
            if (hasParent) {
                reflStrength = v.shadowModel.parentReflection(t.pi, mesh.vertices[i], t.layerR, sc, ss, v.currentTilt, v.simTime, v.reflWorld);
                // 世界 -> 相机 -> 局部
                float rrx = v.reflWorld[0] * cosY + v.reflWorld[2] * sinY;
                float rrz1 = -v.reflWorld[0] * sinY + v.reflWorld[2] * cosY;
                float rcy = v.reflWorld[1] * cosX - rrz1 * sinX;
                float rcz = v.reflWorld[1] * sinX + rrz1 * cosX;
                v.toLocalDir(v.mvTmp, rrx, rcy, rcz, v.reflLocal);
                rx = v.reflLocal[0]; ry = v.reflLocal[1]; rz = v.reflLocal[2];
            }
            v.lighting.evaluateLocal(nx, ny, nz, v.lightLocal[0], v.lightLocal[1], v.lightLocal[2],
                    v.viewLocal[0], v.viewLocal[1], v.viewLocal[2], shadow, globalSun, rx, ry, rz, reflStrength, sl);
            directV[i] = sl.direct; specV[i] = sl.specular;
            rimWV[i] = sl.rimWarm; rimCV[i] = sl.rimCool; shadowBV[i] = sl.shadowBlue;
            reflV[i] = sl.reflected;
        }

        // ---- 细分网格：逐顶点预计算最终色（边界顶点已按地块拆开，免去按面重复 colorize）----
        float[] colTmp = new float[3];
        if (faceParent != null) {
            for (int i = 0; i < n; i++) {
                float[] alb = tileAlbedo[v.shadeVertexParent[i]];
                if (isSun) {
                    float limb = limbV[i];
                    v.lColR[i] = alb[0]*limb; v.lColG[i] = alb[1]*limb; v.lColB[i] = alb[2]*limb;
                } else {
                    sl.set(directV[i], specV[i], rimWV[i], rimCV[i], shadowBV[i], reflV[i]);
                    v.lighting.colorize(alb, sl, colTmp);
                    v.lColR[i] = colTmp[0]; v.lColG[i] = colTmp[1]; v.lColB[i] = colTmp[2];
                }
            }
        }

        // ---- 发射三角形：局部坐标(×layerR)，GPU 应用模型视图+投影 ----
        boolean drew = false;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float[] vR = new float[16], vG = new float[16], vB = new float[16];
        float R = t.layerR;
        float[][] vs = mesh.vertices;
        for (int f = 0; f < nf; f++) {
            int[] fv = mesh.faces[f];
            int kn = fv.length;
            // 背面剔除：局部面法线(面心方向) 与 视线(局部系)
            float fnx = 0, fny = 0, fnz = 0;
            for (int v : fv) { fnx += vs[v][0]; fny += vs[v][1]; fnz += vs[v][2]; }
            float fl = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (fl < 1e-6f) continue;
            fnx /= fl; fny /= fl; fnz /= fl;
            if (fnx * v.viewLocal[0] + fny * v.viewLocal[1] + fnz * v.viewLocal[2] <= 0) continue;
            if (kn == 3) {
                // 细分三角：直接用预计算顶点色
                drew = true;
                bb.addVertex(mat, vs[fv[0]][0]*R, vs[fv[0]][1]*R, vs[fv[0]][2]*R).setColor(v.lColR[fv[0]], v.lColG[fv[0]], v.lColB[fv[0]], 1f);
                bb.addVertex(mat, vs[fv[1]][0]*R, vs[fv[1]][1]*R, vs[fv[1]][2]*R).setColor(v.lColR[fv[1]], v.lColG[fv[1]], v.lColB[fv[1]], 1f);
                bb.addVertex(mat, vs[fv[2]][0]*R, vs[fv[2]][1]*R, vs[fv[2]][2]*R).setColor(v.lColR[fv[2]], v.lColG[fv[2]], v.lColB[fv[2]], 1f);
            } else {
                // 多边形地块：逐顶点 colorize + 中心扇
                float[] alb = tileAlbedo[f];
                float ccR = 0, ccG = 0, ccB = 0;
                for (int k = 0; k < kn; k++) {
                    int vi = fv[k];
                    if (isSun) {
                        colTmp[0] = alb[0]; colTmp[1] = alb[1]; colTmp[2] = alb[2];
                    } else {
                        sl.set(directV[vi], specV[vi], rimWV[vi], rimCV[vi], shadowBV[vi], reflV[vi]);
                        v.lighting.colorize(alb, sl, colTmp);
                    }
                    float limb = isSun ? limbV[vi] : 1f;
                    vR[k] = colTmp[0] * limb; vG[k] = colTmp[1] * limb; vB[k] = colTmp[2] * limb;
                    ccR += vR[k]; ccG += vG[k]; ccB += vB[k];
                }
                ccR /= kn; ccG /= kn; ccB /= kn;
                float cx = fnx * R, cy = fny * R, cz = fnz * R;
                for (int k = 0; k < kn; k++) {
                    int a1 = (k + 1) % kn;
                    drew = true;
                    bb.addVertex(mat, cx, cy, cz).setColor(ccR, ccG, ccB, 1f);
                    bb.addVertex(mat, vs[fv[k]][0]*R, vs[fv[k]][1]*R, vs[fv[k]][2]*R).setColor(vR[k], vG[k], vB[k], 1f);
                    bb.addVertex(mat, vs[fv[a1]][0]*R, vs[fv[a1]][1]*R, vs[fv[a1]][2]*R).setColor(vR[a1], vG[a1], vB[a1], 1f);
                }
            }
        }
        if (drew) BufferUploader.drawWithShader(bb.buildOrThrow());
        // 还原模型视图为单位阵（其余绘制仍用 cameraTo 烘焙坐标）
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
    }

    void drawCloudLayer(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        if (PlanetShaders.isCloudReady()) {
            drawCloudLayerGPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
        } else {
            drawCloudLayerCPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
        }
    }

    /** 空安全的 uniform 设置：着色器可能优化掉未使用的 uniform。 */
    static void setUniform(ShaderInstance sh, String name, float... values) {
        Uniform u = sh.getUniform(name);
        if (u != null) u.set(values);
    }

    /** CLOUD 层 GPU：局部坐标+径向法线+密度阈值(Color.r)，噪声/光照/阴影全在片元着色器。 */
    void drawCloudLayerGPU(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        Polyhedron mesh = t.mesh;
        int cloudIdx = 0;
        for (PlanetLayer cl : v.solarSystem.get(t.pi).layers())
            if (cl.type() == PlanetLayerType.CLOUD && cl.radius() < t.layerR - 1e-4f) cloudIdx++;
        float densityFactor = 0.42f + cloudIdx * 0.08f;
        long layerSeed = 0x5EED1234L + t.pi * 0x1234567L + (long)(t.layerR * 1000) * 0x9E3779B9L;
        Noise3 layerNoise = v.layerNoiseCache.get(layerSeed);
        if (layerNoise == null) { layerNoise = new Noise3(layerSeed); v.layerNoiseCache.put(layerSeed, layerNoise); }

        v.buildModelView(t, sc, ss, v.mvTmp);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.set(v.mvTmp);
        RenderSystem.applyModelViewMatrix();

        v.toLocalDir(v.mvTmp, v.lighting.dirX(), v.lighting.dirY(), v.lighting.dirZ(), v.lightLocal);
        float ccx = v.mvTmp.m30(), ccy = v.mvTmp.m31(), ccz = v.mvTmp.m32();
        float clen = (float) Math.sqrt(ccx * ccx + ccy * ccy + ccz * ccz);
        if (clen > 1e-5f) { ccx /= clen; ccy /= clen; ccz /= clen; }
        v.toLocalDir(v.mvTmp, -ccx, -ccy, -ccz, v.viewLocal);

        ShaderInstance sh = PlanetShaders.cloudShader();
        SolarSystemView.setUniform(sh, "SunDir", v.lightLocal[0], v.lightLocal[1], v.lightLocal[2]);
        SolarSystemView.setUniform(sh, "Intensity", v.lighting.intensity());

        float[] planetWP = v.solarSystem.worldPosTo(v._tmpWp1, t.pi, v.simTime);
        int[] cast = v.shadowModel.casters(t.pi);
        int nC = Math.min(cast.length, 4);
        SolarSystemView.setUniform(sh, "CasterCount", (float) nC);
        float ct = (float) Math.cos(v.currentTilt), st = (float) Math.sin(v.currentTilt);
        for (int i = 0; i < 4; i++) {
            Uniform uRel = sh.getUniform("CasterRel" + i);
            Uniform uRad = sh.getUniform("CasterRad" + i);
            if (i < nC) {
                int qi = cast[i];
                float[] cWP = v.solarSystem.worldPosTo(v._tmpWp2, qi, v.simTime);
                float cwX = cWP[0] - planetWP[0], cwZ = cWP[2] - planetWP[2];
                float lx1 = cwX * ct, ly1 = -cwX * st, lz1 = cwZ;
                if (uRel != null) uRel.set(lx1 * sc + lz1 * ss, ly1, -lx1 * ss + lz1 * sc);
                if (uRad != null) uRad.set(v.shadowModel.bodyRadius(qi));
            } else {
                if (uRel != null) uRel.set(0f, 0f, 0f);
                if (uRad != null) uRad.set(0f);
            }
        }

        RenderSystem.setShader(() -> PlanetShaders.cloudShader());
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        String vboKey = "CLOUD_" + t.pi + "_" + t.layerR;
        VertexBuffer vb = getOrBuildCloudVBO(vboKey, mat, mesh, t.layerR, densityFactor, layerNoise);
        if (vb != null) {
            RenderSystem.enableCull();
            vb.bind();
            vb.drawWithShader(v.mvTmp, RenderSystem.getProjectionMatrix(), PlanetShaders.cloudShader());
            VertexBuffer.unbind();
            RenderSystem.disableCull();
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
    }

    /** CLOUD 层 CPU 回退。 */
    void drawCloudLayerCPU(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        Polyhedron mesh = t.mesh;
        long layerSeed = 0x5EED1234L + t.pi * 0x1234567L + (long)(t.layerR * 1000) * 0x9E3779B9L;
        Noise3 layerNoise = v.layerNoiseCache.get(layerSeed);
        if (layerNoise == null) { layerNoise = new Noise3(layerSeed); v.layerNoiseCache.put(layerSeed, layerNoise); }
        int cloudIdx = 0;
        for (PlanetLayer cl : v.solarSystem.get(t.pi).layers())
            if (cl.type() == PlanetLayerType.CLOUD && cl.radius() < t.layerR - 1e-4f) cloudIdx++;
        float densityFactor = 0.42f + cloudIdx * 0.08f;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        boolean drew = false;
        float[] cxs = new float[6], cys = new float[6], czs = new float[6];
        float[] cam = new float[3];
        float[] cloudCenterCam = new float[3];
        v.camera.cameraTo(cloudCenterCam, new float[]{0,0,0}, 1, t.dwx, t.dwz, sc, ss, v.currentTilt);
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            float nx=0, ny=0, nz=0;
            for (int k = 0; k < fv.length; k++) {
                float[] vert = mesh.vertices[fv[k]];
                nx += vert[0]; ny += vert[1]; nz += vert[2];
                v.camera.cameraTo(cam, vert, t.layerR, t.dwx, t.dwz, sc, ss, v.currentTilt);
                cxs[k] = cam[0]; cys[k] = cam[1]; czs[k] = cam[2];
            }
            float nlen = (float) Math.sqrt(nx*nx+ny*ny+nz*nz);
            if (nlen < 1e-6f) continue;
            nx/=nlen; ny/=nlen; nz/=nlen;
            float threshold = densityFactor + Math.abs(ny)*0.18f;
            float cloudVal = layerNoise.fbm(nx*2.5f+7.3f, ny*2.5f+13.7f, nz*2.5f+3.1f);
            if (cloudVal < threshold) continue;
            { float fcx2=(cxs[0]+cxs[1]+cxs[2])/3f, fcy2=(cys[0]+cys[1]+cys[2])/3f, fcz2=(czs[0]+czs[1]+czs[2])/3f;
              float dp=cloudCenterCam[0]*fcx2+cloudCenterCam[1]*fcy2+cloudCenterCam[2]*fcz2;
              float pp=fcx2*fcx2+fcy2*fcy2+fcz2*fcz2;
              if (dp <= pp) continue; }
            float nX=cxs[0]-cloudCenterCam[0]+cxs[1]-cloudCenterCam[0]+cxs[2]-cloudCenterCam[0];
            float nY=cys[0]-cloudCenterCam[1]+cys[1]-cloudCenterCam[1]+cys[2]-cloudCenterCam[1];
            float nZ=czs[0]-cloudCenterCam[2]+czs[1]-cloudCenterCam[2]+czs[2]-cloudCenterCam[2];
            float nLen=(float)Math.sqrt(nX*nX+nY*nY+nZ*nZ);
            if(nLen>1e-5f){nX/=nLen;nY/=nLen;nZ/=nLen;}
            float ndotlC=nX*v.lighting.dirX()+nY*v.lighting.dirY()+nZ*v.lighting.dirZ();
            float ambC=v.lighting.ambient();
            float cloudShadow=v.shadowModel.hasShadow(t.pi)?v.shadowModel.occlusion(t.pi,mesh.vertices[mesh.faces[f][0]],t.layerR,sc,ss,v.currentTilt,v.simTime):0;
            float directC=v.lighting.direct(ndotlC,cloudShadow);
            float shade=ambC+(1-ambC)*directC;
            float fa=0.55f*shade;
            float[]lcC=new float[3];v.lighting.lightColor(directC,lcC);
            float cR=0.96f*lcC[0],cG=0.97f*lcC[1],cB=1.0f*lcC[2];
            float fcx=0,fcy=0,fcz=0;
            for(int k=0;k<fv.length;k++){fcx+=cxs[k];fcy+=cys[k];fcz+=czs[k];}
            fcx/=fv.length;fcy/=fv.length;fcz/=fv.length;
            for(int k=0;k<fv.length;k++){
                int a1=(k+1)%fv.length;
                bb.addVertex(mat,fcx,fcy,fcz).setColor(cR*0.92f,cG*0.92f,cB*0.92f,fa);
                bb.addVertex(mat,cxs[k],cys[k],czs[k]).setColor(cR,cG,cB,fa);
                bb.addVertex(mat,cxs[a1],cys[a1],czs[a1]).setColor(cR,cG,cB,fa);
            }
            drew = true;
        }
        if (drew) BufferUploader.drawWithShader(bb.buildOrThrow());
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
    void drawAtmosphereLayer(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        if (PlanetShaders.isAtmoReady()) {
            drawAtmosphereLayerGPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
        } else {
            drawAtmosphereLayerCPU(mat, t, sc, ss, cosY, sinY, cosX, sinX);
        }
    }

    /** ATMO 层 GPU：局部坐标+径向法线，rim/日照/阴影全在片元着色器。 */
    void drawAtmosphereLayerGPU(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        Polyhedron mesh = t.mesh;
        float[] atmColor = v.atmosphereColor(t.pi);
        boolean isStar = v.solarSystem.get(t.pi).visual().isGlowing();

        v.buildModelView(t, sc, ss, v.mvTmp);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.set(v.mvTmp);
        RenderSystem.applyModelViewMatrix();

        v.toLocalDir(v.mvTmp, v.lighting.dirX(), v.lighting.dirY(), v.lighting.dirZ(), v.lightLocal);
        float ccx = v.mvTmp.m30(), ccy = v.mvTmp.m31(), ccz = v.mvTmp.m32();
        float clen = (float) Math.sqrt(ccx * ccx + ccy * ccy + ccz * ccz);
        if (clen > 1e-5f) { ccx /= clen; ccy /= clen; ccz /= clen; }
        v.toLocalDir(v.mvTmp, -ccx, -ccy, -ccz, v.viewLocal);

        ShaderInstance sh = PlanetShaders.atmoShader();
        SolarSystemView.setUniform(sh, "SunDir", v.lightLocal[0], v.lightLocal[1], v.lightLocal[2]);
        SolarSystemView.setUniform(sh, "ViewDir", v.viewLocal[0], v.viewLocal[1], v.viewLocal[2]);
        SolarSystemView.setUniform(sh, "Intensity", v.lighting.intensity());
        SolarSystemView.setUniform(sh, "IsSun", isStar ? 1f : 0f);
        float baseR = 0;
        for (PlanetLayer l : v.solarSystem.get(t.pi).layers())
            if (l.type() == PlanetLayerType.BASE) baseR = l.radius();
        SolarSystemView.setUniform(sh, "AtmoInner", baseR > 0 ? baseR / t.layerR : 0.9f);
        SolarSystemView.setUniform(sh, "AtmoColor", atmColor[0], atmColor[1], atmColor[2]);

        float[] planetWP = v.solarSystem.worldPosTo(v._tmpWp1, t.pi, v.simTime);
        int[] cast = v.shadowModel.casters(t.pi);
        int nC = Math.min(cast.length, 4);
        SolarSystemView.setUniform(sh, "CasterCount", (float) nC);
        float ct = (float) Math.cos(v.currentTilt), st = (float) Math.sin(v.currentTilt);
        for (int i = 0; i < 4; i++) {
            Uniform uRel = sh.getUniform("CasterRel" + i);
            Uniform uRad = sh.getUniform("CasterRad" + i);
            if (i < nC) {
                int qi = cast[i];
                float[] cWP = v.solarSystem.worldPosTo(v._tmpWp2, qi, v.simTime);
                float cwX = cWP[0] - planetWP[0], cwZ = cWP[2] - planetWP[2];
                float lx1 = cwX * ct, ly1 = -cwX * st, lz1 = cwZ;
                if (uRel != null) uRel.set(lx1 * sc + lz1 * ss, ly1, -lx1 * ss + lz1 * sc);
                if (uRad != null) uRad.set(v.shadowModel.bodyRadius(qi));
            } else {
                if (uRel != null) uRel.set(0f, 0f, 0f);
                if (uRad != null) uRad.set(0f);
            }
        }

        RenderSystem.setShader(() -> PlanetShaders.atmoShader());
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        String vboKey = "ATMO_" + t.pi + "_" + t.layerR + (mesh == v.shadedBase ? "_S" : "_B");
        VertexBuffer vb = getOrBuildAtmoVBO(vboKey, mat, mesh, t.layerR);
        if (vb != null) {
            RenderSystem.enableCull();
            vb.bind();
            vb.drawWithShader(v.mvTmp, RenderSystem.getProjectionMatrix(), PlanetShaders.atmoShader());
            VertexBuffer.unbind();
            RenderSystem.disableCull();
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
    }

    /** ATMO 层 CPU 回退。 */
    void drawAtmosphereLayerCPU(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        Polyhedron mesh = t.mesh;
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        float[] cxs = new float[6], cys = new float[6], czs = new float[6];
        float[] cam = new float[3];
        float[] atmoCenterCam = new float[3];
        v.camera.cameraTo(atmoCenterCam, new float[]{0, 0, 0}, 1, t.dwx, t.dwz, sc, ss, v.currentTilt);
        float[] atmColor = v.atmosphereColor(t.pi);
        for (int f = 0; f < mesh.faces.length; f++) {
            int[] fv = mesh.faces[f];
            for (int k = 0; k < fv.length; k++) {
                v.camera.cameraTo(cam, mesh.vertices[fv[k]], t.layerR, t.dwx, t.dwz, sc, ss, v.currentTilt);
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
            // 与 GPU 版一致：行星边缘最亮，向外渐隐
            float dAtmo = (float) Math.sqrt(Math.max(0f, 1f - NdotV * NdotV));
            float innerAtmo = 0.9f;
            for (PlanetLayer l : v.solarSystem.get(t.pi).layers())
                if (l.type() == PlanetLayerType.BASE) innerAtmo = Math.max(0.001f, l.radius() / t.layerR);
            float rim;
            if (dAtmo > innerAtmo) {
                float tt = (dAtmo - innerAtmo) / (1f - innerAtmo);
                rim = (float) Math.pow(1f - tt, 1.5f); // 平缓衰减：边缘逐渐变透明
            } else {
                float tt = dAtmo / innerAtmo;
                rim = tt * tt;
            }
            float sunDot = Math.max(0, anx * v.lighting.dirX() + any * v.lighting.dirY() + anz * v.lighting.dirZ());
            boolean isStar = v.solarSystem.get(t.pi).visual().isGlowing();
            float atmoShadow = v.shadowModel.hasShadow(t.pi)
                    ? v.shadowModel.occlusion(t.pi, mesh.faces[f].length > 0 ? mesh.vertices[mesh.faces[f][0]] : new float[]{0,0,0}, t.layerR, sc, ss, v.currentTilt, v.simTime) : 0;
            float sunF = isStar ? 1f : v.lighting.intensity() * (1f - atmoShadow * sunDot);
            float sunLift = (float) Math.pow(sunDot, 0.75f);
            float alpha = isStar ? rim * 0.45f : rim * sunF * (0.14f + 0.44f * sunLift);
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
    void drawRing(Matrix4f mat, SolarSystemView.RenderTask t, float sc, float ss, float cosY, float sinY, float cosX, float sinX) {
        float baseR = 0;
        for (PlanetLayer l : v.solarSystem.get(t.pi).layers()) if (l.type() == PlanetLayerType.BASE) baseR = l.radius();
        float innerR = Math.max(baseR * 1.15f, t.layerR * 0.65f);
        float outerR = t.layerR;
        int bands = 24, segs = 96;
        v.buildTransformMatrix(t.dwx, t.dwz, sc, ss, v.currentTilt, v.mvTmp);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.set(v.mvTmp);
        RenderSystem.applyModelViewMatrix();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int b = 0; b < bands; b++) {
            float t0 = (float) b / bands, t1 = (float) (b + 1) / bands;
            float r0 = innerR + (outerR - innerR) * t0;
            float r1 = innerR + (outerR - innerR) * t1;
            float gap0 = 0.42f, gap1 = 0.50f, alpha;
            if (t0 >= gap0 && t1 <= gap1) alpha = 0f;
            else if (t0 < gap0 && t1 > gap0) alpha = 0.15f;
            else if (t0 < gap1 && t1 > gap1) alpha = 0.15f;
            else { float mid = (t0 + t1) / 2f; alpha = 0.35f - 0.15f * Math.abs(mid - 0.3f); }
            if (t.pi == 16 || t.pi == 17) alpha *= 0.55f;
            if (alpha < 0.01f) continue;
            float cr, cg, cb;
            if (t.pi == 13) { cr = 0.82f + 0.08f * (float) Math.sin(t0 * 40f); cg = 0.72f + 0.06f * (float) Math.cos(t0 * 55f); cb = 0.55f + 0.10f * (float) Math.sin(t0 * 70f); }
            else if (t.pi == 16) { cr = 0.55f; cg = 0.75f; cb = 0.82f; }
            else if (t.pi == 17) { cr = 0.45f; cg = 0.68f; cb = 0.92f; }
            else { cr = 0.75f; cg = 0.70f; cb = 0.65f; }
            for (int s = 0; s < segs; s++) {
                float a0 = (float) Math.PI * 2 * s / segs;
                float a1 = (float) Math.PI * 2 * (s + 1) / segs;
                float x0 = (float) Math.cos(a0), z0 = (float) Math.sin(a0);
                float x1 = (float) Math.cos(a1), z1 = (float) Math.sin(a1);
                bb.addVertex(mat, x0 * r0, 0, z0 * r0).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, x0 * r1, 0, z0 * r1).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, x1 * r1, 0, z1 * r1).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, x0 * r0, 0, z0 * r0).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, x1 * r1, 0, z1 * r1).setColor(cr, cg, cb, alpha);
                bb.addVertex(mat, x1 * r0, 0, z1 * r0).setColor(cr, cg, cb, alpha);
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
    }



}
