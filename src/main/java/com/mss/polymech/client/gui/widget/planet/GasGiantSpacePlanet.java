package com.mss.polymech.client.gui.widget.planet;

/**
 * 气态巨行星共用的色带引擎。
 *
 * <p>所有气态巨行星共享同一套纬度色带 + 湍流噪声方法；子类只覆写
 * {@link #adjustGasColor(float[], int, float, float, float, float, float, Noise3)}
 * 加红斑/特殊风暴，不再各自复制一遍色带算法。</p>
 */
class GasGiantSpacePlanet extends SpacePlanet {
    protected final float baseR;
    protected final float baseG;
    protected final float baseB;

    GasGiantSpacePlanet(String id, PlanetVisual visual, float baseR, float baseG, float baseB,
                        float spaceRotationSpeed, float atmosphereRatio) {
        this(id, visual, baseR, baseG, baseB, spaceRotationSpeed, atmosphereRatio, 5);
    }

    GasGiantSpacePlanet(String id, PlanetVisual visual, float baseR, float baseG, float baseB,
                        float spaceRotationSpeed, float atmosphereRatio, int meshSubdivision) {
        super(id, visual, 0f, spaceRotationSpeed, meshSubdivision, atmosphereRatio);
        this.baseR = baseR;
        this.baseG = baseG;
        this.baseB = baseB;
    }

    @Override
    public float[] compute(int faceIndex, float cx, float cy, float cz,
                           float latitude, float height, Noise3 noise) {
        float band = (float) Math.sin(latitude * bandFrequency()) * 0.5f + 0.5f;
        float n = noise.fbm(cx * 3f + 7f, cy * 3f + 2f, cz * 3f) * 0.20f;
        float[] rgb = {
                baseR * (0.78f + 0.28f * band + n),
                baseG * (0.74f + 0.30f * band + n * 0.8f),
                baseB * (0.68f + 0.34f * band + n * 0.6f)
        };
        adjustGasColor(rgb, faceIndex, cx, cy, cz, latitude, height, noise);
        clampColor(rgb);
        return rgb;
    }

    /** 子类覆写加风暴/红斑；默认无个性调整。 */
    protected void adjustGasColor(float[] rgb, int faceIndex, float cx, float cy, float cz,
                                  float latitude, float height, Noise3 noise) {
    }

    protected float bandFrequency() {
        return 18f;
    }

    @Override
    public SurfaceMaterial material(int faceIndex, float cx, float cy, float cz,
                                    float latitude, float height, Noise3 noise) {
        return SurfaceMaterial.GAS;
    }
}
