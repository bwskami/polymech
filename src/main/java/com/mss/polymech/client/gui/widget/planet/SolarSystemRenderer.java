package com.mss.polymech.client.gui.widget.planet;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mss.polymech.techtree.Polyhedron;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 太空世界用星球渲染器：静态单位球 VBO + 每帧模型矩阵缩放。
 */
public final class SolarSystemRenderer {

    private static final Polyhedron UNIT_SPHERE = Polyhedron.goldberg(3);
    private static final Map<PlanetVisual, VertexBuffer> VBO_CACHE = new IdentityHashMap<>();

    private SolarSystemRenderer() {
    }

    public static void renderBody(Matrix4f view, double relX, double relY, double relZ,
                                  double radius, PlanetVisual visual) {
        if (radius <= 0) return;
        float[] base = visual.baseColor();
        if (base == null) return;

        Matrix4f model = new Matrix4f(view)
                .translate((float) relX, (float) relY, (float) relZ)
                .scale((float) radius);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();

        VertexBuffer vbo = getOrCreateVbo(visual);
        if (vbo == null) return;
        vbo.bind();
        vbo.drawWithShader(model, RenderSystem.getProjectionMatrix(), GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.enableCull();
    }

    private static VertexBuffer getOrCreateVbo(PlanetVisual visual) {
        VertexBuffer cached = VBO_CACHE.get(visual);
        if (cached != null) return cached;

        float[] base = visual.baseColor();
        if (base == null) return null;
        float[][] verts = UNIT_SPHERE.vertices;
        int[][] faces = UNIT_SPHERE.faces;
        Noise3 noise = new Noise3(0x5EED1234L ^ visual.hashCode());

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int f = 0; f < faces.length; f++) {
            int[] fv = faces[f];
            float cx = 0, cy = 0, cz = 0;
            for (int v : fv) {
                cx += verts[v][0];
                cy += verts[v][1];
                cz += verts[v][2];
            }
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            if (len < 1e-6f) len = 1f;
            cx /= len; cy /= len; cz /= len;
            float n = noise.noise(cx * 3.0f, cy * 3.0f, cz * 3.0f);
            float mul = 0.85f + 0.15f * n;
            float r = Math.min(1f, base[0] * mul);
            float g = Math.min(1f, base[1] * mul);
            float b = Math.min(1f, base[2] * mul);

            for (int k = 1; k + 1 < fv.length; k++) {
                bb.addVertex(verts[fv[0]][0], verts[fv[0]][1], verts[fv[0]][2]).setColor(r, g, b, 1f);
                bb.addVertex(verts[fv[k]][0], verts[fv[k]][1], verts[fv[k]][2]).setColor(r, g, b, 1f);
                bb.addVertex(verts[fv[k + 1]][0], verts[fv[k + 1]][1], verts[fv[k + 1]][2]).setColor(r, g, b, 1f);
            }
        }
        var rendered = bb.buildOrThrow();
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(rendered);
        VertexBuffer.unbind();
        VBO_CACHE.put(visual, vbo);
        return vbo;
    }

    public static void close() {
        VBO_CACHE.values().forEach(VertexBuffer::close);
        VBO_CACHE.clear();
    }
}
