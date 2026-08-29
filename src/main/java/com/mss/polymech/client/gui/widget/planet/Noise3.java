package com.mss.polymech.client.gui.widget.planet;

/**
 * 3D 噪声生成器（Value Noise + FBM）。
 * <p>
 * 用于程序化纹理生成（星球表面颜色、云层、光晕等）。
 * 从 SolarSystemView 内部类提取为独立顶层类，供 {@link PlanetColorProvider} 等外部使用。
 */
final class Noise3 {
    private final long seed;

    Noise3(long seed) {
        this.seed = seed;
    }

    private int hash(int x, int y, int z) {
        long h = seed ^ (x * 374761393L) ^ (y * 668265263L) ^ (z * 2654435761L);
        h = (h ^ (h >>> 13)) * 1274126177L;
        h = h ^ (h >>> 16);
        return (int) (h & 0x7fffffff);
    }

    private float val(int x, int y, int z) {
        return hash(x, y, z) / (float) 0x7fffffff;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    float noise(float x, float y, float z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        float xf = x - xi, yf = y - yi, zf = z - zi;
        float u = xf * xf * (3 - 2 * xf), v = yf * yf * (3 - 2 * yf), w = zf * zf * (3 - 2 * zf);
        float c000 = val(xi, yi, zi), c100 = val(xi + 1, yi, zi);
        float c010 = val(xi, yi + 1, zi), c110 = val(xi + 1, yi + 1, zi);
        float c001 = val(xi, yi, zi + 1), c101 = val(xi + 1, yi, zi + 1);
        float c011 = val(xi, yi + 1, zi + 1), c111 = val(xi + 1, yi + 1, zi + 1);
        float x00 = lerp(c000, c100, u), x10 = lerp(c010, c110, u);
        float x01 = lerp(c001, c101, u), x11 = lerp(c011, c111, u);
        return lerp(lerp(x00, x10, v), lerp(x01, x11, v), w);
    }

    float fbm(float x, float y, float z) {
        float s = 0, a = 0.5f, f = 1;
        for (int o = 0; o < 4; o++) {
            s += a * noise(x * f, y * f, z * f);
            f *= 2;
            a *= 0.5f;
        }
        return s;
    }
}
