package com.mss.polymech.client.gui.widget.planet;

/**
 * 太空维度专用的星球外观常量。
 *
 * <p>与 {@link PlanetVisual} 中给星系 UI / 工具链使用的常量分离：
 * UI 保持原来的基色不动；太空维度使用这里更亮、统一的一版，
 * 避免一边修过曝一边把太空星球压暗，也避免反过来影响星系 UI。</p>
 */
final class SpaceVisuals {

    static final PlanetVisual SUN = PlanetVisual.SUN;

    static final PlanetVisual MERCURY = PlanetVisual.ofFull(
            new float[]{0.55f, 0.51f, 0.47f}, null, null, 0f, 0f, 32f);
    static final PlanetVisual VENUS = PlanetVisual.ofFull(
            new float[]{0.82f, 0.73f, 0.51f},
            new float[]{0.80f, 0.71f, 0.48f}, null, 0f, 0f, 32f);
    static final PlanetVisual EARTH = PlanetVisual.ofFull(
            new float[]{0.30f, 0.55f, 0.90f},
            new float[]{0.25f, 0.55f, 1.00f}, null, 0f, 0.35f, 48f);
    static final PlanetVisual MOON = PlanetVisual.ofFull(
            new float[]{0.58f, 0.56f, 0.53f}, null, null, 0f, 0f, 32f);
    static final PlanetVisual MARS = PlanetVisual.ofFull(
            new float[]{0.72f, 0.38f, 0.21f},
            new float[]{0.68f, 0.38f, 0.23f}, null, 0f, 0f, 32f);
    static final PlanetVisual JUPITER = PlanetVisual.ofFull(
            new float[]{0.68f, 0.55f, 0.38f},
            new float[]{0.62f, 0.50f, 0.34f}, null, 0f, 0f, 32f);
    static final PlanetVisual SATURN = PlanetVisual.ofFull(
            new float[]{0.73f, 0.64f, 0.47f},
            new float[]{0.68f, 0.60f, 0.44f},
            new float[]{0.74f, 0.65f, 0.47f}, 0f, 0f, 32f);
    static final PlanetVisual TITAN = PlanetVisual.ofFull(
            new float[]{0.73f, 0.48f, 0.22f},
            new float[]{0.70f, 0.46f, 0.21f}, null, 0f, 0f, 32f);
    static final PlanetVisual URANUS = PlanetVisual.ofFull(
            new float[]{0.49f, 0.66f, 0.75f},
            new float[]{0.45f, 0.60f, 0.69f},
            new float[]{0.48f, 0.63f, 0.73f}, 0f, 0f, 32f);
    static final PlanetVisual NEPTUNE = PlanetVisual.ofFull(
            new float[]{0.32f, 0.50f, 0.81f},
            new float[]{0.30f, 0.46f, 0.74f},
            new float[]{0.29f, 0.48f, 0.79f}, 0f, 0f, 32f);
    static final PlanetVisual PLUTO = PlanetVisual.ofFull(
            new float[]{0.59f, 0.54f, 0.49f}, null, null, 0f, 0f, 32f);
    static final PlanetVisual CHARON = PlanetVisual.ofFull(
            new float[]{0.46f, 0.44f, 0.42f}, null, null, 0f, 0f, 32f);

    static final PlanetVisual IO = PlanetVisual.ofFull(
            new float[]{0.80f, 0.67f, 0.18f}, null, null, 0f, 0f, 32f);
    static final PlanetVisual EUROPA = PlanetVisual.ofFull(
            new float[]{0.76f, 0.73f, 0.67f}, null, null, 0f, 0.55f, 96f);
    static final PlanetVisual GANYMEDE = PlanetVisual.ofFull(
            new float[]{0.54f, 0.50f, 0.45f}, null, null, 0f, 0.15f, 32f);
    static final PlanetVisual CALLISTO = PlanetVisual.ofFull(
            new float[]{0.38f, 0.36f, 0.33f}, null, null, 0f, 0.12f, 24f);
    static final PlanetVisual ENCELADUS = PlanetVisual.ofFull(
            new float[]{0.92f, 0.95f, 0.98f}, null, null, 0f, 0.60f, 128f);
    static final PlanetVisual PHOBOS = PlanetVisual.ofFull(
            new float[]{0.54f, 0.51f, 0.46f}, null, null, 0f, 0f, 32f);
    static final PlanetVisual DEIMOS = PlanetVisual.ofFull(
            new float[]{0.48f, 0.45f, 0.42f}, null, null, 0f, 0f, 32f);

    static final PlanetVisual DEFAULT = PlanetVisual.ofFull(
            new float[]{0.55f, 0.55f, 0.55f}, null, null, 0f, 0f, 32f);

    private SpaceVisuals() {
    }
}
