package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;

/**
 * 一个星球图层。
 * <p>
 * 所有图层共享同一中心点；图层顺序由 {@code radius} 决定（从内到外）。
 * <ul>
 *   <li>{@code geometry} 为空时跟随星球底层网格；</li>
 *   <li>{@code rotationSpeed} 为 {@code NaN} 时跟随星球默认自转速度。</li>
 * </ul>
 */
public final class PlanetLayer {
    private final PlanetLayerType type;
    private final float radius;
    private final Polyhedron geometry;    // null = 跟随底层
    private final float rotationSpeed;    // NaN = 跟随星球默认
    private final boolean visible;

    private PlanetLayer(PlanetLayerType type, float radius, Polyhedron geometry, float rotationSpeed, boolean visible) {
        this.type = type;
        this.radius = radius;
        this.geometry = geometry;
        this.rotationSpeed = rotationSpeed;
        this.visible = visible;
    }

    public static PlanetLayer of(PlanetLayerType type, float radius) {
        return new PlanetLayer(type, radius, null, Float.NaN, true);
    }

    public static PlanetLayer of(PlanetLayerType type, float radius, Polyhedron geometry) {
        return new PlanetLayer(type, radius, geometry, Float.NaN, true);
    }

    /** 用 Goldberg 多面体作为图层网格：subdiv 越大面数越多。 */
    public static PlanetLayer goldberg(PlanetLayerType type, float radius, int subdiv) {
        return of(type, radius, Polyhedron.goldberg(subdiv));
    }

    /** 用 UV 球作为图层网格：stacks/slices 控制分辨率。 */
    public static PlanetLayer sphere(PlanetLayerType type, float radius, int stacks, int slices) {
        return of(type, radius, Polyhedron.sphere(stacks, slices));
    }

    public PlanetLayer withGeometry(Polyhedron geometry) {
        return new PlanetLayer(type, radius, geometry, rotationSpeed, visible);
    }

    /** 手动指定自转速度（rad/s），0 表示不自转。 */
    public PlanetLayer withRotationSpeed(float speed) {
        return new PlanetLayer(type, radius, geometry, speed, visible);
    }

    public PlanetLayer withVisible(boolean visible) {
        return new PlanetLayer(type, radius, geometry, rotationSpeed, visible);
    }

    public PlanetLayerType type() { return type; }
    public float radius() { return radius; }
    public Polyhedron geometry() { return geometry; }
    /** 返回是否手动指定了旋转速度。 */
    public boolean hasCustomRotationSpeed() { return !Float.isNaN(rotationSpeed); }
    public float rotationSpeed() { return rotationSpeed; }
    public boolean visible() { return visible; }

    public PlanetLayer withRadius(float newRadius) {
        return new PlanetLayer(type, newRadius, geometry, rotationSpeed, visible);
    }
}
