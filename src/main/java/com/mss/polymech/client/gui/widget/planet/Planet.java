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
    private final PlanetColorProvider colorProvider;

    private final float orbitalRadius;
    private final float orbitalSpeed;
    private final float axialTilt;
    private final int parentId;
    private final float heightScale;
    private final boolean hasStaticPos;
    private final float staticX, staticY, staticZ;

    private Planet(String name, Polyhedron baseMesh, float defaultRotationSpeed,
                   float orbitalRadius, float orbitalSpeed, float axialTilt, int parentId,
                   List<PlanetLayer> layers, List<TechNode> techNodes, PlanetVisual visual,
                     PlanetColorProvider colorProvider, float heightScale,
                   boolean hasStaticPos, float staticX, float staticY, float staticZ) {
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
        this.colorProvider = colorProvider;
        this.heightScale = heightScale;
        this.hasStaticPos = hasStaticPos;
        this.staticX = staticX;
        this.staticY = staticY;
        this.staticZ = staticZ;
    }

    public String name() { return name; }
    public Polyhedron baseMesh() { return baseMesh; }
    public float defaultRotationSpeed() { return defaultRotationSpeed; }
    public List<PlanetLayer> layers() { return List.copyOf(layers); }
    public List<TechNode> techNodes() { return techNodes; }
    public PlanetVisual visual() { return visual; }
    public PlanetColorProvider colorProvider() { return colorProvider; }
    public float orbitalRadius() { return orbitalRadius; }
    public float orbitalSpeed() { return orbitalSpeed; }

    /** 返回一个仅公转轨道半径不同的副本（用于自动安全距离调整）。 */
    public Planet withOrbitalRadius(float newOrbitalRadius) {
        return new Planet(name, baseMesh, defaultRotationSpeed,
                newOrbitalRadius, orbitalSpeed, axialTilt, parentId,
                layers, techNodes, visual, colorProvider, heightScale,
                hasStaticPos, staticX, staticY, staticZ);
    }

    public boolean hasStaticPos() { return hasStaticPos; }
    public float staticX() { return staticX; }
    public float staticY() { return staticY; }
    public float staticZ() { return staticZ; }
    public float axialTilt() { return axialTilt; }
    public int parentId() { return parentId; }
    /** 表面地形起伏强度：0=光滑（气态巨行星/恒星），>0 为相对半径的高度幅度。 */
    public float heightScale() { return heightScale; }

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
        private PlanetColorProvider colorProvider = PlanetColorProvider.DEFAULT;
        private float heightScale = 0f;
        private boolean hasStaticPos = false;
        private float staticX, staticY, staticZ;

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

        public Builder colorProvider(PlanetColorProvider colorProvider) {
            this.colorProvider = colorProvider;
            return this;
        }

        public Builder heightScale(float heightScale) {
            this.heightScale = heightScale;
            return this;
        }

        /** 静态位置（星图用）：不参与公转，直接放在世界坐标 (x, y, z)。 */
        public Builder staticPos(float x, float y, float z) {
            this.hasStaticPos = true;
            this.staticX = x;
            this.staticY = y;
            this.staticZ = z;
            return this;
        }

        public Planet build() {
            return new Planet(name, baseMesh, defaultRotationSpeed,
                    orbitalRadius, orbitalSpeed, axialTilt, parentId,
                    layers, techNodes, visual, colorProvider, heightScale,
                    hasStaticPos, staticX, staticY, staticZ);
        }
    }
}
