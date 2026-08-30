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
import com.mss.polymech.techtree.Polyhedron;

/**
 * 轨道环、小行星带、Kuiper 带、恒星光晕。
 */
class OrbitalDrawer {
    final SolarSystemView v;

    OrbitalDrawer(SolarSystemView v) { this.v = v; }

    void drawSunGlow(Matrix4f mat) {
        int sunIdx = 0;
        float[] pos = v.solarSystem.worldPos(sunIdx, v.simTime);
        float dwx = pos[0] - v.camera.focalX(), dwz = pos[2] - v.camera.focalZ();
        float[] camPos = new float[3];
        v.camera.worldToCamera(camPos, 0, 0, 0, dwx, dwz);
        float depth = -camPos[2];
        if (depth < 0.15f) return; // 相机在恒星内/后
        float sunR = 0;
        for (PlanetLayer l : v.solarSystem.get(sunIdx).layers()) if (l.type() == PlanetLayerType.BASE) sunR = l.radius();
        if (sunR < 0.01f) return;
        float[] scr = v.camera.toScreen(camPos);
        float sx = scr[0], sy = scr[1];
        float rPx = sunR * v.camera.focalLength() / depth;      // 恒星投影半径（像素）
        if (rPx < 2f) return;                                  // 太小不可见
        float scale = Math.min(1f, Math.max(0.35f, rPx / 28f)); // 远处光晕收敛
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        // 内层亮核：贴着恒星边缘的暖光
        drawGlowFan(mat, sx, sy, rPx * 1.45f, 0.50f * scale, 1.0f, 0.90f, 0.65f);
        // 外层泛光：大范围暖橙渐隐
        drawGlowFan(mat, sx, sy, rPx * 2.8f, 0.14f * scale, 1.0f, 0.72f, 0.35f);
        RenderSystem.defaultBlendFunc();
    }

    /** 屏幕空间环形渐变光晕（TRIANGLE_FAN：中心实色 → 边缘透明）。 */
    void drawGlowFan(Matrix4f mat, float sx, float sy, float outerR,
                             float alpha, float cr, float cg, float cb) {
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(mat, sx, sy, 0).setColor(cr, cg, cb, alpha);
        int seg = 56;
        for (int j = 0; j <= seg; j++) {
            float a = (float) (Math.PI * 2 * j / seg);
            bb.addVertex(mat, sx + (float) Math.cos(a) * outerR, sy + (float) Math.sin(a) * outerR, 0)
               .setColor(cr, cg, cb, 0f);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    void drawOrbitalRings(Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        // 轨道：用连续的 TRIANGLE_STRIP 环带，每点沿切线垂直方向偏移，横截面连续一致
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.depthMask(false); RenderSystem.enableDepthTest();
        int segments = 256;
        for (int pi = 0; pi < v.solarSystem.size(); pi++) {
            Planet p = v.solarSystem.get(pi);
            float orbR = p.orbitalRadius();
            if (orbR < 0.01f) continue;
            float alpha = (pi == v.focalIndex) ? 0.90f : 0.55f;
            float[] rgb = (pi == v.focalIndex) ? new float[]{0.50f, 0.78f, 1.0f} : new float[]{0.45f, 0.62f, 0.85f};
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
                float[] pp = v.solarSystem.worldPos(p.parentId(), v.simTime);
                orbitCx = pp[0]; orbitCz = pp[2];
            }
            for (int i = 0; i < n; i++) {
                float angle = (float) Math.PI * 2 * i / segments;
                float wx = orbitCx + (float) Math.cos(angle) * orbR;
                float wz = orbitCz + (float) Math.sin(angle) * orbR;
                float dwx = wx - v.camera.focalX(), dwz = wz - v.camera.focalZ();
                float rx = dwx * cosY + dwz * sinY;
                float rz1 = -dwx * sinY + dwz * cosY;
                float ry2 = -rz1 * sinX; float rz = rz1 * cosX;
                float pzc = rz - v.camera.dist();
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
    void drawBeltBand(Matrix4f mat, float innerR, float outerR, int bands,
            float cosY, float sinY, float cosX, float sinX,
            float cr, float cg, float cb, float baseAlpha) {
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false); RenderSystem.enableDepthTest();
        float savedTilt = v.currentTilt; v.currentTilt = 0;
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
                float ddx = -v.camera.focalX(), ddz = -v.camera.focalZ();
                p00[0] = x0 * r0; p00[1] = 0; p00[2] = z0 * r0;
                p01[0] = x0 * r1; p01[1] = 0; p01[2] = z0 * r1;
                p11[0] = x1 * r1; p11[1] = 0; p11[2] = z1 * r1;
                p10[0] = x1 * r0; p10[1] = 0; p10[2] = z1 * r0;
                v.camera.cameraTo(c00, p00, 1, ddx, ddz, 1, 0, v.currentTilt);
                v.camera.cameraTo(c01, p01, 1, ddx, ddz, 1, 0, v.currentTilt);
                v.camera.cameraTo(c11, p11, 1, ddx, ddz, 1, 0, v.currentTilt);
                v.camera.cameraTo(c10, p10, 1, ddx, ddz, 1, 0, v.currentTilt);
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
        v.currentTilt = savedTilt;
        RenderSystem.depthMask(true);
    }
    /** Render 3D rock polyhedra scattered in a belt — filled faces + wireframe edges */
    /** Render 3D rock polyhedra scattered in a belt — 真3D: cameraTo() + GPU透视投影 */
    void drawScatteredRocks(Matrix4f mat, float[][] particles,
            float cosY, float sinY, float cosX, float sinX) {
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true); RenderSystem.enableDepthTest();
        float savedTilt = v.currentTilt; v.currentTilt = 0;
        // mesh selected per-rock below
        float[] cam = new float[3];
        // 第一遍：填充面
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < particles.length; i++) {
            float[] p = particles[i];
            float angle = p[0] + v.simTime * 0.006f;
            float radius = p[1], yPos = p[2], sz = p[3];
            float tiltA = p[4], tiltB = p[5];
            float cr = p[6], cg = p[7], cb = p[8];
            int seed = (int) p[8]; Polyhedron mesh = v.rockCache.getOrDefault((long)seed, null);
            if (mesh == null) { mesh = Polyhedron.rock((long)seed, 0.8f); v.rockCache.put((long)seed, mesh); }
            float dwx = (float) Math.cos(angle) * radius - v.camera.focalX();
            float dwz = (float) Math.sin(angle) * radius - v.camera.focalZ();
            v.lighting.updateForWorldPos(dwx + v.camera.focalX(), dwz + v.camera.focalZ(), cosY, sinY, cosX, sinX);
            float cosTA = (float) Math.cos(tiltA), sinTA = (float) Math.sin(tiltA);
            float cosTB = (float) Math.cos(tiltB), sinTB = (float) Math.sin(tiltB);
            for (int f = 0; f < mesh.faces.length; f++) {
                int[] fv = mesh.faces[f];
                // 计算面法线用于光照
                float fnx = 0, fny = 0, fnz = 0;
                for (int vi : fv) { fnx += mesh.vertices[vi][0]; fny += mesh.vertices[vi][1]; fnz += mesh.vertices[vi][2]; }
                float fnLen = (float) Math.sqrt(fnx*fnx + fny*fny + fnz*fnz);
                if (fnLen > 1e-5f) { fnx /= fnLen; fny /= fnLen; fnz /= fnLen; }
                // 碎石自转
                float rfnx = fnx * cosTA - fny * sinTA;
                float rfny = fnx * sinTA + fny * cosTA;
                float rfnz = fnz;
                float nwx = rfnx * cosTB + rfnz * sinTB;
                float nwy = rfny;
                float nwz = -rfnx * sinTB + rfnz * cosTB;
                float ndotl = nwx * v.lighting.dirX() + nwy * v.lighting.dirY() + nwz * v.lighting.dirZ();
                float ambR = v.lighting.ambient();
                float lit = ambR + (1 - ambR) * v.lighting.direct(ndotl, 0);
                lit = Math.max(0.25f, lit);
                float lr = cr * lit, lg = cg * lit, lb = cb * lit;
                // 用 cameraTo() 投影每个顶点 — 跟行星完全一致的真3D管线
                boolean behind = false;
                float[] vsx = new float[fv.length], vsy = new float[fv.length], vsz = new float[fv.length];
                for (int vk = 0; vk < fv.length; vk++) {
                    float[] vtx = mesh.vertices[fv[vk]];
                    // apply tiltA then tiltB
                    float tlx = vtx[0] * sz, tly = vtx[1] * sz, tlz = vtx[2] * sz;
                    float tlx2 = tlx * cosTA - tly * sinTA, tly2 = tlx * sinTA + tly * cosTA;
                    float flx = tlx2 * cosTB + tlz * sinTB;
                    float fly = tly2;
                    float flz = -tlx2 * sinTB + tlz * cosTB;
                    // build a fake planet layer vertex for cameraTo()
                    // cameraTo needs: v[0]=lx, v[1]=ly, v[2]=lz, layerR=1, dwx, dwz, sc=cos(rotAngle), ss=sin(rotAngle)
                    float[] localV = { flx, fly + yPos, flz };
                    v.camera.cameraTo(cam, localV, 1f, dwx, dwz, 1f, 0f, v.currentTilt);
                    vsx[vk] = cam[0]; vsy[vk] = cam[1]; vsz[vk] = cam[2];
                    if (cam[2] > -0.02f) behind = true;
                }
                if (behind) continue;
                for (int vi2 = 1; vi2 + 1 < fv.length; vi2++) {
                    bb.addVertex(mat, vsx[0], vsy[0], vsz[0]).setColor(lr, lg, lb, 1f);
                    bb.addVertex(mat, vsx[vi2], vsy[vi2], vsz[vi2]).setColor(lr, lg, lb, 1f);
                    bb.addVertex(mat, vsx[vi2+1], vsy[vi2+1], vsz[vi2+1]).setColor(lr, lg, lb, 1f);
                }
            }
        }
        var rendered = bb.build();
        if (rendered != null) BufferUploader.drawWithShader(rendered);

        v.currentTilt = savedTilt;
        RenderSystem.depthMask(true);
    }

}
