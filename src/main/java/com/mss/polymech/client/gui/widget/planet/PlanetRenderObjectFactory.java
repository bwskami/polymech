package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.techtree.Polyhedron;

import java.util.ArrayList;
import java.util.List;

/**
 * 从不同数据源构建 PlanetRenderObject。
 */
public final class PlanetRenderObjectFactory {

    private static final List<PlanetRenderObject> BODIES = createBodies();

    private PlanetRenderObjectFactory() {
    }

    public static List<PlanetRenderObject> bodies() {
        return BODIES;
    }

    public static PlanetRenderObject fromRealAstroData(RealAstroData data) {
        return fromRealAstroData(data, visualFor(data));
    }

    public static PlanetRenderObject fromRealAstroData(RealAstroData data, PlanetVisual visual) {
        double[] pos = data.realPositionAt(0);
        double atmosphereRadius = atmosphereRadius(data, visual);
        return new PlanetRenderObject(planetFor(data, visual), data.radiusMeters(), atmosphereRadius,
                castersFor(data), pos[0], pos[1], pos[2]);
    }

    /** 真实天体阴影投射者：目前只有地月互相投影（与 GUI ShadowModel 的父子规则一致）。 */
    private static List<RealAstroData> castersFor(RealAstroData data) {
        return switch (data.id()) {
            case "earth" -> List.of(RealAstroData.MOON);
            case "moon" -> List.of(RealAstroData.EARTH);
            default -> List.of();
        };
    }

    /** 视觉大气外半径：真实大气很薄，因此这里的比例比 GUI 星图更贴近地表。 */
    private static double atmosphereRadius(RealAstroData data, PlanetVisual visual) {
        if (!visual.hasAtmosphere()) return 0;
        double ratio = switch (data.id()) {
            case "sun" -> 1.025;
            case "venus" -> 1.040;
            case "earth" -> 1.025;
            case "mars" -> 1.020;
            case "jupiter" -> 1.018;
            case "saturn" -> 1.015;
            case "uranus" -> 1.018;
            case "neptune" -> 1.018;
            default -> 1.02;
        };
        return data.radiusMeters() * ratio;
    }

    public static PlanetRenderObject fromVisual(double radius, double posX, double posY, double posZ,
                                                PlanetVisual visual) {
        return new PlanetRenderObject(visual, radius, posX, posY, posZ);
    }

    private static List<PlanetRenderObject> createBodies() {
        List<PlanetRenderObject> list = new ArrayList<>();
        for (RealAstroData data : RealAstroData.BODIES) {
            list.add(fromRealAstroData(data));
        }
        return List.copyOf(list);
    }

    /** 为真实天体构建 GUI 同款表面 Planet（复用 PlanetColorProvider / PlanetHeight / PlanetVisual）。 */
    private static Planet planetFor(RealAstroData data, PlanetVisual visual) {
        Polyhedron base = switch (data.id()) {
            case "earth" -> Polyhedron.goldberg(6);   // 地球近景：40962 面，配合小起伏高密度噪声
            default -> Polyhedron.goldberg(5);        // 其余统一升到 10242 面
        };
        Planet.Builder builder = Planet.of(data.id(), base, rotationSpeed(data), layers(data))
                .visual(visual)
                .heightScale(heightScale(data));

        switch (data.id()) {
            case "sun" -> builder.colorProvider(PlanetColorProvider.STAR);
            case "mercury" -> builder.colorProvider(PlanetColorProvider.mercury(0.60f, 0.55f, 0.50f));
            case "venus" -> builder.colorProvider(PlanetColorProvider.venus(0.85f, 0.75f, 0.40f));
            case "earth" -> builder.colorProvider(PlanetColorProvider.EARTH);
            case "moon" -> builder.colorProvider(PlanetColorProvider.moon(0.55f, 0.53f, 0.50f));
            case "mars" -> builder.colorProvider(PlanetColorProvider.mars(0.80f, 0.35f, 0.15f));
            case "jupiter" -> builder.colorProvider(PlanetColorProvider.JUPITER);
            case "saturn" -> builder.colorProvider(PlanetColorProvider.SATURN);
            case "uranus" -> builder.colorProvider(PlanetColorProvider.gasGiant(0.55f, 0.75f, 0.85f));
            case "neptune" -> builder.colorProvider(PlanetColorProvider.gasGiant(0.35f, 0.55f, 0.90f));
            default -> builder.colorProvider(PlanetColorProvider.DEFAULT);
        }
        return builder.build();
    }

    /** 给地球/金星补云层（贴近地表），给土星/天王星/海王星补光环。 */
    private static PlanetLayer[] layers(RealAstroData data) {
        float R = (float) data.radiusMeters();
        List<PlanetLayer> layers = new ArrayList<>();
        layers.add(PlanetLayer.of(PlanetLayerType.BASE, R));
        switch (data.id()) {
            case "venus" -> {
                layers.add(PlanetLayer.of(PlanetLayerType.CLOUD, R * 1.014f).withRotationSpeed(-0.036f));
                layers.add(PlanetLayer.of(PlanetLayerType.CLOUD, R * 1.024f).withRotationSpeed(-0.022f));
            }
            case "earth" -> {
                layers.add(PlanetLayer.of(PlanetLayerType.CLOUD, R * 1.007f).withRotationSpeed(0.018f));
                layers.add(PlanetLayer.of(PlanetLayerType.CLOUD, R * 1.013f));
                layers.add(PlanetLayer.of(PlanetLayerType.CLOUD, R * 1.019f).withRotationSpeed(-0.012f));
            }
            case "saturn" -> layers.add(PlanetLayer.of(PlanetLayerType.RING, R * 1.60f));
            case "uranus" -> layers.add(PlanetLayer.of(PlanetLayerType.RING, R * 1.4444f));
            case "neptune" -> layers.add(PlanetLayer.of(PlanetLayerType.RING, R * 1.5294f));
            default -> { }
        }
        return layers.toArray(new PlanetLayer[0]);
    }

    private static float rotationSpeed(RealAstroData data) {
        return switch (data.id()) {
            case "sun" -> 0.006f;
            case "mercury" -> 0.040f;
            case "venus" -> 0.016f;
            case "earth" -> 0.020f;
            case "moon" -> 0.008f;
            case "mars" -> 0.019f;
            case "jupiter" -> 0.032f;
            case "saturn" -> 0.030f;
            case "uranus" -> 0.024f;
            case "neptune" -> 0.026f;
            default -> 0.005f;
        };
    }

    private static float heightScale(RealAstroData data) {
        // 太空维度使用真实比例：起伏不能超过云层/大气层。
        // GUI 星图仍保持夸张化处理（SolarSystem.createDefault 里的 heightScale 不变）。
        return switch (data.id()) {
            case "mercury" -> 0.015f;
            case "venus" -> 0.015f;
            case "earth" -> 0.006f;
            case "moon" -> 0.025f;
            case "mars" -> 0.015f;
            default -> 0f;
        };
    }

    private static PlanetVisual visualFor(RealAstroData body) {
        return switch (body.id()) {
            case "sun" -> PlanetVisual.SUN;
            case "mercury" -> PlanetVisual.MERCURY;
            case "venus" -> PlanetVisual.VENUS;
            case "earth" -> PlanetVisual.EARTH;
            case "moon" -> PlanetVisual.MOON;
            case "mars" -> PlanetVisual.MARS;
            case "jupiter" -> PlanetVisual.JUPITER;
            case "saturn" -> PlanetVisual.SATURN;
            case "uranus" -> PlanetVisual.URANUS;
            case "neptune" -> PlanetVisual.NEPTUNE;
            default -> PlanetVisual.MOON;
        };
    }
}
