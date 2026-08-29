package com.mss.polymech.client.gui.widget.planet;

/**
 * 一个星球的视觉属性（颜色/外观）。
 * <p>
 * 从 SolarSystemView 的硬编码 {@code if (pi == X)} 中抽离出来，
 * 让每个星球自己携带视觉数据，SolarSystemView 只查询不判断。
 * <p>
 * 所有颜色为 RGB 0..1； {@code null} 表示使用默认值。
 */
public final class PlanetVisual {
    private final float[] baseColor;       // 地表主色 albedo tint (3)
    private final float[] atmosphereColor; // 大气散射色 (3)，null = 不渲染大气
    private final float[] ringColor;       // 星环色 (3)，null = 无星环
    private final float glowStrength;      // 自发光强度（太阳用），0 = 不自发光

    private PlanetVisual(float[] baseColor, float[] atmosphereColor, float[] ringColor, float glowStrength) {
        this.baseColor = baseColor;
        this.atmosphereColor = atmosphereColor;
        this.ringColor = ringColor;
        this.glowStrength = glowStrength;
    }

    public float[] baseColor() { return baseColor; }
    public float[] atmosphereColor() { return atmosphereColor; }
    public float[] ringColor() { return ringColor; }
    public float glowStrength() { return glowStrength; }
    public boolean hasAtmosphere() { return atmosphereColor != null; }
    public boolean hasRing() { return ringColor != null; }
    public boolean isGlowing() { return glowStrength > 0; }

    // ============ 预定义星球外观 ============

    /** 恒星专用视觉：表面有米粒组织+黑子纹理，大气层做发光描边 */
    public static final PlanetVisual STAR = new PlanetVisual(
            new float[]{1.0f, 0.85f, 0.45f},
            new float[]{1.0f, 0.75f, 0.30f}, null, 1.0f);
    public static final PlanetVisual SUN = STAR;
    public static final PlanetVisual MERCURY = new PlanetVisual(
            new float[]{0.65f, 0.60f, 0.55f}, null, null, 0);
    public static final PlanetVisual VENUS = new PlanetVisual(
            new float[]{0.90f, 0.80f, 0.55f},
            new float[]{0.90f, 0.80f, 0.50f}, null, 0);
    public static final PlanetVisual EARTH = new PlanetVisual(
            new float[]{0.30f, 0.55f, 0.90f},
            new float[]{0.25f, 0.55f, 1.00f}, null, 0);
    public static final PlanetVisual MOON = new PlanetVisual(
            new float[]{0.55f, 0.53f, 0.50f}, null, null, 0);
    public static final PlanetVisual MARS = new PlanetVisual(
            new float[]{0.85f, 0.45f, 0.25f},
            new float[]{0.80f, 0.50f, 0.35f}, null, 0);
    public static final PlanetVisual JUPITER = new PlanetVisual(
            new float[]{0.80f, 0.65f, 0.45f},
            new float[]{0.80f, 0.65f, 0.45f}, null, 0);
    public static final PlanetVisual SATURN = new PlanetVisual(
            new float[]{0.85f, 0.75f, 0.55f},
            new float[]{0.85f, 0.75f, 0.55f},
            new float[]{0.80f, 0.70f, 0.50f}, 0);
    public static final PlanetVisual TITAN = new PlanetVisual(
            new float[]{0.85f, 0.55f, 0.25f},
            new float[]{0.85f, 0.55f, 0.25f}, null, 0);
    public static final PlanetVisual URANUS = new PlanetVisual(
            new float[]{0.55f, 0.75f, 0.85f},
            new float[]{0.55f, 0.75f, 0.85f},
            new float[]{0.50f, 0.65f, 0.75f}, 0);
    public static final PlanetVisual NEPTUNE = new PlanetVisual(
            new float[]{0.35f, 0.55f, 0.90f},
            new float[]{0.35f, 0.55f, 0.90f},
            new float[]{0.30f, 0.50f, 0.85f}, 0);
    public static final PlanetVisual PLUTO = new PlanetVisual(
            new float[]{0.65f, 0.60f, 0.55f}, null, null, 0);
    public static final PlanetVisual CHARON = new PlanetVisual(
            new float[]{0.50f, 0.48f, 0.45f}, null, null, 0);

    // 卫星的通用外观
    public static final PlanetVisual IO = new PlanetVisual(
            new float[]{0.90f, 0.75f, 0.20f}, null, null, 0);
    public static final PlanetVisual EUROPA = new PlanetVisual(
            new float[]{0.85f, 0.82f, 0.75f}, null, null, 0);
    public static final PlanetVisual GANYMEDE = new PlanetVisual(
            new float[]{0.60f, 0.55f, 0.50f}, null, null, 0);
    public static final PlanetVisual CALLISTO = new PlanetVisual(
            new float[]{0.40f, 0.38f, 0.35f}, null, null, 0);
    public static final PlanetVisual ENCELADUS = new PlanetVisual(
            new float[]{0.92f, 0.95f, 0.98f}, null, null, 0);
    public static final PlanetVisual PHOBOS = new PlanetVisual(
            new float[]{0.45f, 0.42f, 0.38f}, null, null, 0);
    public static final PlanetVisual DEIMOS = new PlanetVisual(
            new float[]{0.40f, 0.38f, 0.35f}, null, null, 0);

    /** 默认灰色外观 */
    public static final PlanetVisual DEFAULT = new PlanetVisual(
            new float[]{0.50f, 0.50f, 0.50f}, null, null, 0);

    /** 构造自定义外观 */
    public static PlanetVisual of(float r, float g, float b) {
        return new PlanetVisual(new float[]{r, g, b}, null, null, 0);
    }

    /** 构造带大气的外观 */
    public static PlanetVisual withAtmosphere(float r, float g, float b,
                                              float ar, float ag, float ab) {
        return new PlanetVisual(new float[]{r, g, b}, new float[]{ar, ag, ab}, null, 0);
    }
}
