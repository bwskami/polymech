package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一个星球：中心点 + 多个按半径排序的图层 + 公转参数。
 * <p>
 * 所有图层共享同一中心点；图层顺序由 {@code radius} 决定（从内到外）。
 * <ul>
 *   <li>{@code geometry} 为空时跟随星球底层网格；</li>
 *   <li>{@code rotationSpeed} 为 {@code NaN} 时跟随星球默认自转速度。</li>
 * </ul>
 */
public final class Planet {
    private final String name;
    private final Polyhedron baseMesh;
    private final float defaultRotationSpeed; // 自转速度 rad/s
    private final List<PlanetLayer> layers;

    // 公转参数（相对父天体）
    private final float orbitalRadius;   // 公转半径（AU/单位），0 = 在中心（如太阳）
    private final float orbitalSpeed;    // 公转角速度 rad/s，0 = 不公转
    private final float axialTilt;
    private final int parentId; // -1 = orbits sun       // 轴倾角（弧度）

    public Planet(String name, Polyhedron baseMesh, float defaultRotationSpeed,
                  float orbitalRadius, float orbitalSpeed, float axialTilt, int parentId,
                  List<PlanetLayer> layers) {
        this.name = name;
        this.baseMesh = baseMesh;
        this.defaultRotationSpeed = defaultRotationSpeed;
        this.orbitalRadius = orbitalRadius;
        this.orbitalSpeed = orbitalSpeed;
        this.axialTilt = axialTilt;
        this.parentId = parentId;
        this.layers = new ArrayList<>(layers);
        this.layers.sort(Comparator.comparingDouble(PlanetLayer::radius));
    }

    public String name() { return name; }
    public Polyhedron baseMesh() { return baseMesh; }
    public float defaultRotationSpeed() { return defaultRotationSpeed; }
    public List<PlanetLayer> layers() { return List.copyOf(layers); }
    public float orbitalRadius() { return orbitalRadius; }
    public float orbitalSpeed() { return orbitalSpeed; }
    public float axialTilt() { return axialTilt; }
    public int parentId() { return parentId; }

    public Optional<PlanetLayer> layer(PlanetLayerType type) {
        return layers.stream().filter(l -> l.type() == type).findFirst();
    }

    public Polyhedron resolveGeometry(PlanetLayer layer) {
        return layer.geometry() != null ? layer.geometry() : baseMesh;
    }

    public float resolveRotationSpeed(PlanetLayer layer) {
        return layer.hasCustomRotationSpeed() ? layer.rotationSpeed() : defaultRotationSpeed;
    }

    /** 便捷构造（无公转参数，默认轴倾角） */
    public static Planet of(String name, Polyhedron baseMesh, float defaultRotationSpeed, PlanetLayer... layers) {
        return new Planet(name, baseMesh, defaultRotationSpeed, 0, 0, 0, -1, List.of(layers));
    }

    /** 带公转参数的完整构造 */
    public static Planet of(String name, Polyhedron baseMesh, float defaultRotationSpeed,
                            float orbitalRadius, float orbitalSpeed, PlanetLayer... layers) {
        return new Planet(name, baseMesh, defaultRotationSpeed, orbitalRadius, orbitalSpeed, 0, -1, List.of(layers));
    }

    /** 带公转 + 轴倾角 */
    public static Planet of(String name, Polyhedron baseMesh, float defaultRotationSpeed,
                            float orbitalRadius, float orbitalSpeed, float axialTilt, PlanetLayer... layers) {
        return new Planet(name, baseMesh, defaultRotationSpeed, orbitalRadius, orbitalSpeed, axialTilt, -1, List.of(layers));
    }

    /** 带公转 + 轴倾角 + 卫星（parentId） */
    public static Planet moon(String name, Polyhedron baseMesh, float defaultRotationSpeed,
                              float orbitalRadius, float orbitalSpeed, float axialTilt, int parentId, PlanetLayer... layers) {
        return new Planet(name, baseMesh, defaultRotationSpeed, orbitalRadius, orbitalSpeed, axialTilt, parentId, List.of(layers));
    }
}
