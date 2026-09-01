package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;

/**
 * 星球表面高度场：预计算材质/颜色和 VBO 位移共用同一套函数，
 * 保证「陆地/海洋由高度决定」与渲染位移完全一致。
 *
 * <p>高度定义：rawHeight &gt; 0 = 陆地，&lt;= 0 = 海面以下。
 * radius() 会把海面以下部分钳到海平面 R，这样海洋表面是平整的，并且和陆地是同一张连续网格。</p>
 */
final class PlanetHeight {
    private final float heightScale;
    private final float freq;
    private final Noise3 noise;
    /** 有海洋的星球才把海面以下钳平；岩石星球保持真实山谷。 */
    boolean clampToSea = false;

    PlanetHeight(int pi, Polyhedron baseMesh, float heightScale) {
        this.heightScale = heightScale;
        this.freq = heightScale <= 0f ? 1f
                : baseMesh.faces.length >= 2000 ? 2.5f
                : baseMesh.faces.length >= 500 ? 1.8f : 1.2f;
        this.noise = new Noise3(0x5EED1234L + pi * 0x1234567L + 0x9E3779B9L);
    }

    /** 原始高度：约 -0.5..0.44，0 = 海平面。 */
    float rawHeight(float x, float y, float z) {
        if (heightScale <= 0f) return 0f;
        return noise.fbm(x * freq + 7.7f, y * freq + 13.3f, z * freq + 5.1f) - 0.5f;
    }

    boolean isLand(float x, float y, float z) {
        return rawHeight(x, y, z) > 0f;
    }

    /** 位移后的局部坐标（单位球方向 -> 实际位置），海面以下钳到海平面。 */
    float[] displaced(float x, float y, float z, float R) {
        float r = R * (1f + heightScale * rawHeight(x, y, z));
        if (clampToSea && r < R) r = R; // 海洋星球：海面以下钳平到海平面
        return new float[]{x * r, y * r, z * r};
    }
}
