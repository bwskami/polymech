package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;

import java.util.ArrayList;
import java.util.List;

/**
 * 太空星球渲染基类 —— 所有天体共享同一套 Planet 构建、图层顺序、表色工具和亮度基准。
 *
 * <p>设计目的：之前每种星球各写一套颜色/视觉方法，容易一边修过曝、一边把别的星球压暗，
 * 最后每颗星球看起来“不是一个渲染器画的”。现在把地球的通用能力抽到这里：</p>
 * <ul>
 *   <li>{@link #createPlanet(Polyhedron, float)} 统一构建 {@link Planet}；</li>
 *   <li>{@link #layers(float)} 统一 BASE 层与云/环追加顺序；</li>
 *   <li>表色工具 {@link #clampColor(float[])} 等供子类共用；</li>
 *   <li>具体星球只继承并覆写 {@code adjustSurfaceColor(...)} / {@code addLayers(...)} 等钩子。</li>
 * </ul>
 */
public abstract class SpacePlanet implements PlanetColorProvider {
    protected final String id;
    protected final PlanetVisual visual;
    protected final float heightScale;
    protected final float spaceRotationSpeed;
    protected final int meshSubdivision;
    protected final float atmosphereRatio;
    /** 自转轴相对黄道面法线的倾角（弧度），0 = 垂直黄道面。 */
    private float axialTilt;

    protected SpacePlanet(String id, PlanetVisual visual, float heightScale,
                          float spaceRotationSpeed, int meshSubdivision, float atmosphereRatio) {
        this.id = id;
        this.visual = visual;
        this.heightScale = heightScale;
        this.spaceRotationSpeed = spaceRotationSpeed;
        this.meshSubdivision = meshSubdivision;
        this.atmosphereRatio = atmosphereRatio;
    }

    public final String id() {
        return id;
    }

    public final PlanetVisual visual() {
        return visual;
    }

    public final float heightScale() {
        return heightScale;
    }

    public final float spaceRotationSpeed() {
        return spaceRotationSpeed;
    }

    public final int meshSubdivision() {
        return meshSubdivision;
    }

    /** 视觉大气外半径 / 行星半径；<= 1 表示没有大气。 */
    public final float atmosphereRatio() {
        return atmosphereRatio;
    }

    /** 自转轴倾角（弧度），与 UI 的 Planet.axialTilt 同一约定。 */
    public final float axialTilt() {
        return axialTilt;
    }

    /** 在目录里链式设置自转轴倾角；不属于渲染热路径。 */
    public final SpacePlanet tilt(float axialTilt) {
        this.axialTilt = axialTilt;
        return this;
    }

    /** 用同一个模板构建星球；子类不要在各自的工厂里重复写 Builder。 */
    public final Planet createPlanet(Polyhedron baseMesh, float radius) {
        return Planet.of(id, baseMesh, spaceRotationSpeed, layers(radius))
                .visual(visual)
                .colorProvider(this)
                .heightScale(heightScale)
                .tilt(axialTilt)
                .build();
    }

    /** 默认只有 BASE 层；有云/环的星球覆写 {@link #addLayers(float, List)}。 */
    public final PlanetLayer[] layers(float radius) {
        List<PlanetLayer> layers = new ArrayList<>();
        layers.add(PlanetLayer.of(PlanetLayerType.BASE, radius));
        addLayers(radius, layers);
        return layers.toArray(new PlanetLayer[0]);
    }

    /** 追加云层/光环的钩子。默认无追加。 */
    protected void addLayers(float radius, List<PlanetLayer> out) {
    }

    @Override
    public SurfaceMaterial material(int faceIndex, float cx, float cy, float cz,
                                    float latitude, float height, Noise3 noise) {
        return SurfaceMaterial.ROCK;
    }

    // ==================== 共用表色工具 ====================

    protected static float clamp(float v) {
        return clamp(v, 0f, 1f);
    }

    protected static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    protected static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    protected static float smoothstep(float e0, float e1, float x) {
        float t = clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    protected static float luminance(float r, float g, float b) {
        return r * 0.299f + g * 0.587f + b * 0.114f;
    }

    protected static void clampColor(float[] rgb) {
        rgb[0] = clamp(rgb[0]);
        rgb[1] = clamp(rgb[1]);
        rgb[2] = clamp(rgb[2]);
    }
}
