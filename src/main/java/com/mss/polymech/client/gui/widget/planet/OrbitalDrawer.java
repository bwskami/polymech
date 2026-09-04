package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import com.mss.polymech.techtree.Polyhedron;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * 轨道环、小行星带、Kuiper 带、恒星光晕。
 */
class OrbitalDrawer {
    final SolarSystemView v;
    // Reusable arrays for drawOrbitalRings (avoids per-planet per-frame allocation)
    // Reusable array for drawScatteredRocks face vertices (avoids per-face per-rock allocation)
    /** Reusable vertex array for scattered rocks (max 6 verts x 3 components). */
    private final float[] rockVtx = new float[18];
    /** Reusable ring arrays (segments+1+1 entries). */
    private final float[] ringPx = new float[258], ringPy = new float[258], ringPz = new float[258];
    private final boolean[] ringValid = new boolean[258];
    private final float[] ringLx = new float[258], ringLy = new float[258];
    private final float[] ringRx = new float[258], ringRy = new float[258];
    // ---- VBO for belt bands (static geometry, uploaded once per config) ----
    private VertexBuffer beltBandVBO1, beltBandVBO2;
    // ---- Rock LOD meshes ----
    private Polyhedron rockHighMesh, rockLowMesh;
    // ---- Rock instancing ----
    private RockInstancedRenderer rockInstanced;

    OrbitalDrawer(SolarSystemView v) {
        this.v = v;
        this.rockInstanced = new RockInstancedRenderer(v);
    }

    /** 释放环带 VBO。 */
    void closeVBOs() {
        if (beltBandVBO1 != null) beltBandVBO1.close();
        if (beltBandVBO2 != null) beltBandVBO2.close();
        beltBandVBO1 = beltBandVBO2 = null;
        rockInstanced.close();
    }

    /** 世界平面 y=0 上的虚线圆（与轨道线同一套相机空间画法）。 */
    void drawDashedWorldCircle(Matrix4f mat, float radius, float hw,
                               float r, float g, float b, float a,
                               int dashPeriod, int dashOn) {
        int steps = 128;
        float cosY = v.camera.cosY(), sinY = v.camera.sinY(), cosX = v.camera.cosX(), sinX = v.camera.sinX();
        float fX = v.camera.focalX(), fZ = v.camera.focalZ();
        float[] px = ringPx, py = ringPy, pz = ringPz;
        for (int i = 0; i < steps; i++) {
            float ang = (float) (Math.PI * 2 * i / steps);
            float wx = (float) Math.cos(ang) * radius;
            float wz = (float) Math.sin(ang) * radius;
            float dwx = wx - fX, dwz = wz - fZ;
            float rx = dwx * cosY + dwz * sinY;
            float rz1 = -dwx * sinY + dwz * cosY;
            float wyRel = -v.camera.focalY();
            px[i] = rx;
            py[i] = wyRel * cosX - rz1 * sinX;
            pz[i] = wyRel * sinX + rz1 * cosX - v.camera.dist();
        }
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < steps; i++) {
            if (i % dashPeriod >= dashOn) continue;
            int j = (i + 1) % steps;
            SolarSystemView.addQuad3D(bb, mat, px[i], py[i], pz[i], px[j], py[j], pz[j], hw, r, g, b, a);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    /** 世界平面 y=0 上的 3D 箭头（三棱柱，超空间航道入口）。 */
    void drawHyperlaneArrow3D(Matrix4f mat, float angle, float radius, float size, float hw,
                              float r, float g, float b, float a) {
        float ca = (float) Math.cos(angle), sa = (float) Math.sin(angle);
        float halfW = size * 0.45f;
        float thick = Math.max(0.06f, size * 0.16f);
        // 三棱柱 6 个顶点（世界坐标，y=±thick）
        float bLx = ca * radius - sa * halfW, bLz = sa * radius + ca * halfW;
        float bRx = ca * radius + sa * halfW, bRz = sa * radius - ca * halfW;
        float tipX = ca * (radius + size), tipZ = sa * (radius + size);
        float[] blt = camPoint3(mat, bLx, thick, bLz);
        float[] brt = camPoint3(mat, bRx, thick, bRz);
        float[] tt = camPoint3(mat, tipX, thick, tipZ);
        float[] blb = camPoint3(mat, bLx, -thick, bLz);
        float[] brb = camPoint3(mat, bRx, -thick, bRz);
        float[] tb = camPoint3(mat, tipX, -thick, tipZ);

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        tri(bb, mat, blt, brt, tt, r, g, b, a);   // 顶面
        tri(bb, mat, brb, blb, tb, r, g, b, a);   // 底面
        quad(bb, mat, blt, blb, brb, brt, r, g, b, a); // 底面边
        quad(bb, mat, brt, brb, tb, tt, r, g, b, a);   // 右斜面
        quad(bb, mat, tt, tb, blb, blt, r, g, b, a);   // 左斜面
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    private static void tri(BufferBuilder bb, Matrix4f mat, float[] p0, float[] p1, float[] p2,
                            float r, float g, float b, float a) {
        bb.addVertex(mat, p0[0], p0[1], p0[2]).setColor(r, g, b, a);
        bb.addVertex(mat, p1[0], p1[1], p1[2]).setColor(r, g, b, a);
        bb.addVertex(mat, p2[0], p2[1], p2[2]).setColor(r, g, b, a);
    }

    private static void quad(BufferBuilder bb, Matrix4f mat, float[] p0, float[] p1, float[] p2, float[] p3,
                             float r, float g, float b, float a) {
        tri(bb, mat, p0, p1, p2, r, g, b, a);
        tri(bb, mat, p0, p2, p3, r, g, b, a);
    }

    private float[] camPoint3(Matrix4f mat, float wx, float wy, float wz) {
        float[] out = new float[3];
        float fX = v.camera.focalX(), fZ = v.camera.focalZ();
        float dx = wx - fX, dz = wz - fZ;
        float cosY = v.camera.cosY(), sinY = v.camera.sinY(), cosX = v.camera.cosX(), sinX = v.camera.sinX();
        float rx = dx * cosY + dz * sinY;
        float rz1 = -dx * sinY + dz * cosY;
        float wyRel = wy - v.camera.focalY();
        out[0] = rx;
        out[1] = wyRel * cosX - rz1 * sinX;
        out[2] = wyRel * sinX + rz1 * cosX - v.camera.dist();
        return out;
    }

    void drawSunGlow(Matrix4f mat) {
        int sunIdx = 0;
        float[] pos = v.solarSystem.worldPosTo(v._tmpWp1, sunIdx, v.simTime);
        float dwx = pos[0] - v.camera.focalX(), dwz = pos[2] - v.camera.focalZ();
        float[] camPos = new float[3];
        v.camera.worldToCamera(camPos, 0, 0, 0, dwx, dwz);
        float depth = -camPos[2];
        if (depth < 0.15f) return;
        float sunR = 0;
        for (PlanetLayer l : v.solarSystem.get(sunIdx).layers()) if (l.type() == PlanetLayerType.BASE) sunR = l.radius();
        if (sunR < 0.01f) return;
        // 3D billboard: world-space disc at sun position, depth-tested against planets
        float cx3d = camPos[0], cy3d = camPos[1], cz3d = camPos[2];
        // 光晕颜色取恒星自身颜色（什么颜色就发什么光），不是写死的白色
        float[] bc = v.solarSystem.get(sunIdx).visual().baseColor();
        float gr = bc[0] * 0.65f + 0.35f;
        float gg = bc[1] * 0.65f + 0.35f;
        float gb = bc[2] * 0.65f + 0.35f;
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        // 单层连续日冕：从恒星边缘最亮，按二次曲线平滑衰减到外缘透明，避免分层断层
        drawGlowHalo(mat, cx3d, cy3d, cz3d, sunR * 1.00f, sunR * 2.40f, 0.35f, gr, gg, gb);
        RenderSystem.defaultBlendFunc();
    }

    /** 3D 辉光日冕：单层多段径向渐变，内缘最亮，按 (1-t)^2 平滑衰减到外缘透明。 */
    void drawGlowHalo(Matrix4f mat, float cx3d, float cy3d, float cz3d,
                      float innerR, float outerR, float alpha, float cr, float cg, float cb) {
        StarGlowRenderer.drawGlowHalo(mat, cx3d, cy3d, cz3d, innerR, outerR, alpha, cr, cg, cb);
    }

    /** 3D Billboard 发光圆盘：以恒星世界位置为中心，朝向摄像机的平面圆盘，从中心到边缘渐变透明。 */
    void drawGlowBillboard(Matrix4f mat, float cx3d, float cy3d, float cz3d,
                            float worldR, float alpha, float cr, float cg, float cb) {
        StarGlowRenderer.drawGlowBillboard(mat, cx3d, cy3d, cz3d, worldR, alpha, cr, cg, cb);
    }

    void drawOrbitalRings(Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        // 轨道：用连续的 TRIANGLE_STRIP 环带，每点沿切线垂直方向偏移（相机空间），横截面连续一致
        SolarSystemView.setupTransparentBlend();
        int segments = 256;
        for (int pi = 0; pi < v.solarSystem.size(); pi++) {
            Planet p = v.solarSystem.get(pi);
            float orbR = p.orbitalRadius();
            if (orbR < 0.01f) continue;
            float alpha = (pi == v.focalIndex) ? 0.75f : 0.40f;
            float orbCr = 1.0f, orbCg = 1.0f, orbCb = 1.0f;
            // 线宽随轨道半径增大，保证远近轨道在屏幕上都有 ~1px（白色半透明细线）
            boolean isMoon = p.parentId() >= 0;
            float hw = isMoon ? (0.018f + orbR * 0.00024f) : (0.036f + orbR * 0.00036f);
            // 预计算相机空间点（闭合环：多算两个点用于首尾衔接）
            int n = segments + 1;
            float[] px = ringPx, py = ringPy, pz = ringPz;
            boolean[] valid = ringValid;
            // 卫星轨道围绕母星，行星轨道围绕太阳
            float orbitCx = 0, orbitCz = 0;
            if (p.parentId() >= 0) {
                float[] pp = v.solarSystem.worldPosTo(v._tmpWp1, p.parentId(), v.simTime);
                orbitCx = pp[0]; orbitCz = pp[2];
            }
            for (int i = 0; i < n; i++) {
                float angle = (float) Math.PI * 2 * i / segments;
                float wx = orbitCx + (float) Math.cos(angle) * orbR;
                float wz = orbitCz + (float) Math.sin(angle) * orbR;
                float dwx = wx - v.camera.focalX(), dwz = wz - v.camera.focalZ();
                float rx = dwx * cosY + dwz * sinY;
                float rz1 = -dwx * sinY + dwz * cosY;
                float wyRel = -v.camera.focalY();
                float ry2 = wyRel * cosX - rz1 * sinX; float rz = wyRel * sinX + rz1 * cosX;
                float pzc = rz - v.camera.dist();
                px[i] = rx; py[i] = ry2; pz[i] = pzc;
                valid[i] = pzc < -0.05f;
            }
            // 闭合：第 n 个点 = 第 0 个点
            px[n] = px[0]; py[n] = py[0]; pz[n] = pz[0]; valid[n] = valid[0];
            // 预计算每个点的左右偏移（切线在相机空间 xy 平面的垂直方向）
            float[] lx = ringLx, ly = ringLy, rx2 = ringRx, ry2 = ringRy;
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
                        bb.addVertex(mat, lx[i], ly[i], pz[i]).setColor(orbCr, orbCg, orbCb, alpha);
                        bb.addVertex(mat, rx2[i], ry2[i], pz[i]).setColor(orbCr, orbCg, orbCb, alpha);
                    }
                    BufferUploader.drawWithShader(bb.buildOrThrow());
                }
                start = end + 1;
            }
        }
        SolarSystemView.teardownTransparent();
    }
    /** Compute moon shadow on planet surface vertex. Returns 0 (no shadow) to 1 (full shadow). */
    /** Quick check: does planet pi have any shadow-casting body (moon or parent)? */
    /** Draw a translucent flat ring band (background layer for belts) */
    private VertexBuffer buildBeltBandVBO(float innerR, float outerR, int bands,
            float cr, float cg, float cb, float baseAlpha) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
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
                bb.addVertex(v.idMat, x0 * r0, 0, z0 * r0).setColor(cr, cg, cb, alpha);
                bb.addVertex(v.idMat, x0 * r1, 0, z0 * r1).setColor(cr, cg, cb, alpha);
                bb.addVertex(v.idMat, x1 * r1, 0, z1 * r1).setColor(cr, cg, cb, alpha);
                bb.addVertex(v.idMat, x0 * r0, 0, z0 * r0).setColor(cr, cg, cb, alpha);
                bb.addVertex(v.idMat, x1 * r1, 0, z1 * r1).setColor(cr, cg, cb, alpha);
                bb.addVertex(v.idMat, x1 * r0, 0, z1 * r0).setColor(cr, cg, cb, alpha);
            }
        }
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();            // CRITICAL: bind own VAO so upload sets attribs on it, not the shared immediate VAO
        vb.upload(bb.buildOrThrow());
        VertexBuffer.unbind(); // restore VAO 0; next BufferUploader re-binds its own immediate buffer
        return vb;
    }
    void drawBeltBand(Matrix4f mat, float innerR, float outerR, int bands,
            float cosY, float sinY, float cosX, float sinX,
            float cr, float cg, float cb, float baseAlpha) {
        SolarSystemView.setupTransparentBlend();
        float dwx = -v.camera.focalX(), dwz = -v.camera.focalZ();
        v.buildTransformMatrix(dwx, 0f, dwz, 1, 0, 0, v.mvTmp);
        if (innerR < 100f) {
            if (beltBandVBO1 == null)
                beltBandVBO1 = buildBeltBandVBO(innerR, outerR, bands, cr, cg, cb, baseAlpha);
        } else {
            if (beltBandVBO2 == null)
                beltBandVBO2 = buildBeltBandVBO(innerR, outerR, bands, cr, cg, cb, baseAlpha);
        }
        VertexBuffer vb = (innerR < 100f) ? beltBandVBO1 : beltBandVBO2;
        ShaderInstance shader = GameRenderer.getPositionColorShader();
        org.joml.Matrix4f projMat = RenderSystem.getProjectionMatrix();
        vb.bind();            // CRITICAL: bind own VAO before draw
        vb.drawWithShader(v.mvTmp, projMat, shader);
        VertexBuffer.unbind(); // restore VAO 0 so following BufferUploader draws re-bind correctly
        SolarSystemView.teardownTransparent();
    }

    /** Render 3D rock polyhedra scattered in a belt — filled faces + wireframe edges */
    /** Render 3D rock polyhedra scattered in a belt — 真3D: cameraTo() + GPU透视投影 */
    void drawScatteredRocks(Matrix4f mat, float[][] particles,
            float cosY, float sinY, float cosX, float sinX) {
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true); RenderSystem.enableDepthTest();
        if (rockInstanced.ready() && PlanetShaders.rockShader() != null) {
            rockInstanced.draw(particles);
            RenderSystem.depthMask(true);
            return;
        }
        float savedTilt = v.currentTilt; v.currentTilt = 0;
        float fX = v.camera.focalX(), fZ = v.camera.focalZ();
        // GPU transform: build camera modelview once, send world-space coords
        v.buildTransformMatrix(-fX, 0f, -fZ, 1, 0, 0, v.mvTmp);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.set(v.mvTmp);
        RenderSystem.applyModelViewMatrix();
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < particles.length; i++) {
            float[] p = particles[i];
            float angle = p[0] + v.simTime * 0.006f;
            float radius = p[1], yPos = p[2], sz = p[3];
            float tiltA = p[4], tiltB = p[5];
            float cr = p[6], cg = p[7], cb = p[8];
            boolean low = (particles == v.kuiperPos) || (sz < 0.18f);
            Polyhedron mesh;
            if (low) {
                if (rockLowMesh == null) rockLowMesh = Polyhedron.rock(0L, 0.8f, 0);
                mesh = rockLowMesh;
            } else {
                if (rockHighMesh == null) rockHighMesh = Polyhedron.rock(0L, 0.8f, 1);
                mesh = rockHighMesh;
            }
            float wxCenter = (float) Math.cos(angle) * radius;
            float wzCenter = (float) Math.sin(angle) * radius;
            v.lighting.updateForWorldPos(wxCenter, wzCenter, cosY, sinY, cosX, sinX);
            float cosTA = (float) Math.cos(tiltA), sinTA = (float) Math.sin(tiltA);
            float cosTB = (float) Math.cos(tiltB), sinTB = (float) Math.sin(tiltB);
            for (int f = 0; f < mesh.faces.length; f++) {
                int[] fv = mesh.faces[f];
                float fnx = 0, fny = 0, fnz = 0;
                for (int vi : fv) { fnx += mesh.vertices[vi][0]; fny += mesh.vertices[vi][1]; fnz += mesh.vertices[vi][2]; }
                float fnLen = (float) Math.sqrt(fnx*fnx + fny*fny + fnz*fnz);
                if (fnLen > 1e-5f) { fnx /= fnLen; fny /= fnLen; fnz /= fnLen; }
                float rfnx = fnx * cosTA - fny * sinTA;
                float rfny = fnx * sinTA + fny * cosTA;
                float rfnz = fnz;
                float nwx = rfnx * cosTB + rfnz * sinTB;
                float nwy = rfny;
                float nwz = -rfnx * sinTB + rfnz * cosTB;
                float ndotl = nwx * v.lighting.dirX() + nwy * v.lighting.dirY() + nwz * v.lighting.dirZ();
                float lit = v.lighting.ambient() + (1 - v.lighting.ambient()) * v.lighting.direct(ndotl, 0);
                lit = Math.max(0.25f, lit);
                float lr = cr * lit, lg = cg * lit, lb = cb * lit;
                int n = Math.min(fv.length, 6);
                float[] vtx = rockVtx;
                for (int vk = 0; vk < n; vk++) {
                    float[] ve = mesh.vertices[fv[vk]];
                    float tlx = ve[0] * sz, tly = ve[1] * sz, tlz = ve[2] * sz;
                    float tlx2 = tlx * cosTA - tly * sinTA, tly2 = tlx * sinTA + tly * cosTA;
                    float flx = tlx2 * cosTB + tlz * sinTB;
                    float fly = tly2;
                    float flz = -tlx2 * sinTB + tlz * cosTB;
                    // World-space coords (GPU applies camera transform via modelview)
                    vtx[vk*3]   = flx + wxCenter;
                    vtx[vk*3+1] = fly + yPos;
                    vtx[vk*3+2] = flz + wzCenter;
                }
                for (int vi2 = 1; vi2 + 1 < n; vi2++) {
                    bb.addVertex(mat, vtx[0], vtx[1], vtx[2]).setColor(lr, lg, lb, 1f);
                    bb.addVertex(mat, vtx[vi2*3], vtx[vi2*3+1], vtx[vi2*3+2]).setColor(lr, lg, lb, 1f);
                    bb.addVertex(mat, vtx[(vi2+1)*3], vtx[(vi2+1)*3+1], vtx[(vi2+1)*3+2]).setColor(lr, lg, lb, 1f);
                }
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
        v.currentTilt = savedTilt;
        RenderSystem.depthMask(true);
    }


}
