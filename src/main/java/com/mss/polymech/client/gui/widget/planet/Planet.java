package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一个星球：封装所有属于该星球的数据。
 * <p>
 * 包含：图层、科技项、视觉属性、公转参数、卫星关系。
 * 通过 {@link #visual()} 获取视觉属性，不再需要在渲染层做 {@code if (pi == X)} 判断。
 */
public final class Planet {
    private final String name;
    private final Polyhedron baseMesh;
    private final float defaultRotationSpeed;
    private final List<PlanetLayer> layers;
    private final List<TechNode> techNodes;
    private final PlanetVisual visual;

    private final float orbitalRadius;
    private final float orbitalSpeed;
    private final float axialTilt;
    private final int parentId;

    private Planet(String name, Polyhedron baseMesh, float defaultRotationSpeed,
                   float orbitalRadius, float orbitalSpeed, float axialTilt, int parentId,
                   List<PlanetLayer> layers, List<TechNode> techNodes, PlanetVisual visual) {
        this.name = name;
        this.baseMesh = baseMesh;
        this.defaultRotationSpeed = defaultRotationSpeed;
        this.orbitalRadius = orbitalRadius;
        this.orbitalSpeed = orbitalSpeed;
        this.axialTilt = axialTilt;
        this.parentId = parentId;
        this.layers = new ArrayList<>(layers);
        this.layers.sort(Comparator.comparingDouble(PlanetLayer::radius));
        this.techNodes = List.copyOf(techNodes);
        this.visual = visual;
    }

    public String name() { return name; }
    public Polyhedron baseMesh() { return baseMesh; }
    public float defaultRotationSpeed() { return defaultRotationSpeed; }
    public List<PlanetLayer> layers() { return List.copyOf(layers); }
    public List<TechNode> techNodes() { return techNodes; }
    public PlanetVisual visual() { return visual; }
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

    // ============ Builder ============

    public static Builder of(String name, Polyhedron baseMesh, float defaultRotationSpeed, PlanetLayer... layers) {
        return new Builder(name, baseMesh, defaultRotationSpeed, layers);
    }

    public static Builder moon(String name, Polyhedron baseMesh, float defaultRotationSpeed,
                               float orbitalRadius, float orbitalSpeed, float axialTilt, int parentId, PlanetLayer... layers) {
        return new Builder(name, baseMesh, defaultRotationSpeed, layers)
                .orbital(orbitalRadius, orbitalSpeed)
                .tilt(axialTilt)
                .parent(parentId);
    }

    public static final class Builder {
        private final String name;
        private final Polyhedron baseMesh;
        private final float defaultRotationSpeed;
        private final List<PlanetLayer> layers;
        private float orbitalRadius;
        private float orbitalSpeed;
        private float axialTilt;
        private int parentId = -1;
        private List<TechNode> techNodes = List.of();
        private PlanetVisual visual = PlanetVisual.DEFAULT;

        Builder(String name, Polyhedron baseMesh, float defaultRotationSpeed, PlanetLayer[] layers) {
            this.name = name;
            this.baseMesh = baseMesh;
            this.defaultRotationSpeed = defaultRotationSpeed;
            this.layers = List.of(layers);
        }

        public Builder orbital(float radius, float speed) {
            this.orbitalRadius = radius;
            this.orbitalSpeed = speed;
            return this;
        }

        public Builder tilt(float tilt) {
            this.axialTilt = tilt;
            return this;
        }

        public Builder parent(int parentId) {
            this.parentId = parentId;
            return this;
        }

        public Builder techNodes(TechNode... nodes) {
            this.techNodes = List.of(nodes);
            return this;
        }

        public Builder techNodes(List<TechNode> nodes) {
            this.techNodes = List.copyOf(nodes);
            return this;
        }

        public Builder visual(PlanetVisual visual) {
            this.visual = visual;
            return this;
        }

        public Planet build() {
            return new Planet(name, baseMesh, defaultRotationSpeed,
                    orbitalRadius, orbitalSpeed, axialTilt, parentId,
                    layers, techNodes, visual);
        }
    }
}
