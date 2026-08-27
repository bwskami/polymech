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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 科技树的 3D 可视化控件：一个自转的“地块球”（正六边形 + 12 五边形拼接）。
 * <p>
 * 视觉：纯黑背景；球体以线框显示，但正面用深色实体填充遮挡背面（不可透视看到背面线框）；
 * 每个科技地块用程序化六边形图标（按 tier 着色）表示，不使用 MC 物品/方块图标；
 * 悬停高亮 + 名称提示，点击有科技的面对应节点触发 {@code onSelect}（打开思索面板）。
 * </p>
 */
public class PolyhedronView extends UIElement {

    private final Polyhedron poly;
    private final List<TechNode> nodes;
    private final Consumer<TechNode> onSelect;

    private float yaw = 0.6f;
    private float pitch = 0.35f;
    private boolean dragging = false;
    private long lastNano = System.nanoTime();

    private int hoveredFace = -1;
    private int selectedFace = -1;

    /** 投影缓存（每次绘制更新）：绝对屏幕坐标 + 深度 z。 */
    private final float[] px, py, pz;

    public PolyhedronView(Polyhedron poly, List<TechNode> nodes, Consumer<TechNode> onSelect) {
        this.poly = poly;
        this.nodes = nodes;
        this.onSelect = onSelect;
        this.px = new float[poly.vertices.length];
        this.py = new float[poly.vertices.length];
        this.pz = new float[poly.vertices.length];

        addEventListener(UIEvents.MOUSE_DOWN, e -> dragging = true);
        addEventListener(UIEvents.MOUSE_UP, e -> dragging = false);
        addEventListener(UIEvents.MOUSE_MOVE, e -> {
            if (dragging) {
                yaw += e.deltaX * 0.01f;
                pitch += e.deltaY * 0.01f;
                pitch = Math.max(-1.4f, Math.min(1.4f, pitch));
            }
        });
        addEventListener(UIEvents.CLICK, e -> {
            if (hoveredFace >= 0 && hoveredFace < nodes.size()) {
                selectedFace = hoveredFace;
                onSelect.accept(nodes.get(hoveredFace));
            }
        });
        addEventListener(UIEvents.HOVER_TOOLTIPS, e -> {
            if (hoveredFace >= 0 && hoveredFace < nodes.size()) {
                List<Component> tips = new ArrayList<>();
                tips.add(nodes.get(hoveredFace).title());
                e.hoverTooltips = new HoverTooltips(tips, null, null, null);
            }
        });
    }

    @Override
    public void drawBackgroundAdditional(GUIContext guiContext) {
        long now = System.nanoTime();
        float dt = (now - lastNano) / 1.0e9f;
        lastNano = now;
        if (dt > 0.1f) dt = 0.1f;
        if (!dragging) yaw += dt * 0.3f; // 自转

        GuiGraphics g = guiContext.graphics;
        int x = (int) getPositionX();
        int y = (int) getPositionY();
        int w = (int) getSizeWidth();
        int h = (int) getSizeHeight();
        g.fill(x, y, x + w, y + h, 0xFF000000); // 纯黑背景

        float cx = x + w / 2f;
        float cy = y + h / 2f;
        float scale = Math.min(w, h) / 2f * 0.82f;

        // 旋转 + 正交投影（z 仅用于深度排序/剔除，绘制时置 0）
        float cosY = (float) Math.cos(yaw), sinY = (float) Math.sin(yaw);
        float cosX = (float) Math.cos(pitch), sinX = (float) Math.sin(pitch);
        for (int i = 0; i < poly.vertices.length; i++) {
            float[] v = poly.vertices[i];
            float x1 = v[0] * cosY + v[2] * sinY;
            float z1 = -v[0] * sinY + v[2] * cosY;
            float y2 = v[1] * cosX - z1 * sinX;
            float z2 = v[1] * sinX + z1 * cosX;
            px[i] = cx + x1 * scale;
            py[i] = cy - y2 * scale;
            pz[i] = z2;
        }

        hoveredFace = pickFace(guiContext.mouseX, guiContext.mouseY);

        // 仅保留朝向相机的正面
        Integer[] front = new Integer[poly.faces.length];
        int cnt = 0;
        for (int f = 0; f < poly.faces.length; f++) {
            if (avgZ(f) > 0) front[cnt++] = f;
        }
        Integer[] fs = Arrays.copyOf(front, cnt);
        Arrays.sort(fs, Comparator.comparingDouble(this::avgZ)); // 远 -> 近

        g.flush();
        Matrix4f matrix = g.pose().last().pose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();

        // 正面填充（fan 三角化）：深色实体 + tier 微染色，高不透明以遮挡背面
        BufferBuilder fb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int fi : fs) {
            int[] fv = poly.faces[fi];
            boolean hasNode = fi < nodes.size();
            boolean hot = (fi == hoveredFace) || (fi == selectedFace);
            int col = hasNode ? tierColor(nodes.get(fi).tier()) : 0x263238;
            float tint = hot ? 0.6f : 0.4f;
            float r = ((col >> 16) & 0xFF) / 255f * tint;
            float gg = ((col >> 8) & 0xFF) / 255f * tint;
            float b = (col & 0xFF) / 255f * tint;
            float a = hasNode ? (hot ? 0.96f : 0.85f) : 0.92f;
            float ccx = 0, ccy = 0;
            for (int k : fv) { ccx += px[k]; ccy += py[k]; }
            ccx /= fv.length; ccy /= fv.length;
            for (int k = 0; k < fv.length; k++) {
                int a0 = fv[k], a1 = fv[(k + 1) % fv.length];
                fb.addVertex(matrix, ccx, ccy, 0).setColor(r, gg, b, a);
                fb.addVertex(matrix, px[a0], py[a0], 0).setColor(r, gg, b, a);
                fb.addVertex(matrix, px[a1], py[a1], 0).setColor(r, gg, b, a);
            }
        }
        BufferUploader.drawWithShader(fb.build());

        // 线框（仅正面边：边中点朝相机）
        RenderSystem.lineWidth(1.5f);
        BufferBuilder lb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        float er = 0.4f, eg = 0.95f, eb = 1.0f, ea = 0.9f;
        for (int[] ed : poly.edges) {
            int i0 = ed[0], i1 = ed[1];
            if ((pz[i0] + pz[i1]) * 0.5f <= 0) continue;
            lb.addVertex(matrix, px[i0], py[i0], 0).setColor(er, eg, eb, ea);
            lb.addVertex(matrix, px[i1], py[i1], 0).setColor(er, eg, eb, ea);
        }
        BufferUploader.drawWithShader(lb.build());

        // 科技地块图标（程序化六边形，非 MC 物品）
        for (int fi = 0; fi < nodes.size(); fi++) {
            if (avgZ(fi) <= 0) continue;
            int[] fv = poly.faces[fi];
            float mx = 0, my = 0;
            for (int k : fv) { mx += px[k]; my += py[k]; }
            mx /= fv.length; my /= fv.length;
            drawTileIcon(g, fi, mx, my, scale);
        }
    }

    /** 程序化六边形图标（按 tier 着色，悬停更亮），非 MC 物品/方块图标。 */
    private void drawTileIcon(GuiGraphics g, int fi, float mx, float my, float scale) {
        TechNode node = nodes.get(fi);
        int col = tierColor(node.tier());
        boolean hot = (fi == hoveredFace) || (fi == selectedFace);
        float r = Math.min(scale * 0.16f, 30f);
        float[] hx = new float[6], hy = new float[6];
        for (int k = 0; k < 6; k++) {
            double ang = Math.PI / 6 + k * Math.PI / 3; // 尖角朝上
            hx[k] = mx + (float) Math.cos(ang) * r;
            hy[k] = my + (float) Math.sin(ang) * r;
        }
        Matrix4f matrix = g.pose().last().pose();
        float cr = ((col >> 16) & 0xFF) / 255f;
        float cg = ((col >> 8) & 0xFF) / 255f;
        float cb = (col & 0xFF) / 255f;

        // 填充
        float fa = hot ? 0.5f : 0.28f;
        BufferBuilder fb = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        for (int k = 0; k < 6; k++) {
            int a1 = (k + 1) % 6;
            fb.addVertex(matrix, mx, my, 0).setColor(cr, cg, cb, fa);
            fb.addVertex(matrix, hx[k], hy[k], 0).setColor(cr, cg, cb, fa);
            fb.addVertex(matrix, hx[a1], hy[a1], 0).setColor(cr, cg, cb, fa);
        }
        BufferUploader.drawWithShader(fb.build());

        // 描边
        float oa = hot ? 1f : 0.85f;
        BufferBuilder lb = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR);
        for (int k = 0; k < 6; k++) {
            int a1 = (k + 1) % 6;
            lb.addVertex(matrix, hx[k], hy[k], 0).setColor(cr, cg, cb, oa);
            lb.addVertex(matrix, hx[a1], hy[a1], 0).setColor(cr, cg, cb, oa);
        }
        BufferUploader.drawWithShader(lb.build());
    }

    private float avgZ(int f) {
        int[] fv = poly.faces[f];
        float s = 0;
        for (int k : fv) s += pz[k];
        return s / fv.length;
    }

    private int pickFace(int mx, int my) {
        int best = -1;
        float bestZ = Float.NEGATIVE_INFINITY;
        for (int f = 0; f < poly.faces.length; f++) {
            if (avgZ(f) <= 0) continue; // 背面忽略
            int[] fv = poly.faces[f];
            if (pointInPoly(mx, my, fv)) {
                float z = avgZ(f);
                if (z > bestZ) {
                    bestZ = z;
                    best = f;
                }
            }
        }
        return best;
    }

    private boolean pointInPoly(float qx, float qy, int[] fv) {
        boolean inside = false;
        int n = fv.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float xi = px[fv[i]], yi = py[fv[i]];
            float xj = px[fv[j]], yj = py[fv[j]];
            boolean intersect = ((yi > qy) != (yj > qy)) &&
                    (qx < (xj - xi) * (qy - yi) / (yj - yi) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
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
}
