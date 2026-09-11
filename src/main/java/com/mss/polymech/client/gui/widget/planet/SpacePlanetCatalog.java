package com.mss.polymech.client.gui.widget.planet;

import java.util.List;
import java.util.Map;

/**
 * 真实太阳系天体的统一目录。
 *
 * <p>此前的做法是在 {@code PlanetRenderObjectFactory} / {@code SolarSystem} 里为每颗星球
 * 写一个 switch 分支；现在只有目录负责“哪颗星球用哪个类”，工厂只调用基类接口。
 * 要调整某颗行星，只需改对应子类覆写的方法，不会影响其他星球。</p>
 */
public final class SpacePlanetCatalog {

    public static final SpacePlanet SUN = new StarPlanet();
    public static final SpacePlanet MERCURY = new Mercury().tilt(0.0006f);
    public static final SpacePlanet VENUS = new Venus().tilt(3.09f);
    public static final SpacePlanet EARTH = new Earth().tilt(0.409f);
    public static final SpacePlanet MOON = new Moon().tilt(0.117f);
    public static final SpacePlanet MARS = new Mars().tilt(0.439f);
    public static final SpacePlanet PHOBOS = new RockSpacePlanet(
            "phobos", 101L, 0.62f, 0.59f, 0.55f, SpaceVisuals.PHOBOS, 0.025f, 0.0010f, 5, 1f);
    public static final SpacePlanet DEIMOS = new RockSpacePlanet(
            "deimos", 102L, 0.58f, 0.55f, 0.52f, SpaceVisuals.DEIMOS, 0.025f, 0.0009f, 5, 1f);
    public static final SpacePlanet JUPITER = new Jupiter().tilt(0.055f);
    public static final SpacePlanet IO = new RockSpacePlanet(
            "io", 111L, 0.88f, 0.72f, 0.22f, SpaceVisuals.IO, 0.025f, 0.0014f, 5, 1f);
    public static final SpacePlanet EUROPA = new IceSpacePlanet(
            "europa", 112L, true, SpaceVisuals.EUROPA, 0.020f, 0.0012f, 5, 1f);
    public static final SpacePlanet GANYMEDE = new RockSpacePlanet(
            "ganymede", 113L, 0.57f, 0.53f, 0.48f, SpaceVisuals.GANYMEDE, 0.025f, 0.0010f, 5, 1f);
    public static final SpacePlanet CALLISTO = new RockSpacePlanet(
            "callisto", 114L, 0.44f, 0.41f, 0.38f, SpaceVisuals.CALLISTO, 0.030f, 0.0009f, 5, 1f);
    public static final SpacePlanet SATURN = new Saturn().tilt(0.467f);
    public static final SpacePlanet TITAN = new RockSpacePlanet(
            "titan", 121L, 0.78f, 0.56f, 0.28f, SpaceVisuals.TITAN, 0.025f, 0.0010f, 5, 1.02f);
    public static final SpacePlanet ENCELADUS = new IceSpacePlanet(
            "enceladus", 122L, false, SpaceVisuals.ENCELADUS, 0.012f, 0.0010f, 5, 1f);
    public static final SpacePlanet URANUS = new Uranus().tilt(1.71f);
    public static final SpacePlanet NEPTUNE = new Neptune().tilt(0.49f);
    public static final SpacePlanet PLUTO = new RockSpacePlanet(
            "pluto", 141L, 0.62f, 0.57f, 0.52f, SpaceVisuals.PLUTO, 0.020f, -0.0008f, 5, 1f).tilt(2.09f);
    public static final SpacePlanet CHARON = new RockSpacePlanet(
            "charon", 142L, 0.50f, 0.48f, 0.45f, SpaceVisuals.CHARON, 0.020f, 0.0008f, 5, 1f);
    public static final SpacePlanet DEFAULT = new RockSpacePlanet(
            "default", 0xDEFACEDL, 0.55f, 0.55f, 0.55f, SpaceVisuals.DEFAULT, 0.020f, 0.0005f, 5, 1f);

    private static final Map<String, SpacePlanet> BY_ID = Map.ofEntries(
            Map.entry("sun", SUN),
            Map.entry("mercury", MERCURY),
            Map.entry("venus", VENUS),
            Map.entry("earth", EARTH),
            Map.entry("moon", MOON),
            Map.entry("mars", MARS),
            Map.entry("phobos", PHOBOS),
            Map.entry("deimos", DEIMOS),
            Map.entry("jupiter", JUPITER),
            Map.entry("io", IO),
            Map.entry("europa", EUROPA),
            Map.entry("ganymede", GANYMEDE),
            Map.entry("callisto", CALLISTO),
            Map.entry("saturn", SATURN),
            Map.entry("titan", TITAN),
            Map.entry("enceladus", ENCELADUS),
            Map.entry("uranus", URANUS),
            Map.entry("neptune", NEPTUNE),
            Map.entry("pluto", PLUTO),
            Map.entry("charon", CHARON)
    );

    private SpacePlanetCatalog() {
    }

    public static SpacePlanet byId(String id) {
        return BY_ID.getOrDefault(id, DEFAULT);
    }
}

// ==================== 具体天体（只覆写差异，不重复构建/表色流程） ====================

final class StarPlanet extends SpacePlanet {
    StarPlanet() {
        super("sun", SpaceVisuals.SUN, 0f, 0.0006f, 5, 1.025f);
    }

    @Override
    public float[] compute(int faceIndex, float cx, float cy, float cz,
                           float latitude, float height, Noise3 noise) {
        return PlanetColorProvider.STAR.compute(faceIndex, cx, cy, cz, latitude, height, noise);
    }
}

final class Earth extends TerrestrialSpacePlanet {
    Earth() {
        super("earth", 0L, 0.5f, 0.5f, true,
                SpaceVisuals.EARTH, 0.006f, 0.0020f, 6, 1.025f);
    }

    @Override
    protected void addLayers(float radius, List<PlanetLayer> out) {
        out.add(PlanetLayer.of(PlanetLayerType.CLOUD, radius * 1.007f).withRotationSpeed(0.018f));
        out.add(PlanetLayer.of(PlanetLayerType.CLOUD, radius * 1.013f));
        out.add(PlanetLayer.of(PlanetLayerType.CLOUD, radius * 1.019f).withRotationSpeed(-0.012f));
    }
}

final class Mercury extends TerrestrialSpacePlanet {
    Mercury() {
        super("mercury", 11L, 0.95f, 0.0f, false,
                SpaceVisuals.MERCURY, 0.015f, 0.0040f, 5, 1f);
    }

    @Override
    protected void adjustSurfaceColor(float[] rgb, int faceIndex, float cx, float cy, float cz,
                                      float latitude, float height, Noise3 noise) {
        float lum = luminance(rgb[0], rgb[1], rgb[2]);
        float v = 0.58f + 0.46f * lum;
        rgb[0] = v * 0.98f;
        rgb[1] = v * 0.95f;
        rgb[2] = v * 0.91f;
    }
}

final class Venus extends TerrestrialSpacePlanet {
    Venus() {
        super("venus", 22L, 0.88f, 0.0f, false,
                SpaceVisuals.VENUS, 0.015f, -0.0016f, 5, 1.040f);
    }

    @Override
    protected void addLayers(float radius, List<PlanetLayer> out) {
        // 金星厚云层：真实太空渲染不能只剩裸地表。
        out.add(PlanetLayer.of(PlanetLayerType.CLOUD, radius * 1.014f).withRotationSpeed(-0.036f));
        out.add(PlanetLayer.of(PlanetLayerType.CLOUD, radius * 1.024f).withRotationSpeed(-0.022f));
    }

    @Override
    protected void adjustSurfaceColor(float[] rgb, int faceIndex, float cx, float cy, float cz,
                                      float latitude, float height, Noise3 noise) {
        float lum = luminance(rgb[0], rgb[1], rgb[2]);
        float v = 0.78f + 0.34f * lum;
        rgb[0] = v * 0.96f;
        rgb[1] = v * 0.83f;
        rgb[2] = v * 0.55f;
    }
}

final class Moon extends TerrestrialSpacePlanet {
    Moon() {
        super("moon", 44L, 0.95f, 0.0f, false,
                SpaceVisuals.MOON, 0.025f, 0.0008f, 5, 1f);
    }

    @Override
    protected void adjustSurfaceColor(float[] rgb, int faceIndex, float cx, float cy, float cz,
                                      float latitude, float height, Noise3 noise) {
        float lum = luminance(rgb[0], rgb[1], rgb[2]);
        float v = 0.62f + 0.48f * lum;
        rgb[0] = v * 0.99f;
        rgb[1] = v * 0.97f;
        rgb[2] = v * 0.94f;
    }
}

final class Mars extends TerrestrialSpacePlanet {
    Mars() {
        super("mars", 55L, 0.88f, 0.35f, false,
                SpaceVisuals.MARS, 0.015f, 0.0019f, 5, 1.020f);
    }

    @Override
    protected void adjustSurfaceColor(float[] rgb, int faceIndex, float cx, float cy, float cz,
                                      float latitude, float height, Noise3 noise) {
        float lum = luminance(rgb[0], rgb[1], rgb[2]);
        float v = 0.62f + 0.56f * lum;
        rgb[0] = v * 0.96f;
        rgb[1] = v * 0.45f;
        rgb[2] = v * 0.24f;
    }
}

final class Jupiter extends GasGiantSpacePlanet {
    Jupiter() {
        super("jupiter", SpaceVisuals.JUPITER, 0.80f, 0.65f, 0.45f, 0.0032f, 1.018f);
    }

    @Override
    protected float bandFrequency() {
        return 26f;
    }

    @Override
    protected void adjustGasColor(float[] rgb, int faceIndex, float cx, float cy, float cz,
                                  float latitude, float height, Noise3 noise) {
        float spot = noise.fbm(cx * 3f + 1.5f, cy * 3f - 0.4f, cz * 3f);
        if (spot > 0.60f && latitude < 0.40f && latitude > 0.10f) {
            float sf = (spot - 0.60f) / 0.40f;
            rgb[0] += (0.92f - rgb[0]) * sf * 0.70f;
            rgb[1] += (0.34f - rgb[1]) * sf * 0.50f;
            rgb[2] += (0.18f - rgb[2]) * sf * 0.30f;
        }
    }
}

final class Saturn extends GasGiantSpacePlanet {
    Saturn() {
        super("saturn", SpaceVisuals.SATURN, 0.85f, 0.72f, 0.40f, 0.0030f, 1.015f);
    }

    @Override
    protected float bandFrequency() {
        return 20f;
    }

    @Override
    protected void addLayers(float radius, List<PlanetLayer> out) {
        out.add(PlanetLayer.of(PlanetLayerType.RING, radius * 1.60f));
    }
}

final class Uranus extends GasGiantSpacePlanet {
    Uranus() {
        super("uranus", SpaceVisuals.URANUS, 0.55f, 0.75f, 0.85f, -0.0024f, 1.018f);
    }

    @Override
    protected float bandFrequency() {
        return 16f;
    }

    @Override
    protected void addLayers(float radius, List<PlanetLayer> out) {
        out.add(PlanetLayer.of(PlanetLayerType.RING, radius * 1.4444f));
    }
}

final class Neptune extends GasGiantSpacePlanet {
    Neptune() {
        super("neptune", SpaceVisuals.NEPTUNE, 0.35f, 0.55f, 0.90f, 0.0026f, 1.018f);
    }

    @Override
    protected float bandFrequency() {
        return 16f;
    }

    @Override
    protected void addLayers(float radius, List<PlanetLayer> out) {
        out.add(PlanetLayer.of(PlanetLayerType.RING, radius * 1.5294f));
    }
}

/** 通用岩石星球：也继承 {@link SpacePlanet}，因此噪声/亮度基准与类地星球共用同一套工具。 */
class RockSpacePlanet extends SpacePlanet {
    protected final long seed;
    protected final float baseR;
    protected final float baseG;
    protected final float baseB;

    RockSpacePlanet(String id, long seed, float baseR, float baseG, float baseB,
                    PlanetVisual visual, float heightScale, float spaceRotationSpeed,
                    int meshSubdivision, float atmosphereRatio) {
        super(id, visual, heightScale, spaceRotationSpeed, meshSubdivision, atmosphereRatio);
        this.seed = seed;
        this.baseR = baseR;
        this.baseG = baseG;
        this.baseB = baseB;
    }

    @Override
    public float[] compute(int faceIndex, float cx, float cy, float cz,
                           float latitude, float height, Noise3 noise) {
        float n = clamp(noise.fbm(cx * 3f + 11f, cy * 3f + 27f, cz * 3f + 6f) / 0.94f);
        float v = 0.85f + 0.45f * n;
        return new float[]{clamp(baseR * v), clamp(baseG * v), clamp(baseB * v)};
    }
}

/** 冰质卫星：继承了岩石星球的亮度基准，只覆写冰面配色/裂缝。 */
class IceSpacePlanet extends RockSpacePlanet {
    private final boolean cracked;

    IceSpacePlanet(String id, long seed, boolean cracked, PlanetVisual visual,
                   float heightScale, float spaceRotationSpeed, int meshSubdivision, float atmosphereRatio) {
        super(id, seed, 0.90f, 0.92f, 0.96f, visual, heightScale, spaceRotationSpeed, meshSubdivision, atmosphereRatio);
        this.cracked = cracked;
    }

    @Override
    public float[] compute(int faceIndex, float cx, float cy, float cz,
                           float latitude, float height, Noise3 noise) {
        float n = clamp(noise.fbm(cx * 2.2f + 11.3f, cy * 2.2f + 27.1f, cz * 2.2f + 5.7f) / 0.94f);
        float v = 0.90f + 0.10f * n;
        float[] rgb = {0.88f * v, 0.91f * v, 0.96f * v};
        if (cracked) {
            float crack = noise.fbm(cx * 5f, cy * 5f, cz * 5f);
            if (crack > 0.60f) {
                float t = (crack - 0.60f) / 0.40f;
                rgb[0] -= 0.20f * t;
                rgb[1] -= 0.15f * t;
                rgb[2] -= 0.10f * t;
            }
        }
        clampColor(rgb);
        return rgb;
    }
}


