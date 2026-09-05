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

    /** smoothstep：x 从 e0 到 e1 平滑过渡 0->1。 */
    static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /**
     * 类地星球表色引擎：气候带（纬度）x 湿度场 x 海拔分段 的组合表色。全程程序化，零贴图。
     *
     * <p>同一套逻辑，不同参数长出不同性格的类地星球：</p>
     * <ul>
     *   <li>纬度带 —— 热带/亚热带/温带/寒带/极地，每带一组「旱/润」色板；</li>
     *   <li>湿度场 —— 独立噪声，同纬度内分出沙漠与雨林（撒哈拉 vs 东南亚）；</li>
     *   <li>海拔分段 —— 海滩 / 低地 / 丘陵褐化 / 山地裸岩 / 雪线（随纬度和干旱度变化）；</li>
     *   <li>河谷绿带 —— 湿润区山地里沿脊线噪声分布的绿色谷地；</li>
     *   <li>浅海 —— 热带近岸青绿浅滩，衬托大陆轮廓。</li>
     * </ul>
     *
     * @param seed    生物群系布局种子：扰动湿度/斑块/河谷三个噪声场的采样偏移，
     *                每颗星球的大陆性格都不同；<b>0 = 地球经典布局</b>（不施加偏移，向后兼容）。
     * @param dryness 干旱度 0..1：0.5 = 地球；&gt;0.5 全球偏旱（沙漠世界），&lt;0.5 全球偏润（丛林/水世界）。
     * @param ice     冰量 0..1：0.5 = 地球（冰盖 |lat|&gt;0.85）；&gt;0.5 冰盖扩张、雪线压低（冰封世界），
     *                趋近 0 时无冰盖、只有最高峰有雪（炎热世界）。
     */
    static PlanetColorProvider terrestrial(long seed, float dryness, float ice) {
        // 种子 → 9 个噪声场采样偏移（LCG 展开；seed=0 时全 0，保证地球布局不变）
        float[] off = new float[9];
        if (seed != 0) {
            long z = seed;
            for (int i = 0; i < 9; i++) {
                z = z * 6364136223846793005L + 1442695040888963407L;
                off[i] = ((z >>> 33) & 0x3FFFL) * 0.0125f; // 0 .. ~51.2
            }
        }
        final float o0 = off[0], o1 = off[1], o2 = off[2], o3 = off[3], o4 = off[4],
                o5 = off[5], o6 = off[6], o7 = off[7], o8 = off[8];
        final float dryF = clamp(dryness, 0f, 1f);
        final float iceF = clamp(ice, 0f, 1f);
        // 冰盖起始纬度：ice=0.5 -> 0.85（地球）；ice=1 -> 0.60；ice=0 -> 1.10（>1 永不触发）
        final float capStart = 1.10f - iceF * 0.50f;
        final float capMat = capStart + 0.03f; // 材质 ICE 边界略宽于颜色边界（地球 = 0.88）
        return new PlanetColorProvider() {
            @Override
            public float[] compute(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                float r, g, b;
                if (height <= 0f) {
                    // ===== 海洋：深度渐变 + 热带浅滩 + 寒海偏绿 =====
                    float depth = clamp(-height * 4f, 0f, 1f);
                    r = 0.04f + 0.03f * depth;
                    g = 0.14f + 0.22f * depth;
                    b = 0.38f + 0.32f * depth;
                    // 热带近岸浅滩：青绿色（珊瑚浅滩），衬托大陆轮廓
                    float shallow = 1f - smoothstep(0.004f, 0.030f, -height);
                    float tropicSea = 1f - smoothstep(0.25f, 0.42f, lat);
                    float lagoon = shallow * tropicSea;
                    r = lerp(r, 0.14f, lagoon * 0.6f);
                    g = lerp(g, 0.50f, lagoon * 0.6f);
                    b = lerp(b, 0.50f, lagoon * 0.5f);
                    // 高纬寒海：略偏蓝绿
                    g += smoothstep(0.55f, 0.80f, lat) * 0.05f;
                } else {
                    // ===== 陆地 =====
                    // 三个互不相关的噪声场（各类偏移由种子决定）：
                    float moisture = clamp(noise.fbm(cx * 2.6f + 50.3f + o0, cy * 2.6f + 71.7f + o1, cz * 2.6f + 33.1f + o2) / 0.94f, 0f, 1f); // 湿度
                    float patch    = clamp(noise.fbm(cx * 5.5f + 99.7f + o3, cy * 5.5f + 83.3f + o4, cz * 5.5f + 41.9f + o5) / 0.94f, 0f, 1f); // 小斑块
                    float ridge    = 1f - Math.abs(clamp(noise.fbm(cx * 4.0f + 55.1f + o6, cy * 4.0f + 61.7f + o7, cz * 4.0f + 49.3f + o8) / 0.94f, 0f, 1f) * 2f - 1f); // 脊线（河谷用）

                    // 湿度：混入小斑块打散连续性，拉陡 S 曲线增强旱/润对比；dryness 整体偏移（0.5 = 地球基准）
                    float m = clamp(moisture + (patch - 0.5f) * 0.35f, 0f, 1f);
                    m = smoothstep(0.18f, 0.82f, m);
                    m = clamp(m + (0.5f - dryF) * 0.6f, 0f, 1f);

                    // --- 纬度气候带（smoothstep 过渡，权重和为 1，无硬边界） ---
                    float wTrop = 1f - smoothstep(0.24f, 0.34f, lat);
                    float wSub  = smoothstep(0.24f, 0.34f, lat) * (1f - smoothstep(0.40f, 0.50f, lat));
                    float wTemp = smoothstep(0.40f, 0.50f, lat) * (1f - smoothstep(0.60f, 0.70f, lat));
                    float wBor  = smoothstep(0.60f, 0.70f, lat) * (1f - smoothstep(0.76f, 0.84f, lat));
                    float wPol  = smoothstep(0.76f, 0.84f, lat);

                    // 每带一组「旱 -> 润」色板，按湿度插值、按带权重累加
                    float[] col = {0f, 0f, 0f};
                    accZone(col, wTrop, 0.72f, 0.62f, 0.38f,  0.09f, 0.30f, 0.09f, m); // 热带：沙黄 <-> 雨林深绿
                    accZone(col, wSub,  0.80f, 0.68f, 0.44f,  0.16f, 0.40f, 0.13f, m); // 亚热带：荒漠 <-> 常绿林
                    accZone(col, wTemp, 0.58f, 0.52f, 0.30f,  0.18f, 0.42f, 0.16f, m); // 温带：干草原 <-> 落叶林
                    accZone(col, wBor,  0.40f, 0.38f, 0.30f,  0.14f, 0.28f, 0.18f, m); // 寒带：苔原 <-> 针叶林
                    accZone(col, wPol,  0.56f, 0.57f, 0.56f,  0.50f, 0.53f, 0.51f, m); // 极地：灰白冻原

                    // --- 海拔分段 ---
                    // 海滩：浅色沙带，湿润区偏暗湿地色
                    float beach = 1f - smoothstep(0.005f, 0.015f, height);
                    col[0] = lerp(col[0], lerp(0.76f, 0.55f, m), beach * 0.7f);
                    col[1] = lerp(col[1], lerp(0.72f, 0.52f, m), beach * 0.7f);
                    col[2] = lerp(col[2], lerp(0.55f, 0.42f, m), beach * 0.7f);
                    // 丘陵：轻度褐化脱饱和
                    float hills = smoothstep(0.04f, 0.09f, height) * (1f - smoothstep(0.10f, 0.16f, height));
                    col[0] = lerp(col[0], 0.55f, hills * 0.35f);
                    col[1] = lerp(col[1], 0.48f, hills * 0.35f);
                    col[2] = lerp(col[2], 0.34f, hills * 0.35f);
                    // 山地裸岩：旱山偏红棕、湿山偏绿灰
                    float mtn = smoothstep(0.10f, 0.17f, height);
                    col[0] = lerp(col[0], lerp(0.50f, 0.38f, m), mtn * 0.85f);
                    col[1] = lerp(col[1], lerp(0.42f, 0.42f, m), mtn * 0.85f);
                    col[2] = lerp(col[2], lerp(0.36f, 0.36f, m), mtn * 0.85f);
                    // 雪线：低纬只有最高峰有雪；高纬压低雪线（pow 曲线中纬度保持高位）；冰量整体缩放
                    float latF = (float) Math.pow(clamp(lat / 0.85f, 0f, 1f), 2.6);
                    float snowline = (lerp(0.30f, 0.10f, latF) + (1f - m) * 0.05f) * (1.5f - iceF);
                    float snow = smoothstep(snowline, snowline + 0.04f, height);
                    // 河谷绿带：湿润区丘陵/山地里沿脊线分布的绿色谷地（雪线以上不出现）
                    float valley = smoothstep(0.80f, 0.95f, ridge) * m * (hills + mtn) * (1f - snow);
                    col[0] = lerp(col[0], 0.16f, valley * 0.7f);
                    col[1] = lerp(col[1], 0.38f, valley * 0.7f);
                    col[2] = lerp(col[2], 0.14f, valley * 0.7f);
                    // 雪盖
                    col[0] = lerp(col[0], 0.93f, snow);
                    col[1] = lerp(col[1], 0.95f, snow);
                    col[2] = lerp(col[2], 0.98f, snow);

                    r = col[0];
                    g = col[1];
                    b = col[2];
                }
                // 极地冰盖：capStart 以上出现（冰量决定范围），平滑过渡
                if (lat > capStart) {
                    float t2 = clamp((lat - capStart) / Math.max(1e-4f, 1f - capStart), 0f, 1f);
                    r += (0.94f - r) * t2;
                    g += (0.96f - g) * t2;
                    b += (0.99f - b) * t2;
                }
                return new float[]{clamp(r, 0f, 1f), clamp(g, 0f, 1f), clamp(b, 0f, 1f)};
            }

            /** 把一个气候带的「旱 -> 润」色板按湿度 m 插值后，按带权重 w 累加进 col。 */
            private void accZone(float[] col, float w,
                                 float dr, float dg, float db,
                                 float wr, float wg, float wb, float m) {
                if (w <= 0f) return;
                col[0] += w * lerp(dr, wr, m);
                col[1] += w * lerp(dg, wg, m);
                col[2] += w * lerp(db, wb, m);
            }

            @Override
            public SurfaceMaterial material(int fi, float cx, float cy, float cz, float lat, float height, Noise3 noise) {
                if (lat > capMat) return SurfaceMaterial.ICE;
                return height <= 0f ? SurfaceMaterial.OCEAN : SurfaceMaterial.LAND;
            }
        };
    }

    /** 地球：类地引擎的标准实例（种子 0、干旱 0.5、冰量 0.5），材质区分供地形压平和高光掩码使用。 */
    PlanetColorProvider EARTH = terrestrial(0L, 0.5f, 0.5f);


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
