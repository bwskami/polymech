package com.mss.polymech.client.gui.widget.planet;

/**
 * 星球表面颜色生成策略。
 * <p>
 * 每个 {@link Planet} 携带一个 {@code PlanetColorProvider}，
 * 在预计算阶段为每个三角面生成 albedo 颜色。
 * <p>
 * 替代原先 {@code SolarSystemView.precomputeColors()} 中的 switch-case 硬编码。
 */
@FunctionalInterface
public interface PlanetColorProvider {

    /**
     * 为一个三角面计算 albedo 颜色。
     *
     * @param faceIndex 面索引
     * @param cx        面中心 X（单位球方向）
     * @param cy        面中心 Y
     * @param cz        面中心 Z
     * @param latitude  纬度绝对值 |cy|
     * @param noise     噪声函数（用于纹理变化）
     * @return RGB albedo [r, g, b]，各分量 0..1
     */
    float[] compute(int faceIndex, float cx, float cy, float cz, float latitude, Noise3 noise);

    // ==================== 预定义策略 ====================

    /** 恒星：米粒组织 + 黑子 + 色球层色变 */
    PlanetColorProvider STAR = (fi, cx, cy, cz, lat, noise) -> {
        // 米粒组织：低频噪点模拟对流胞，颜色均匀过渡
        float granule = noise.fbm(cx * 4f + 3.7f, cy * 4f - 1.2f, cz * 4f + 5.3f);
        granule = clamp((granule - 0.3f) / 0.4f, 0, 1);
        // 活动区：低频大尺度亮度变化
        float activity = noise.fbm(cx * 2f + 10f, cy * 2f + 20f, cz * 2f);
        activity = clamp((activity - 0.1f) / 0.8f, 0, 1);
        // 黑子
        float spot = noise.fbm(cx * 5f - 5f, cy * 5f + 3f, cz * 5f + 1f);
        boolean isSpot = spot > 0.78f && activity < 0.35f;
        if (isSpot) {
            float sf = (spot - 0.78f) / 0.22f;
            return new float[]{0.60f + 0.10f * sf, 0.30f + 0.08f * sf, 0.12f + 0.05f * sf};
        } else {
            float brightness = 0.85f + 0.15f * granule + 0.05f * (activity - 0.5f);
            return new float[]{
                    Math.min(1f, 0.95f * brightness),
                    Math.min(1f, 0.72f * brightness),
                    Math.min(1f, 0.28f * brightness)
            };
        }
    };

    /** 地球：海洋 + 大陆 + 极冰 */
    PlanetColorProvider EARTH = (fi, cx, cy, cz, lat, noise) -> {
        float n = noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f);
        n = clamp(n / 0.94f, 0, 1);
        float r, g, b;
        if (n < 0.5f) {
            float t = n / 0.5f;
            r = 0.05f + 0.04f * t;
            g = 0.16f + 0.30f * t;
            b = 0.40f + 0.40f * t;
        } else {
            float t = (n - 0.5f) / 0.5f;
            r = 0.24f + 0.42f * t;
            g = 0.45f - 0.10f * t;
            b = 0.18f - 0.10f * t;
        }
        if (lat > 0.80f) {
            float t2 = (lat - 0.80f) / 0.20f;
            r = r + (1 - r) * t2;
            g = g + (1 - g) * t2;
            b = b + (1 - b) * t2;
        }
        return new float[]{r, g, b};
    };

    /** 木星：色带 + 大红斑 */
    PlanetColorProvider JUPITER = (fi, cx, cy, cz, lat, noise) -> {
        float band = (float) Math.sin(lat * 28f) * 0.5f + 0.5f;
        float storm = noise.fbm(cx * 5f, cy * 8f, cz * 5f) * 0.3f;
        float r = 0.80f * (0.70f + 0.30f * band + storm);
        float g = 0.65f * (0.65f + 0.35f * band);
        float b = 0.45f * (0.55f + 0.45f * band);
        float spot = noise.fbm(cx * 3f + 1.5f, cy * 3f - 0.4f, cz * 3f);
        if (spot > 0.6f && lat < 0.4f && lat > 0.1f) {
            float sf = (spot - 0.6f) / 0.4f;
            r = r + (0.85f - r) * sf * 0.7f;
            g = g + (0.30f - g) * sf * 0.5f;
            b = b + (0.15f - b) * sf * 0.3f;
        }
        return new float[]{r, g, b};
    };

    /** 土星：柔和色带 */
    PlanetColorProvider SATURN = (fi, cx, cy, cz, lat, noise) -> {
        float band = (float) Math.sin(lat * 20f) * 0.5f + 0.5f;
        float n = noise.fbm(cx * 3f + 7f, cy * 3f + 2f, cz * 3f) * 0.2f;
        return new float[]{
                0.85f * (0.70f + 0.30f * band + n),
                0.72f * (0.65f + 0.35f * band),
                0.40f * (0.55f + 0.45f * band)
        };
    };

    /** 气态巨行星通用色带（天王星/海王星用） */
    static PlanetColorProvider gasGiant(float baseR, float baseG, float baseB) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float band = (float) Math.sin(lat * 16f) * 0.5f + 0.5f;
            float n = noise.fbm(cx * 3f, cy * 3f, cz * 3f) * 0.15f;
            return new float[]{
                    baseR * (0.75f + 0.25f * band + n),
                    baseG * (0.70f + 0.30f * band),
                    baseB * (0.65f + 0.35f * band)
            };
        };
    }

    /** 通用岩石星球：噪点着色 */
    static PlanetColorProvider rock(float baseR, float baseG, float baseB) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float n = noise.fbm(cx * 3f + 11f, cy * 3f + 27f, cz * 3f + 6f);
            n = clamp(n / 0.94f, 0, 1);
            float variation = 0.70f + 0.30f * n;
            return new float[]{baseR * variation, baseG * variation, baseB * variation};
        };
    }


    /** 水星：灰褐色 + 撞击坑暗斑 */
    static PlanetColorProvider mercury(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
            float dark = noise.fbm(cx * 4f, cy * 4f, cz * 4f);
            float v = (0.92f - dark * 0.18f) * (0.70f + n * 0.55f);
            return new float[]{r * v, g * v, b * v};
        };
    }

    /** 金星：奶油黄云层旋涡 */
    static PlanetColorProvider venus(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float swirl = noise.fbm(cx * 3f + 7f, cy * 3f + 3f, cz * 3f + 9f);
            return new float[]{r * (0.80f + 0.30f * swirl), g * (0.80f + 0.25f * swirl), b * (0.75f + 0.20f * swirl)};
        };
    }

    /** 月球：灰色 + 陨石坑暗色 */
    static PlanetColorProvider moon(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
            float dark = n * n;
            return new float[]{r * (1f - dark * 0.30f), g * (1f - dark * 0.30f), b * (1f - dark * 0.30f)};
        };
    }

    /** 火星：红橙色 + 暗区 + 极冠 */
    static PlanetColorProvider mars(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
            float dark = noise.fbm(cx * 3f, cy * 3f, cz * 3f);
            float rr = r * (0.75f + n * 0.25f - dark * 0.20f);
            float gg = g * (0.60f + n * 0.20f - dark * 0.15f);
            float bb = b * (0.50f + n * 0.20f - dark * 0.10f);
            if (lat > 0.85f) { float t2 = (lat - 0.85f) / 0.15f; rr += (1 - rr) * t2; gg += (1 - gg) * t2; bb += (1 - bb) * t2; }
            return new float[]{rr, gg, bb};
        };
    }

    /** 木卫一：硫磺黄橙 */
    static PlanetColorProvider io(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float patch = noise.fbm(cx * 3f, cy * 3f, cz * 3f);
            return new float[]{0.90f - patch * 0.20f, 0.70f + patch * 0.10f, 0.20f + patch * 0.10f};
        };
    }

    /** 木卫二：冰白 + 棕色裂缝 */
    static PlanetColorProvider europa(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
            float rr = 0.85f + 0.10f * n, gg = 0.88f + 0.08f * n, bb = 0.92f;
            float crack = noise.fbm(cx * 5f, cy * 5f, cz * 5f);
            if (crack > 0.60f) { float t2 = (crack - 0.60f) / 0.40f; rr -= 0.20f * t2; gg -= 0.15f * t2; bb -= 0.10f * t2; }
            return new float[]{rr, gg, bb};
        };
    }

    /** 土卫二：亮白冰面 */
    static PlanetColorProvider enceladus() {
        return (fi, cx, cy, cz, lat, noise) -> new float[]{0.90f, 0.90f, 0.92f};
    }

    /** 冥王星：棕褐色 + 心形亮区 */
    static PlanetColorProvider pluto(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, noise) -> {
            float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
            float rr = r * (0.80f + n * 0.20f), gg = g * (0.80f + n * 0.20f), bb = b * (0.80f + n * 0.20f);
            float heart = noise.fbm(cx * 2f + 1f, cy * 2f - 0.3f, cz * 2f);
            if (heart > 0.62f) { float t2 = (heart - 0.62f) / 0.38f; rr += (0.95f - rr) * t2; gg += (0.92f - gg) * t2; bb += (0.85f - bb) * t2; }
            return new float[]{rr, gg, bb};
        };
    }

    /** 通用岩石/冰体：噪点着色（保留 tint 基色） */
    static PlanetColorProvider tintedRock(float baseR, float baseG, float baseB) {
        return rock(baseR, baseG, baseB);
    }

    /** 通用默认：灰色噪点 */
    PlanetColorProvider DEFAULT = rock(0.50f, 0.50f, 0.50f);

    // ==================== 工具方法 ====================

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
