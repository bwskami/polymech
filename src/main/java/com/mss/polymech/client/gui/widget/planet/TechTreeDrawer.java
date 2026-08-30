package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;
import java.util.Map;

/**
 * 科技树 UI：标记面、高亮选中、标签、拾取。
 */
class TechTreeDrawer {
    final SolarSystemView v;

    TechTreeDrawer(SolarSystemView v) { this.v = v; }

    void drawTechMarkers(GuiGraphics g2, Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focalLength, float cx, float cy) {
        // 与 PolyhedronView 一致：科技项用 3D 半透明面 + 亮边绘制在 TECH 层
        Planet fp = v.solarSystem.get(v.focalIndex);
        // Unified: tech markers / wireframe / selection all use WIREFRAME's mesh+radius
        PlanetLayer gridL = gridLayer(fp);
        if (gridL == null) return;
        float gridR = gridL.radius();
        Polyhedron gridMesh = fp.resolveGeometry(gridL);
        float[] wp = v.solarSystem.worldPos(v.focalIndex, v.simTime);
        float dwx = wp[0] - v.camera.focalX(), dwz = wp[2] - v.camera.focalZ();
        float selfAngle = fp.resolveRotationSpeed(gridL) * v.simTime;
        float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);
        v.currentTilt = fp.axialTilt();

        if (v.overlayFade < 0.01f) return;
        List<TechNode> focalNodes = fp.techNodes();
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
                float[] cam = v.camera.camera(gridMesh.vertices[fv[k]], gridR, dwx, dwz, sc, ss, v.currentTilt);
                px[k] = cam[0]; py[k] = cam[1]; pz[k] = cam[2];
                ccx += cam[0]; ccy += cam[1]; ccz += cam[2];
                if (cam[2] > 0) behind = true;
            }
            if (behind) continue;
            ccx /= fv.length; ccy /= fv.length; ccz /= fv.length;
            // 半透明面
            float fa = 0.28f * v.overlayFade;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                bb.addVertex(mat, ccx, ccy, ccz).setColor(cr, cg, cb, fa);
                bb.addVertex(mat, px[k], py[k], pz[k]).setColor(cr, cg, cb, fa);
                bb.addVertex(mat, px[a1], py[a1], pz[a1]).setColor(cr, cg, cb, fa);
            }
            // 亮边
            float ea = 0.8f * v.overlayFade; float hw = 0.006f;
            for (int k = 0; k < fv.length; k++) {
                int a1 = (k + 1) % fv.length;
                SolarSystemView.addQuad3D(bb, mat, px[k], py[k], pz[k], px[a1], py[a1], pz[a1], hw, cr, cg, cb, ea);
            }
            drew = true;
        }
        if (drew) BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    PlanetLayer gridLayer(Planet p) {
        for (PlanetLayer l : p.layers()) if (l.type() == PlanetLayerType.WIREFRAME) return l;
        return null;
    }

    void drawTechHighlight(Matrix4f mat, float cosY, float sinY, float cosX, float sinX, float focalLength, float cx, float cy) {
        int f;
        if (v.hoveredTile >= 0) f = v.hoveredTile;
        else if (v.selectedTile >= 0) f = v.selectedTile;
        else f = -1;

        long now = System.nanoTime();
        float dt = (now - v.lastHighlightNano) / 1e9f;
        v.lastHighlightNano = now;
        if (dt > 0.1f) dt = 0.1f;

        boolean active = (f >= 0);
        if (active) { if (v.hoverStartNano == 0) v.hoverStartNano = now; } else v.hoverStartNano = 0;
        float elapsed = active ? (now - v.hoverStartNano) / 1e9f : 0f;
        float fadeIn = 1f - (float) Math.pow(1f - Math.min(1f, elapsed / 0.12f), 3);
        v.hoverAlpha += ((active ? fadeIn : 0f) - v.hoverAlpha) * Math.min(1f, dt * (active ? 18f : 8f));
        if (v.hoverAlpha < 0.005f) { v.hoverAlpha = 0; v.chaseFace = -1; v.chaseActive = false; return; }
        float a = v.hoverAlpha * (0.85f + 0.15f * (float) Math.sin(elapsed * 4.5f)) * v.overlayFade;

        Planet fp = v.solarSystem.get(v.focalIndex);
        v.currentTilt = fp.axialTilt();
        PlanetLayer gridL = gridLayer(fp);
        if (gridL == null) return;
        float wireR = gridL.radius();
        Polyhedron mesh = fp.resolveGeometry(gridL);

        if (f >= 0 && f != v.chaseFace) {
            boolean firstEver = (v.chaseFace == -1);
            v.chaseFace = f; v.chaseActive = true; v.chaseFaceVerts = mesh.faces[f].length;
            int[] fv = mesh.faces[f];
            if (firstEver) {
                for (int i = 0; i < fv.length; i++) {
                    v.chaseWx[i] = mesh.vertices[fv[i]][0];
                    v.chaseWy[i] = mesh.vertices[fv[i]][1];
                    v.chaseWz[i] = mesh.vertices[fv[i]][2];
                }
                v.chaseWMx = 0; v.chaseWMy = 0; v.chaseWMz = 0;
                for (int vi : fv) { v.chaseWMx += mesh.vertices[vi][0]; v.chaseWMy += mesh.vertices[vi][1]; v.chaseWMz += mesh.vertices[vi][2]; }
                v.chaseWMx /= fv.length; v.chaseWMy /= fv.length; v.chaseWMz /= fv.length;
                if (fv.length < 6) { v.chaseWx[5] = v.chaseWMx; v.chaseWy[5] = v.chaseWMy; v.chaseWz[5] = v.chaseWMz; }
                v.chaseActive = false;
            }
        }
        if (f < 0) { v.chaseFace = -1; v.chaseActive = false; return; }
        if (v.chaseFace < 0) return;

        int[] fv = mesh.faces[v.chaseFace];
        int drawN = fv.length;

        if (v.chaseActive) {
            float ch = 1f - (float) Math.exp(-dt / 0.06f);
            float maxD2 = 0;
            for (int i = 0; i < fv.length; i++) {
                float ttx = mesh.vertices[fv[i]][0];
                float tty = mesh.vertices[fv[i]][1];
                float ttz = mesh.vertices[fv[i]][2];
                v.chaseWx[i] += (ttx - v.chaseWx[i]) * ch;
                v.chaseWy[i] += (tty - v.chaseWy[i]) * ch;
                v.chaseWz[i] += (ttz - v.chaseWz[i]) * ch;
                float dx = ttx - v.chaseWx[i], dy = tty - v.chaseWy[i], dz = ttz - v.chaseWz[i];
                maxD2 = Math.max(maxD2, dx * dx + dy * dy + dz * dz);
            }
            v.chaseWMx = 0; v.chaseWMy = 0; v.chaseWMz = 0;
            for (int i = 0; i < fv.length; i++) { v.chaseWMx += v.chaseWx[i]; v.chaseWMy += v.chaseWy[i]; v.chaseWMz += v.chaseWz[i]; }
            v.chaseWMx /= fv.length; v.chaseWMy /= fv.length; v.chaseWMz /= fv.length;
            if (fv.length < 6) { v.chaseWx[5] = v.chaseWMx; v.chaseWy[5] = v.chaseWMy; v.chaseWz[5] = v.chaseWMz; }
            if (maxD2 < 1e-6f) v.chaseActive = false;
        } else {
            for (int i = 0; i < fv.length; i++) {
                v.chaseWx[i] = mesh.vertices[fv[i]][0];
                v.chaseWy[i] = mesh.vertices[fv[i]][1];
                v.chaseWz[i] = mesh.vertices[fv[i]][2];
            }
            v.chaseWMx = 0; v.chaseWMy = 0; v.chaseWMz = 0;
            for (int vi : fv) { v.chaseWMx += mesh.vertices[vi][0]; v.chaseWMy += mesh.vertices[vi][1]; v.chaseWMz += mesh.vertices[vi][2]; }
            v.chaseWMx /= fv.length; v.chaseWMy /= fv.length; v.chaseWMz /= fv.length;
            if (fv.length < 6) { v.chaseWx[5] = v.chaseWMx; v.chaseWy[5] = v.chaseWMy; v.chaseWz[5] = v.chaseWMz; }
        }

        float[] wp = v.solarSystem.worldPos(v.focalIndex, v.simTime);
        float dwx = wp[0] - v.camera.focalX(), dwz = wp[2] - v.camera.focalZ();
        float selfAngle = fp.resolveRotationSpeed(gridL) * v.simTime;
        float sc = (float) Math.cos(selfAngle), ss = (float) Math.sin(selfAngle);

        float[] pcx = new float[6], pcy = new float[6], pcz = new float[6];
        for (int i = 0; i < drawN; i++) {
            float[] cam = v.camera.camera(new float[]{v.chaseWx[i], v.chaseWy[i], v.chaseWz[i]}, wireR, dwx, dwz, sc, ss, v.currentTilt);
            pcx[i] = cam[0]; pcy[i] = cam[1]; pcz[i] = cam[2];
        }
        float[] cm = v.camera.camera(new float[]{v.chaseWMx, v.chaseWMy, v.chaseWMz}, wireR, dwx, dwz, sc, ss, v.currentTilt);

        float cr, cg, cb;
        List<TechNode> hNodes = v.solarSystem.get(v.focalIndex).techNodes();

        if (v.chaseFace >= 0 && v.chaseFace < hNodes.size()) {
            int col = tierColor(hNodes.get(v.chaseFace).tier());
            cr = ((col >> 16) & 0xFF) / 255f; cg = ((col >> 8) & 0xFF) / 255f; cb = (col & 0xFF) / 255f;
        } else if (v.chaseFace >= 0) {
            cr = Math.min(1f, v.faceColors[v.focalIndex][v.chaseFace][0] * 1.5f);
            cg = Math.min(1f, v.faceColors[v.focalIndex][v.chaseFace][1] * 1.5f);
            cb = Math.min(1f, v.faceColors[v.focalIndex][v.chaseFace][2] * 1.5f);
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
            SolarSystemView.addQuad3D(bb, mat, pcx[k], pcy[k], pcz[k], pcx[a1], pcy[a1], pcz[a1], hw, cr, cg, cb, ea);
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
    void drawLabels(GuiGraphics g, int vx, int vy, int vw, int vh, float cosY, float sinY, float cosX, float sinX, float focal, float cx, float cy) {
        var font = Minecraft.getInstance().font;
        for (int pi = 0; pi < v.solarSystem.size(); pi++) {
            float[] pos = v.solarSystem.worldPos(pi, v.simTime);
            float dwx = pos[0] - v.camera.focalX(), dwz = pos[2] - v.camera.focalZ();
            float rx = dwx * cosY + dwz * sinY;
            float rz1 = -dwx * sinY + dwz * cosY;
            float ry2 = -rz1 * sinX;
            float rz = rz1 * cosX;
            float camZ = rz - v.camera.dist();
            if (camZ > -0.5f) continue;
            float scrX = cx + rx * focal / Math.max(-camZ, 0.01f);
            float scrY = cy - ry2 * focal / Math.max(-camZ, 0.01f);
            float sz = Math.min(1.5f, focal / Math.max(-camZ, 0.01f) * 1.2f);
            if (sz < 0.15f) continue;
            g.drawCenteredString(font, v.solarSystem.get(pi).name(), (int) scrX, (int) scrY, 0xFFCCCCDD);
        }
    }
    int pickPlanet(int mx, int my, float focalLength, float pcx, float pcy) {
        float cosY = (float) Math.cos(v.camera.yaw()), sinY = (float) Math.sin(v.camera.yaw());
        float cosX = (float) Math.cos(v.camera.pitch()), sinX = (float) Math.sin(v.camera.pitch());
        int best = -1; float bestZ = 0;
        for (int pi = 0; pi < v.solarSystem.size(); pi++) {
            float[] pos = v.solarSystem.worldPos(pi, v.simTime);
            float dwx = pos[0] - v.camera.focalX(), dwz = pos[2] - v.camera.focalZ();
            float rz1 = -dwx * sinY + dwz * cosY;
            float camZ = rz1 * cosX - v.camera.dist();
            if (camZ > -0.2f) continue;
            float rx = dwx * cosY + dwz * sinY;
            float ry2 = -rz1 * sinX;
            Planet p = v.solarSystem.get(pi);
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
    static List<int[]> buildEdges(Polyhedron mesh) {
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
}
