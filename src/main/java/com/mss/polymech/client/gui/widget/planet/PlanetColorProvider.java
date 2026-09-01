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
    float[] compute(int faceIndex, float cx, float cy, float cz, float latitude, float height, Noise3 noise);

    /** 面片材质类型。默认 ROCK；需要海洋/冰/气态等特殊处理的预设重写此方法。 */
    default SurfaceMaterial material(int faceIndex, float cx, float cy, float cz, float latitude, float height, Noise3 noise) {
        return SurfaceMaterial.ROCK;
    }

    // ==================== 预定义策略 ====================

    /** 恒星：温度斑块（灰度温度图 -> 多段色带渐变映射），无黑子。 */
    PlanetColorProvider STAR = star(
            new float[]{1.00f, 0.30f, 0.05f},  // 冷：红橙
            new float[]{1.00f, 0.45f, 0.08f},  // 橙
            new float[]{1.00f, 0.70f, 0.30f},  // 橙黄
            new float[]{1.00f, 0.90f, 0.55f},  // 金黄
            new float[]{1.00f, 0.97f, 0.78f}); // 白热金

    /** 恒星表面：先生成灰度温度图（越黑越冷），再套多段渐变映射。以后换色只需换色带数组。 */
    static PlanetColorProvider star(float[]... gradient) {
        return (fi, cx, cy, cz, lat, height, noise) -> {
            float t = starTemperature(cx, cy, cz, noise);
            return sampleGradient(gradient, t);
        };
    }

    /** 在色带数组上做线性插值。t=0 取第一段，t=1 取最后一段。 */
    static float[] sampleGradient(float[][] gradient, float t) {
        int n = gradient.length;
        if (n == 1) return gradient[0];
        float pos = t * (n - 1);
        int i = Math.min((int) pos, n - 2);
        float f = pos - i;
        float[] a = gradient[i], b = gradient[i + 1];
        return new float[]{
                clamp(lerp(a[0], b[0], f), 0f, 1f),
                clamp(lerp(a[1], b[1], f), 0f, 1f),
                clamp(lerp(a[2], b[2], f), 0f, 1f),
        };
    }

    /** 恒星表面灰度温度图：0=冷（黑），1=热（白）。噪点更小更密。 */
    static float starTemperature(float cx, float cy, float cz, Noise3 noise) {
        // 小尺度密集对流胞
        float cell = noise.fbm(cx * 9f + 1.3f, cy * 9f + 5.7f, cz * 9f + 9.1f);
        // 大尺度温度带
        float zone = noise.fbm(cx * 3.5f + 17.1f, cy * 3.5f + 31.7f, cz * 3.5f + 9.3f);
        float t = cell * 0.70f + zone * 0.30f;
        // 整体偏热：只有噪声最低的一小部分进入冷端
        t = clamp((t - 0.10f) / 0.60f, 0f, 1f);
        t = (float) Math.pow(t, 0.85); // 低温区压缩，中高温区更广
        return clamp(t, 0f, 1f);
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 地球：海洋 + 大陆 + 极冰（材质明确区分，供地形压平和高光掩码使用）。 */
    PlanetColorProvider EARTH = earth();

    static PlanetColorProvider earth() {
        return new PlanetColorProvider() {
            @Override
            public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                float r, g, b;
                if (height <= 0f) {
                    // 海面以下：海洋蓝，深度越深越暗
                    float depth = clamp(-height * 4f, 0f, 1f);
                    r = 0.04f + 0.03f * depth;
                    g = 0.14f + 0.22f * depth;
                    b = 0.38f + 0.32f * depth;
                } else {
                    // 陆地：由海拔和噪声共同决定绿/棕
                    float n = noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f);
                    n = clamp(n / 0.94f, 0f, 1f);
                    float t = clamp(height * 5f, 0f, 1f);
                    r = 0.22f + 0.35f * t + 0.10f * n;
                    g = 0.45f - 0.12f * t + 0.08f * n;
                    b = 0.18f - 0.08f * t + 0.04f * n;
                }
                // 极地冰盖：纬度 0.85 以上才出现，平滑过渡
                if (lat > 0.85f) {
                    float t2 = (lat - 0.85f) / 0.15f;
                    r += (0.94f - r) * t2;
                    g += (0.96f - g) * t2;
                    b += (0.99f - b) * t2;
                }
                return new float[]{clamp(r, 0f, 1f), clamp(g, 0f, 1f), clamp(b, 0f, 1f)};
            }

            @Override
            public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                if (lat > 0.88f) return SurfaceMaterial.ICE;
                return height <= 0f ? SurfaceMaterial.OCEAN : SurfaceMaterial.LAND;
            }
        };
    }

    /** 木星：色带 + 大红斑（GAS 材质）。 */
    PlanetColorProvider JUPITER = new PlanetColorProvider() {
        @Override
        public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
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
        }

        @Override
        public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
            return SurfaceMaterial.GAS;
        }
    };

    /** 土星：柔和色带（GAS 材质）。 */
    PlanetColorProvider SATURN = new PlanetColorProvider() {
        @Override
        public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
            float band = (float) Math.sin(lat * 20f) * 0.5f + 0.5f;
            float n = noise.fbm(cx * 3f + 7f, cy * 3f + 2f, cz * 3f) * 0.2f;
            return new float[]{
                    0.85f * (0.70f + 0.30f * band + n),
                    0.72f * (0.65f + 0.35f * band),
                    0.40f * (0.55f + 0.45f * band)
            };
        }

        @Override
        public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
            return SurfaceMaterial.GAS;
        }
    };

    /** 气态巨行星通用色带（天王星/海王星用，GAS 材质）。 */
    static PlanetColorProvider gasGiant(float baseR, float baseG, float baseB) {
        return new PlanetColorProvider() {
            @Override
            public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                float band = (float) Math.sin(lat * 16f) * 0.5f + 0.5f;
                float n = noise.fbm(cx * 3f, cy * 3f, cz * 3f) * 0.15f;
                return new float[]{
                        baseR * (0.75f + 0.25f * band + n),
                        baseG * (0.70f + 0.30f * band),
                        baseB * (0.65f + 0.35f * band)
                };
            }

            @Override
            public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                return SurfaceMaterial.GAS;
            }
        };
    }

    /** 通用岩石星球：噪点着色 */
    static PlanetColorProvider rock(float baseR, float baseG, float baseB) {
        return (fi, cx, cy, cz, lat, height, noise) -> {
            float n = noise.fbm(cx * 3f + 11f, cy * 3f + 27f, cz * 3f + 6f);
            n = clamp(n / 0.94f, 0, 1);
            float variation = 0.70f + 0.30f * n;
            return new float[]{baseR * variation, baseG * variation, baseB * variation};
        };
    }


    /** 水星：灰褐色 + 撞击坑暗斑 */
    static PlanetColorProvider mercury(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, height, noise) -> {
            float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
            float dark = noise.fbm(cx * 4f, cy * 4f, cz * 4f);
            float v = (0.92f - dark * 0.18f) * (0.70f + n * 0.55f);
            return new float[]{r * v, g * v, b * v};
        };
    }

    /** 金星：奶油黄云层旋涡 */
    static PlanetColorProvider venus(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, height, noise) -> {
            float swirl = noise.fbm(cx * 3f + 7f, cy * 3f + 3f, cz * 3f + 9f);
            return new float[]{r * (0.80f + 0.30f * swirl), g * (0.80f + 0.25f * swirl), b * (0.75f + 0.20f * swirl)};
        };
    }

    /** 月球：灰色 + 陨石坑暗色 */
    static PlanetColorProvider moon(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, height, noise) -> {
            float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
            float dark = n * n;
            return new float[]{r * (1f - dark * 0.30f), g * (1f - dark * 0.30f), b * (1f - dark * 0.30f)};
        };
    }

    /** 火星：红橙色 + 暗区 + 极冠（极冠为 ICE 材质）。 */
    static PlanetColorProvider mars(float r, float g, float b) {
        return new PlanetColorProvider() {
            @Override
            public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
                float dark = noise.fbm(cx * 3f, cy * 3f, cz * 3f);
                float rr = r * (0.75f + n * 0.25f - dark * 0.20f);
                float gg = g * (0.60f + n * 0.20f - dark * 0.15f);
                float bb = b * (0.50f + n * 0.20f - dark * 0.10f);
                if (lat > 0.85f) { float t2 = (lat - 0.85f) / 0.15f; rr += (1 - rr) * t2; gg += (1 - gg) * t2; bb += (1 - bb) * t2; }
                return new float[]{rr, gg, bb};
            }

            @Override
            public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                return lat > 0.85f ? SurfaceMaterial.ICE : SurfaceMaterial.ROCK;
            }
        };
    }

    /** 木卫一：硫磺黄橙 */
    static PlanetColorProvider io(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, height, noise) -> {
            float patch = noise.fbm(cx * 3f, cy * 3f, cz * 3f);
            return new float[]{0.90f - patch * 0.20f, 0.70f + patch * 0.10f, 0.20f + patch * 0.10f};
        };
    }

    /** 木卫二：冰白 + 棕色裂缝（ICE 材质）。 */
    static PlanetColorProvider europa(float r, float g, float b) {
        return new PlanetColorProvider() {
            @Override
            public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f, 0, 1);
                float rr = 0.85f + 0.10f * n, gg = 0.88f + 0.08f * n, bb = 0.92f;
                float crack = noise.fbm(cx * 5f, cy * 5f, cz * 5f);
                if (crack > 0.60f) { float t2 = (crack - 0.60f) / 0.40f; rr -= 0.20f * t2; gg -= 0.15f * t2; bb -= 0.10f * t2; }
                return new float[]{rr, gg, bb};
            }

            @Override
            public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                return SurfaceMaterial.ICE;
            }
        };
    }

    /** 土卫二：亮白冰面（ICE 材质）。 */
    static PlanetColorProvider enceladus() {
        return new PlanetColorProvider() {
            @Override
            public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                return new float[]{0.90f, 0.90f, 0.92f};
            }

            @Override
            public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                return SurfaceMaterial.ICE;
            }
        };
    }

    /** 冥王星：棕褐色 + 心形亮区 */
    static PlanetColorProvider pluto(float r, float g, float b) {
        return (fi, cx, cy, cz, lat, height, noise) -> {
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
