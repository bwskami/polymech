package com.mss.polymech.client.gui.widget.planet;

/**
 * 类地星球共用的表色引擎。
 *
 * <p>从原 {@code PlanetColorProvider.terrestrial(...)} 抽出：气候带（纬度）× 湿度场 ×
 * 海拔分段的组合表色。所有类地星球数据都走同一套方法，因此亮度、细节密度和陆海边界一致；
 * 子类只覆写 {@link #adjustSurfaceColor(float[], int, float, float, float, float, float, Noise3)}
 * 做行星个性化微调（火星偏红、水星偏灰、金星偏奶油黄等）。</p>
 *
 * <p>无海洋星球（水星/火星/月球）会自动把负高度映射到低地色，不会再把“低于海平面”画成蓝色海洋。</p>
 */
abstract class TerrestrialSpacePlanet extends SpacePlanet {
    protected final long seed;
    protected final float dryness;
    protected final float ice;
    protected final boolean hasOcean;

    private final float[] offsets = new float[9];
    private final float capStart;
    private final float capMat;

    protected TerrestrialSpacePlanet(String id, long seed, float dryness, float ice, boolean hasOcean,
                                     PlanetVisual visual, float heightScale, float spaceRotationSpeed,
                                     int meshSubdivision, float atmosphereRatio) {
        super(id, visual, heightScale, spaceRotationSpeed, meshSubdivision, atmosphereRatio);
        this.seed = seed;
        this.dryness = clamp(dryness);
        this.ice = clamp(ice);
        this.hasOcean = hasOcean;

        // seed=0 时保持地球经典布局（偏移全 0）；非 0 时扰动土壤湿度/斑块/河谷三个噪声场。
        if (seed != 0L) {
            long z = seed;
            for (int i = 0; i < offsets.length; i++) {
                z = z * 6364136223846793005L + 1442695040888963407L;
                offsets[i] = ((z >>> 33) & 0x3FFFL) * 0.0125f;
            }
        }
        this.capStart = 1.10f - this.ice * 0.50f;
        this.capMat = capStart + 0.03f;
    }

    @Override
    public final float[] compute(int faceIndex, float cx, float cy, float cz,
                                 float latitude, float height, Noise3 noise) {
        float[] rgb = computeBiomes(faceIndex, cx, cy, cz, latitude, height, noise);
        adjustSurfaceColor(rgb, faceIndex, cx, cy, cz, latitude, height, noise);
        applyPolarCaps(rgb, latitude);
        clampColor(rgb);
        return rgb;
    }

    /** 行星个性微调钩子：默认不做任何事，父类结果即地球同款表色。 */
    protected void adjustSurfaceColor(float[] rgb, int faceIndex, float cx, float cy, float cz,
                                      float latitude, float height, Noise3 noise) {
    }

    /** 无海洋星球把 [-0.5, 0.5] 的噪声高度压缩到适合陆地分段的范围。 */
    protected float terrainHeight(float rawHeight) {
        return hasOcean ? rawHeight : (rawHeight + 0.5f) * 0.55f;
    }

    private float[] computeBiomes(int faceIndex, float cx, float cy, float cz,
                                  float latitude, float height, Noise3 noise) {
        float r, g, b;
        if (hasOcean && height <= 0f) {
            // ===== 海洋：深度渐变 + 热带浅滩 + 寒海偏绿 =====
            float depth = clamp(-height * 4f);
            r = 0.04f + 0.03f * depth;
            g = 0.14f + 0.22f * depth;
            b = 0.38f + 0.32f * depth;
            float shallow = 1f - smoothstep(0.004f, 0.030f, -height);
            float tropicSea = 1f - smoothstep(0.25f, 0.42f, latitude);
            float lagoon = shallow * tropicSea;
            r = lerp(r, 0.14f, lagoon * 0.6f);
            g = lerp(g, 0.50f, lagoon * 0.6f);
            b = lerp(b, 0.50f, lagoon * 0.5f);
            g += smoothstep(0.55f, 0.80f, latitude) * 0.05f;
        } else {
            // ===== 陆地 =====
            float h = terrainHeight(height);
            float o0 = offsets[0], o1 = offsets[1], o2 = offsets[2], o3 = offsets[3], o4 = offsets[4],
                    o5 = offsets[5], o6 = offsets[6], o7 = offsets[7], o8 = offsets[8];

            float moisture = clamp(noise.fbm(cx * 2.6f + 50.3f + o0, cy * 2.6f + 71.7f + o1, cz * 2.6f + 33.1f + o2) / 0.94f);
            float patch = clamp(noise.fbm(cx * 5.5f + 99.7f + o3, cy * 5.5f + 83.3f + o4, cz * 5.5f + 41.9f + o5) / 0.94f);
            float ridge = 1f - Math.abs(clamp(noise.fbm(cx * 4.0f + 55.1f + o6, cy * 4.0f + 61.7f + o7, cz * 4.0f + 49.3f + o8) / 0.94f) * 2f - 1f);

            float m = clamp(moisture + (patch - 0.5f) * 0.35f);
            m = smoothstep(0.18f, 0.82f, m);
            m = clamp(m + (0.5f - dryness) * 0.6f);

            float wTrop = 1f - smoothstep(0.24f, 0.34f, latitude);
            float wSub = smoothstep(0.24f, 0.34f, latitude) * (1f - smoothstep(0.40f, 0.50f, latitude));
            float wTemp = smoothstep(0.40f, 0.50f, latitude) * (1f - smoothstep(0.60f, 0.70f, latitude));
            float wBor = smoothstep(0.60f, 0.70f, latitude) * (1f - smoothstep(0.76f, 0.84f, latitude));
            float wPol = smoothstep(0.76f, 0.84f, latitude);

            float[] col = {0f, 0f, 0f};
            accZone(col, wTrop, 0.72f, 0.62f, 0.38f, 0.09f, 0.30f, 0.09f, m);
            accZone(col, wSub, 0.80f, 0.68f, 0.44f, 0.16f, 0.40f, 0.13f, m);
            accZone(col, wTemp, 0.58f, 0.52f, 0.30f, 0.18f, 0.42f, 0.16f, m);
            accZone(col, wBor, 0.40f, 0.38f, 0.30f, 0.14f, 0.28f, 0.18f, m);
            accZone(col, wPol, 0.56f, 0.57f, 0.56f, 0.50f, 0.53f, 0.51f, m);

            float beach = 1f - smoothstep(0.005f, 0.015f, h);
            col[0] = lerp(col[0], lerp(0.76f, 0.55f, m), beach * 0.7f);
            col[1] = lerp(col[1], lerp(0.72f, 0.52f, m), beach * 0.7f);
            col[2] = lerp(col[2], lerp(0.55f, 0.42f, m), beach * 0.7f);

            float hills = smoothstep(0.04f, 0.09f, h) * (1f - smoothstep(0.10f, 0.16f, h));
            col[0] = lerp(col[0], 0.55f, hills * 0.35f);
            col[1] = lerp(col[1], 0.48f, hills * 0.35f);
            col[2] = lerp(col[2], 0.34f, hills * 0.35f);

            float mtn = smoothstep(0.10f, 0.17f, h);
            col[0] = lerp(col[0], lerp(0.50f, 0.38f, m), mtn * 0.85f);
            col[1] = lerp(col[1], lerp(0.42f, 0.42f, m), mtn * 0.85f);
            col[2] = lerp(col[2], lerp(0.36f, 0.36f, m), mtn * 0.85f);

            float latF = (float) Math.pow(clamp(latitude / 0.85f), 2.6);
            float snowline = (lerp(0.30f, 0.10f, latF) + (1f - m) * 0.05f) * (1.5f - ice);
            float snow = smoothstep(snowline, snowline + 0.04f, h);
            float valley = smoothstep(0.80f, 0.95f, ridge) * m * (hills + mtn) * (1f - snow);
            col[0] = lerp(col[0], 0.16f, valley * 0.7f);
            col[1] = lerp(col[1], 0.38f, valley * 0.7f);
            col[2] = lerp(col[2], 0.14f, valley * 0.7f);

            col[0] = lerp(col[0], 0.93f, snow);
            col[1] = lerp(col[1], 0.95f, snow);
            col[2] = lerp(col[2], 0.98f, snow);

            r = col[0];
            g = col[1];
            b = col[2];
        }
        return new float[]{clamp(r), clamp(g), clamp(b)};
    }

    protected void applyPolarCaps(float[] rgb, float latitude) {
        if (latitude > capStart) {
            float t = clamp((latitude - capStart) / Math.max(1e-4f, 1f - capStart));
            rgb[0] += (0.94f - rgb[0]) * t;
            rgb[1] += (0.96f - rgb[1]) * t;
            rgb[2] += (0.99f - rgb[2]) * t;
        }
    }

    @Override
    public SurfaceMaterial material(int faceIndex, float cx, float cy, float cz,
                                    float latitude, float height, Noise3 noise) {
        if (hasOcean && height <= 0f) return SurfaceMaterial.OCEAN;
        if (latitude > capMat) return SurfaceMaterial.ICE;
        return hasOcean ? SurfaceMaterial.LAND : SurfaceMaterial.ROCK;
    }

    private static void accZone(float[] col, float w,
                                float dr, float dg, float db,
                                float wr, float wg, float wb, float m) {
        if (w <= 0f) return;
        col[0] += w * lerp(dr, wr, m);
        col[1] += w * lerp(dg, wg, m);
        col[2] += w * lerp(db, wb, m);
    }
}
