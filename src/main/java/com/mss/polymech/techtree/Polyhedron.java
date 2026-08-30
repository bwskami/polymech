package com.mss.polymech.techtree;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 多面体几何。
 * <p>
 * - {@link #icosphere(int)}：三角面测地线球（备用）。
 * - {@link #goldberg(int)}：正六边形 + 12 个五边形拼接的“地块球”（足球/穹顶式），
 *   由细分测地线球的<b>对偶</b>得到：每个三角面的中心成为顶点，每个原顶点周围一圈三角面中心组成一个多边形面。
 *   每个面可映射为一个科技“地块”。与科技树数据解耦，渲染/交互在 {@code PolyhedronView}。
 * </p>
 */
public final class Polyhedron {

    /** 单位球面上的顶点 [v][x,y,z] */
    public final float[][] vertices;
    /** 面 [f][k] 顶点索引（k 为 5 或 6，即五边形/六边形） */
    public final int[][] faces;
    /** 去重后的边 [e][2] 顶点索引 */
    public final int[][] edges;

    private Polyhedron(float[][] vertices, int[][] faces, int[][] edges) {
        this.vertices = vertices;
        this.faces = faces;
        this.edges = edges;
    }

    // ============================ 三角测地线球（备用） ============================

    /** 由任意顶点/面直接构造（面自动推边）。供细分光照网格等使用。 */
    public static Polyhedron of(float[][] vertices, int[][] faces) {
        return new Polyhedron(vertices, faces, buildEdges(java.util.Arrays.asList(faces)));
    }

    public static Polyhedron icosphere(int level) {
        var g = geodesic(level);
        return new Polyhedron(g.v.toArray(new float[0][]), g.faces.toArray(new int[0][]), buildEdges(g.faces));
    }

    /**
     * 生成 UV 球（纬度 stacks × 经度 slices 的三角网格），用于渲染一个平滑着色的实心球。
     * 顶点即其法线方向（单位球），便于朗伯着色。
     */
    public static Polyhedron sphere(int stacks, int slices) {
        List<float[]> verts = new ArrayList<>();
        int[][] grid = new int[stacks + 1][slices];
        for (int i = 0; i <= stacks; i++) {
            float phi = (float) (Math.PI * i / stacks);
            float y = (float) Math.cos(phi);
            float r = (float) Math.sin(phi);
            for (int j = 0; j < slices; j++) {
                float theta = (float) (2 * Math.PI * j / slices);
                grid[i][j] = verts.size();
                verts.add(new float[]{r * (float) Math.cos(theta), y, r * (float) Math.sin(theta)});
            }
        }
        List<int[]> faces = new ArrayList<>();
        for (int i = 0; i < stacks; i++) {
            for (int j = 0; j < slices; j++) {
                int j2 = (j + 1) % slices;
                int a = grid[i][j], b = grid[i][j2], c = grid[i + 1][j2], d = grid[i + 1][j];
                faces.add(new int[]{a, b, c});
                faces.add(new int[]{a, c, d});
            }
        }
        // 经纬网格边（可选线框，绘制时只画正面）
        Set<Long> seen = new TreeSet<>();
        List<int[]> edgeList = new ArrayList<>();
        for (int i = 0; i < stacks; i++) {
            for (int j = 0; j < slices; j++) {
                int j2 = (j + 1) % slices;
                addEdge(seen, edgeList, grid[i][j], grid[i][j2]);
                addEdge(seen, edgeList, grid[i][j], grid[i + 1][j]);
            }
        }
        return new Polyhedron(verts.toArray(new float[0][]), faces.toArray(new int[0][]), edgeList.toArray(new int[0][]));
    }

    // ============================ 正六边形拼接（地块球） ============================

    /**
     * 生成细分 subdiv 次的正六边形拼接球（Goldberg 多面体）。
     * subdiv=2 → 12 五边形 + 50 六边形 = 62 个地块面。
     */
    public static Polyhedron goldberg(int subdiv) {
        var g = geodesic(subdiv);
        int nT = g.faces.size();
        // 每个三角面的中心（单位球方向）= 对偶顶点
        float[][] cen = new float[nT][];
        for (int f = 0; f < nT; f++) {
            int[] tri = g.faces.get(f);
            float[] a = g.v.get(tri[0]), b = g.v.get(tri[1]), c = g.v.get(tri[2]);
            cen[f] = normalize(new float[]{
                    (a[0] + b[0] + c[0]) / 3,
                    (a[1] + b[1] + c[1]) / 3,
                    (a[2] + b[2] + c[2]) / 3});
        }
        // 顶点 -> 相邻三角面
        List<List<Integer>> vTris = new ArrayList<>(g.v.size());
        for (int i = 0; i < g.v.size(); i++) vTris.add(new ArrayList<>());
        for (int f = 0; f < nT; f++) {
            int[] tri = g.faces.get(f);
            vTris.get(tri[0]).add(f);
            vTris.get(tri[1]).add(f);
            vTris.get(tri[2]).add(f);
        }
        // 每个原顶点 -> 其对偶多边形（按绕 v 的角度排序三角面中心）
        List<int[]> dualFaces = new ArrayList<>();
        for (int v = 0; v < g.v.size(); v++) {
            List<Integer> tris = vTris.get(v);
            if (tris.isEmpty()) continue;
            float[] nv = g.v.get(v);
            float[] ref = Math.abs(nv[0]) < 0.9f ? new float[]{1, 0, 0} : new float[]{0, 1, 0};
            float[] t1 = normalize(cross(nv, ref));
            float[] t2 = cross(nv, t1);
            // (三角面索引, 角度)
            double[][] ta = new double[tris.size()][2];
            for (int i = 0; i < tris.size(); i++) {
                int f = tris.get(i);
                float[] c = cen[f];
                ta[i][0] = f;
                ta[i][1] = Math.atan2(dot(c, t2), dot(c, t1));
            }
            Arrays.sort(ta, Comparator.comparingDouble(o -> o[1]));
            // 找到最大角度间隔，旋转使多边形起点落在间隔之后（避免 atan2 跨 ±π 错位）
            int n = ta.length;
            double maxGap = -1;
            int gapAt = 0;
            for (int i = 0; i < n; i++) {
                double a0 = ta[i][1];
                double a1 = ta[(i + 1) % n][1];
                double gap = a1 - a0;
                if (gap < 0) gap += 2 * Math.PI;
                if (gap > maxGap) {
                    maxGap = gap;
                    gapAt = i;
                }
            }
            int[] poly = new int[n];
            for (int i = 0; i < n; i++) poly[i] = (int) ta[(gapAt + 1 + i) % n][0];
            dualFaces.add(poly);
        }
        return new Polyhedron(cen, dualFaces.toArray(new int[0][]), buildEdges(dualFaces));
    }

    /**
     * 截角二十面体（足球多面体）：12 个正五边形 + 20 个正六边形，所有面都是正多边形。
     * 顶点和面表由正二十面体截角生成，已归一化到单位球。
     */
    public static Polyhedron truncatedIcosahedron() {
        float[][] V = new float[][]{
    {-0.201774f, 0.979432f, 0.000000f},
    {0.201774f, 0.979432f, 0.000000f},
    {-0.403548f, 0.854729f, 0.326477f},
    {-0.201774f, 0.730026f, 0.652955f},
    {-0.403548f, 0.854729f, -0.326477f},
    {-0.201774f, 0.730026f, -0.652955f},
    {-0.730026f, 0.652955f, -0.201774f},
    {-0.854729f, 0.326477f, -0.403548f},
    {-0.730026f, 0.652955f, 0.201774f},
    {-0.854729f, 0.326477f, 0.403548f},
    {0.403548f, 0.854729f, 0.326477f},
    {0.201774f, 0.730026f, 0.652955f},
    {0.403548f, 0.854729f, -0.326477f},
    {0.201774f, 0.730026f, -0.652955f},
    {0.730026f, 0.652955f, -0.201774f},
    {0.854729f, 0.326477f, -0.403548f},
    {0.730026f, 0.652955f, 0.201774f},
    {0.854729f, 0.326477f, 0.403548f},
    {-0.201774f, -0.979432f, 0.000000f},
    {0.201774f, -0.979432f, 0.000000f},
    {-0.403548f, -0.854729f, 0.326477f},
    {-0.201774f, -0.730026f, 0.652955f},
    {-0.403548f, -0.854729f, -0.326477f},
    {-0.201774f, -0.730026f, -0.652955f},
    {-0.730026f, -0.652955f, -0.201774f},
    {-0.854729f, -0.326477f, -0.403548f},
    {-0.730026f, -0.652955f, 0.201774f},
    {-0.854729f, -0.326477f, 0.403548f},
    {0.403548f, -0.854729f, 0.326477f},
    {0.201774f, -0.730026f, 0.652955f},
    {0.403548f, -0.854729f, -0.326477f},
    {0.201774f, -0.730026f, -0.652955f},
    {0.730026f, -0.652955f, -0.201774f},
    {0.854729f, -0.326477f, -0.403548f},
    {0.730026f, -0.652955f, 0.201774f},
    {0.854729f, -0.326477f, 0.403548f},
    {0.000000f, -0.201774f, 0.979432f},
    {0.000000f, 0.201774f, 0.979432f},
    {0.326477f, -0.403548f, 0.854729f},
    {0.652955f, -0.201774f, 0.730026f},
    {-0.326477f, -0.403548f, 0.854729f},
    {-0.652955f, -0.201774f, 0.730026f},
    {0.326477f, 0.403548f, 0.854729f},
    {0.652955f, 0.201774f, 0.730026f},
    {-0.326477f, 0.403548f, 0.854729f},
    {-0.652955f, 0.201774f, 0.730026f},
    {0.000000f, -0.201774f, -0.979432f},
    {0.000000f, 0.201774f, -0.979432f},
    {0.326477f, -0.403548f, -0.854729f},
    {0.652955f, -0.201774f, -0.730026f},
    {-0.326477f, -0.403548f, -0.854729f},
    {-0.652955f, -0.201774f, -0.730026f},
    {0.326477f, 0.403548f, -0.854729f},
    {0.652955f, 0.201774f, -0.730026f},
    {-0.326477f, 0.403548f, -0.854729f},
    {-0.652955f, 0.201774f, -0.730026f},
    {0.979432f, 0.000000f, -0.201774f},
    {0.979432f, 0.000000f, 0.201774f},
    {-0.979432f, 0.000000f, -0.201774f},
    {-0.979432f, 0.000000f, 0.201774f},
};
int[][] F = new int[][]{
    {8, 9, 45, 44, 3, 2},
    {2, 3, 11, 10, 1, 0},
    {0, 1, 12, 13, 5, 4},
    {4, 5, 54, 55, 7, 6},
    {6, 7, 58, 59, 9, 8},
    {10, 11, 42, 43, 17, 16},
    {44, 45, 41, 40, 36, 37},
    {59, 58, 25, 24, 26, 27},
    {55, 54, 47, 46, 50, 51},
    {13, 12, 14, 15, 53, 52},
    {34, 35, 39, 38, 29, 28},
    {28, 29, 21, 20, 18, 19},
    {19, 18, 22, 23, 31, 30},
    {30, 31, 48, 49, 33, 32},
    {32, 33, 56, 57, 35, 34},
    {38, 39, 43, 42, 37, 36},
    {20, 21, 40, 41, 27, 26},
    {23, 22, 24, 25, 51, 50},
    {49, 48, 46, 47, 52, 53},
    {57, 56, 15, 14, 16, 17},
    {2, 0, 4, 6, 8},
    {16, 14, 12, 1, 10},
    {22, 18, 20, 26, 24},
    {32, 34, 28, 19, 30},
    {29, 38, 36, 40, 21},
    {42, 11, 3, 44, 37},
    {48, 31, 23, 50, 46},
    {13, 52, 47, 54, 5},
    {15, 56, 33, 49, 53},
    {35, 57, 17, 43, 39},
    {55, 51, 25, 58, 7},
    {41, 45, 9, 59, 27},
};
        return new Polyhedron(V, F, buildEdges(java.util.Arrays.asList(F)));
    }

    // ============================ 内部：细分测地线球 ============================

    private static final class Geo {
        final List<float[]> v;
        final List<int[]> faces;

        Geo(List<float[]> v, List<int[]> faces) {
            this.v = v;
            this.faces = faces;
        }
    }

    private static Geo geodesic(int level) {
        float t = (float) ((1 + Math.sqrt(5)) / 2);
        float[][] base = {
                {-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0},
                {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
                {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}
        };
        List<float[]> verts = new ArrayList<>();
        for (float[] v : base) verts.add(normalize(v));

        int[][] baseFaces = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        List<int[]> faces = new ArrayList<>();
        for (int[] f : baseFaces) faces.add(f.clone());

        Map<Long, Integer> midCache = new HashMap<>();
        for (int s = 0; s < level; s++) {
            List<int[]> next = new ArrayList<>();
            for (int[] tri : faces) {
                int a = midpoint(tri[0], tri[1], verts, midCache);
                int b = midpoint(tri[1], tri[2], verts, midCache);
                int c = midpoint(tri[2], tri[0], verts, midCache);
                next.add(new int[]{tri[0], a, c});
                next.add(new int[]{tri[1], b, a});
                next.add(new int[]{tri[2], c, b});
                next.add(new int[]{a, b, c});
            }
            faces = next;
        }
        return new Geo(verts, faces);
    }

    private static int[][] buildEdges(List<int[]> faces) {
        Set<Long> seen = new TreeSet<>();
        List<int[]> edgeList = new ArrayList<>();
        for (int[] tri : faces) {
            int n = tri.length;
            for (int i = 0; i < n; i++) {
                addEdge(seen, edgeList, tri[i], tri[(i + 1) % n]);
            }
        }
        return edgeList.toArray(new int[0][]);
    }

    private static void addEdge(Set<Long> seen, List<int[]> edgeList, int i, int j) {
        long key = i < j ? ((long) i << 32) | j : ((long) j << 32) | i;
        if (seen.add(key)) {
            edgeList.add(new int[]{i, j});
        }
    }

    private static int midpoint(int i, int j, List<float[]> verts, Map<Long, Integer> cache) {
        long key = i < j ? ((long) i << 32) | j : ((long) j << 32) | i;
        Integer existing = cache.get(key);
        if (existing != null) return existing;
        float[] a = verts.get(i), b = verts.get(j);
        float[] mid = normalize(new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2});
        int idx = verts.size();
        verts.add(mid);
        cache.put(key, idx);
        return idx;
    }

    private static float[] normalize(float[] v) {
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }

    private static float[] cross(float[] a, float[] b) {
        return new float[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    // ============================ 不规则碎石多面体 ============================

    /**
     * 生成一个不规则碎石形状：从正二十面体出发，细分一次得到 80 个三角面，
     * 然后随机拉扯顶点径向距离，得到有棱有角的不规则多面体（不是球！）。
     * @param seed 随机种子，不同 seed → 不同形状
     * @param roughness 0..1，0=接近正二十面体，1=极度不规则
     */
    public static Polyhedron rock(long seed, float roughness) {
        float t = (float) ((1 + Math.sqrt(5)) / 2);
        float[][] base = {
            {-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0},
            {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
            {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}
        };
        List<float[]> verts = new ArrayList<>();
        for (float[] v : base) verts.add(normalize(v));
        int[][] baseFaces = {
            {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
            {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
            {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
            {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        List<int[]> faces = new ArrayList<>();
        for (int[] f : baseFaces) faces.add(f.clone());
        // 细分一次 → 80 三角面，每面都是小三角形，棱角分明
        Map<Long, Integer> midCache = new HashMap<>();
        List<int[]> subdiv = new ArrayList<>();
        for (int[] tri : faces) {
            int a = midpoint(tri[0], tri[1], verts, midCache);
            int b = midpoint(tri[1], tri[2], verts, midCache);
            int c = midpoint(tri[2], tri[0], verts, midCache);
            subdiv.add(new int[]{tri[0], a, c});
            subdiv.add(new int[]{tri[1], b, a});
            subdiv.add(new int[]{tri[2], c, b});
            subdiv.add(new int[]{a, b, c});
        }
        faces = subdiv;
        // 随机拉扯每个顶点的径向距离
        long rng = seed;
        for (int i = 0; i < verts.size(); i++) {
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            float r = ((int)(rng >>> 33)) / (float)(1L << 31); // 0..1
            float scale = 0.65f + r * 0.7f * roughness; // 0.65..1.35 at roughness=1
            scale = Math.max(0.5f, Math.min(1.5f, scale));
            float[] v = verts.get(i);
            float len = (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
            if (len < 1e-5f) len = 1;
            verts.set(i, new float[]{ v[0]/len * scale, v[1]/len * scale, v[2]/len * scale });
        }
        return new Polyhedron(verts.toArray(new float[0][]), faces.toArray(new int[0][]), buildEdges(faces));
    }
}
