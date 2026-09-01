package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;
import com.mss.polymech.techtree.TechNode;
import com.mss.polymech.techtree.TechTree;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 太阳系：一组星球 + 公转轨道。
 * 太阳在原点 (0,0,0)，各行星按轨道半径分布。
 */
public final class SolarSystem {
    /** 星图模式：目录光年坐标放大到场景坐标的比例。 */
    public static final float GALAXY_MAP_SCALE = 1.6f;
    /** 恒星表面到行星表面的最小间距。 */
    static final float MIN_GAP_STAR = 3.0f;
    /** 相邻行星表面最小间距。 */
    static final float MIN_GAP_PLANET = 2.5f;
    /** 行星表面到卫星表面 / 相邻卫星表面最小间距。 */
    static final float MIN_GAP_MOON = 0.6f;

    private final List<Planet> planets;
    private final float[] phases;

    public SolarSystem(List<Planet> planets) {
        this.planets = enforceSafeOrbits(new java.util.ArrayList<>(planets));
        this.phases = new float[this.planets.size()];
        long rng = 0xCAFEBABEL;
        for (int i = 0; i < planets.size(); i++) {
            if (planets.get(i).orbitalRadius() == 0) { phases[i] = 0; continue; }
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            phases[i] = ((int)(rng >>> 33)) / (float)(1L << 31) * 6.2832f;
        }
    }

    public List<Planet> planets() { return planets; }
    /** Reusable output for worldPosTo (avoids per-call allocation). */
    private final float[] _wpOut = new float[3];

    public int size() { return planets.size(); }
    public Planet get(int i) { return planets.get(i); }

    public float[] worldPos(int i, float t) {
        Planet p = planets.get(i);
        if (p.hasStaticPos()) {
            return new float[]{p.staticX(), p.staticY(), p.staticZ()};
        }
        float x = 0, z = 0;
        if (p.orbitalRadius() > 0) {
            float angle = p.orbitalSpeed() * t + phases[i];
            x = p.orbitalRadius() * (float) Math.cos(angle);
            z = p.orbitalRadius() * (float) Math.sin(angle);
        }
        if (p.parentId() >= 0) {
            float[] pp = worldPos(p.parentId(), t);
            x += pp[0]; z += pp[2];
        }
        return new float[]{x, 0, z};
    }

    /** Zero-allocation version of worldPos: writes result into out[0..2]. */
    public float[] worldPosTo(float[] out, int i, float t) {
        Planet p = planets.get(i);
        if (p.hasStaticPos()) {
            out[0] = p.staticX(); out[1] = p.staticY(); out[2] = p.staticZ();
            return out;
        }
        float x = 0, z = 0;
        if (p.orbitalRadius() > 0) {
            float angle = p.orbitalSpeed() * t + phases[i];
            x = p.orbitalRadius() * (float) Math.cos(angle);
            z = p.orbitalRadius() * (float) Math.sin(angle);
        }
        if (p.parentId() >= 0) {
            float[] parentWP = worldPosTo(out, p.parentId(), t);
            x += parentWP[0]; z += parentWP[2];
        }
        out[0] = x; out[1] = 0; out[2] = z;
        return out;
    }

    public int indexOf(String name) {
        for (int i = 0; i < planets.size(); i++) {
            if (planets.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    // ============================ 安全距离 ============================

    /** 视觉半径取该星球最大可见图层半径（BASE/CLOUD/ATMOSPHERE/WIREFRAME/TECH/RING 都算）。 */
    private static float visualRadius(Planet p) {
        float max = 0;
        for (PlanetLayer l : p.layers()) {
            if (l.visible()) max = Math.max(max, l.radius());
        }
        return max > 0 ? max : 0.5f;
    }

    /**
     * 自动调整轨道半径，所有间距都按视觉半径（整个星球最外层）计算：
     * 1. 行星不撞恒星；
     * 2. 相邻行星不撞；
     * 3. 卫星不撞母星、不撞恒星、不撞其他行星；
     * 4. 同一行星的相邻卫星轨道不撞。
     */
    private static List<Planet> enforceSafeOrbits(List<Planet> list) {
        int starIdx = -1;
        for (int i = 0; i < list.size(); i++) {
            Planet p = list.get(i);
            if (p.orbitalRadius() == 0 && p.parentId() < 0) { starIdx = i; break; }
        }
        if (starIdx < 0) return list;
        float starR = visualRadius(list.get(starIdx));

        List<Integer> planetIdx = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Planet p = list.get(i);
            if (p.parentId() < 0 && p.orbitalRadius() > 0) planetIdx.add(i);
        }
        if (planetIdx.isEmpty()) return list;

        // 迭代：先按视觉半径拉开行星，再拟合卫星；若有卫星放不下，就继续把冲突行星向外推，直到全部放下。
        for (int iter = 0; iter < 40; iter++) {
            planetIdx.sort(Comparator.comparingDouble(i -> list.get(i).orbitalRadius()));
            boolean moved = false;

            // ---- 1. 行星轨道：不撞恒星 + 相邻行星视觉半径不撞 ----
            float prevOrbit = 0, prevR = 0;
            for (int pi : planetIdx) {
                Planet p = list.get(pi);
                float r = visualRadius(p);
                float orb = p.orbitalRadius();
                orb = Math.max(orb, starR + r + MIN_GAP_STAR);
                if (prevR > 0) {
                    orb = Math.max(orb, prevOrbit + prevR + r + MIN_GAP_PLANET);
                }
                if (orb > p.orbitalRadius() + 1e-4f) {
                    list.set(pi, p.withOrbitalRadius(orb));
                    moved = true;
                }
                prevOrbit = list.get(pi).orbitalRadius();
                prevR = r;
            }

            // ---- 2. 卫星轨道：按母星分组逐个向外排布 ----
            boolean conflict = false;
            for (int parentId = 0; parentId < list.size() && !conflict; parentId++) {
                Planet parent = list.get(parentId);
                if (parent.orbitalRadius() <= 0) continue;
                final int pid = parentId;
                List<Integer> moonIdx = new ArrayList<>();
                for (int mi = 0; mi < list.size(); mi++) {
                    if (list.get(mi).parentId() == pid) moonIdx.add(mi);
                }
                if (moonIdx.isEmpty()) continue;
                moonIdx.sort(Comparator.comparingDouble(i -> list.get(i).orbitalRadius()));

                float parentOrbit = parent.orbitalRadius();
                float parentR = visualRadius(parent);
                float prevMoOrbit = 0, prevMoR = 0;
                for (int mi : moonIdx) {
                    Planet m = list.get(mi);
                    float mr = visualRadius(m);

                    float minAllowed = parentR + mr + MIN_GAP_MOON;
                    if (prevMoR > 0) {
                        minAllowed = Math.max(minAllowed, prevMoOrbit + prevMoR + mr + MIN_GAP_MOON);
                    }

                    float maxAllowed = Float.MAX_VALUE;
                    int limitingPi = -1;
                    float starLimit = parentOrbit - starR - mr - MIN_GAP_STAR;
                    if (starLimit < maxAllowed) {
                        maxAllowed = starLimit;
                        limitingPi = -1;
                    }
                    for (int pi : planetIdx) {
                        if (pi == pid) continue;
                        Planet other = list.get(pi);
                        float otherOrbit = other.orbitalRadius();
                        float limit = Math.abs(parentOrbit - otherOrbit) - visualRadius(other) - mr - MIN_GAP_MOON;
                        if (limit < maxAllowed) {
                            maxAllowed = limit;
                            limitingPi = pi;
                        }
                    }

                    if (maxAllowed < minAllowed) {
                        // 放不下：把限制方（外侧行星 / 或母星自身）向外推，下一轮重新拟合
                        float deficit = minAllowed - maxAllowed + 0.01f;
                        int targetPi;
                        if (limitingPi >= 0 && list.get(limitingPi).orbitalRadius() > parentOrbit) {
                            targetPi = limitingPi;
                        } else {
                            targetPi = pid;
                        }
                        Planet target = list.get(targetPi);
                        list.set(targetPi, target.withOrbitalRadius(target.orbitalRadius() + deficit));
                        moved = true;
                        conflict = true;
                        break;
                    }

                    float mo = Math.max(m.orbitalRadius(), minAllowed);
                    mo = Math.min(mo, maxAllowed);
                    if (Math.abs(mo - m.orbitalRadius()) > 1e-4f) {
                        list.set(mi, m.withOrbitalRadius(mo));
                    }
                    prevMoOrbit = list.get(mi).orbitalRadius();
                    prevMoR = mr;
                }
            }

            if (!moved && !conflict) break;
        }

        return list;
    }



    /** 星图模式：把整张星图看作一个“大恒星系”，每个目录恒星是一个静态位置的恒星 Planet。 */
    public static SolarSystem createGalaxyMap() {
        List<Planet> list = new ArrayList<>();
        Polyhedron base = Polyhedron.goldberg(3);
        Polyhedron atmoSphere = Polyhedron.sphere(16, 24);
        for (int i = 0; i < StarSystemCatalog.size(); i++) {
            StarSystem s = StarSystemCatalog.get(i);
            float starR = 0.38f + s.star.radius * 0.22f;
            float[] cool = s.star.coolColor();
            float[] hot = s.star.hotColor();
            float[][] grad = new float[5][3];
            for (int g = 0; g < 5; g++) {
                float t = g / 4f;
                grad[g] = new float[]{
                        lerp(cool[0], hot[0], t),
                        lerp(cool[1], hot[1], t),
                        lerp(cool[2], hot[2], t)
                };
            }
            Planet p = Planet.of(s.name, base, 0.06f,
                            PlanetLayer.of(PlanetLayerType.BASE, starR),
                            PlanetLayer.of(PlanetLayerType.ATMOSPHERE, starR * 1.04f, atmoSphere))
                    .staticPos(s.x * GALAXY_MAP_SCALE, s.y * GALAXY_MAP_SCALE, s.z * GALAXY_MAP_SCALE)
                    .visual(PlanetVisual.star(s.star.r, s.star.g, s.star.b))
                    .colorProvider(PlanetColorProvider.star(grad))
                    .heightScale(0f)
                    .build();
            list.add(p);
        }
        return new SolarSystem(list);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static SolarSystem createDefault() {
        List<Planet> list = new ArrayList<>();
        Polyhedron base = Polyhedron.goldberg(3);
        Polyhedron rockyBase = Polyhedron.goldberg(4); // 地球专用：高一个等级
        Polyhedron lowBase = Polyhedron.goldberg(2);     // 矮行星：更低分辨率
        Polyhedron atmoSphere = Polyhedron.sphere(16, 24);

        list.add(Planet.of("\u592a\u9633", base, 0.06f,
                PlanetLayer.of(PlanetLayerType.BASE, 8.50f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 8.80f, atmoSphere))
                .visual(PlanetVisual.SUN).colorProvider(PlanetColorProvider.STAR).build());

        list.add(Planet.of("\u6c34\u661f", base, 0.50f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.55f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.65f, 2))
                .orbital(20f, 0.20f).tilt(0.0006f)
                .visual(PlanetVisual.MERCURY).colorProvider(PlanetColorProvider.mercury(0.60f, 0.55f, 0.50f)).heightScale(0.080f).build());

        list.add(Planet.of("\u91d1\u661f", base, 0.20f,
                PlanetLayer.of(PlanetLayerType.BASE, 1.10f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 1.14f).withRotationSpeed(-0.45f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 1.18f).withRotationSpeed(-0.28f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 1.22f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 1.28f, 2))
                .orbital(27f, 0.080f).tilt(3.09f)
                .visual(PlanetVisual.VENUS).colorProvider(PlanetColorProvider.venus(0.85f, 0.75f, 0.40f)).heightScale(0.040f).build());

        int earthIdx = list.size();
        list.add(Planet.of("\u5730\u7403", rockyBase, 0.25f,
                PlanetLayer.of(PlanetLayerType.BASE, 1.92f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.02f).withRotationSpeed(0.22f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.06f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.10f).withRotationSpeed(-0.15f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 2.14f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.TECH, 2.17f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 2.20f, 2))
                .orbital(35f, 0.050f).tilt(0.409f)
                .visual(PlanetVisual.EARTH).colorProvider(PlanetColorProvider.EARTH).heightScale(0.120f).build());
        list.add(Planet.moon("\u6708\u7403", lowBase, 0.10f, 3.5f, 0.30f, 0.117f, earthIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.50f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.56f, 2))
                .visual(PlanetVisual.MOON).colorProvider(PlanetColorProvider.moon(0.55f, 0.53f, 0.50f)).heightScale(0.100f).build());

        int marsIdx = list.size();
        list.add(Planet.of("\u706b\u661f", base, 0.24f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.80f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 0.84f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.89f, 2))
                .orbital(44f, 0.026f).tilt(0.439f)
                .visual(PlanetVisual.MARS).colorProvider(PlanetColorProvider.mars(0.80f, 0.35f, 0.15f)).heightScale(0.100f).build());
        list.add(Planet.moon("\u706b\u536b\u4e00", lowBase, 0.80f, 1.6f, 0.60f, 0f, marsIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.18f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.22f, 2))
                .visual(PlanetVisual.PHOBOS).colorProvider(PlanetColorProvider.rock(0.50f, 0.48f, 0.45f)).heightScale(0.120f).build());
        list.add(Planet.moon("\u706b\u536b\u4e8c", lowBase, 0.30f, 2.4f, 0.35f, 0f, marsIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.14f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.18f, 2))
                .visual(PlanetVisual.DEIMOS).colorProvider(PlanetColorProvider.rock(0.55f, 0.50f, 0.48f)).heightScale(0.120f).build());

        int jupIdx = list.size();
        list.add(Planet.of("\u6728\u661f", base, 0.40f,
                PlanetLayer.of(PlanetLayerType.BASE, 4.80f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 4.98f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 5.10f, 2))
                .orbital(85f, 0.0042f).tilt(0.055f)
                .visual(PlanetVisual.JUPITER).colorProvider(PlanetColorProvider.JUPITER).build());
        list.add(Planet.moon("\u6728\u536b\u4e00 Io", lowBase, 0.40f, 6.0f, 0.50f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.28f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.33f, 2))
                .visual(PlanetVisual.IO).colorProvider(PlanetColorProvider.io(0.85f, 0.75f, 0.20f)).heightScale(0.100f).build());
        list.add(Planet.moon("\u6728\u536b\u4e8c Europa", lowBase, 0.30f, 7.5f, 0.40f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.26f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.31f, 2))
                .visual(PlanetVisual.EUROPA).colorProvider(PlanetColorProvider.europa(0.85f, 0.82f, 0.75f)).heightScale(0.040f).build());
        list.add(Planet.moon("\u6728\u536b\u4e09 Ganymede", lowBase, 0.20f, 9.5f, 0.28f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.34f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.39f, 2))
                .visual(PlanetVisual.GANYMEDE).colorProvider(PlanetColorProvider.rock(0.60f, 0.55f, 0.50f)).heightScale(0.080f).build());
        list.add(Planet.moon("\u6728\u536b\u56db Callisto", lowBase, 0.15f, 11.5f, 0.22f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.32f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.37f, 2))
                .visual(PlanetVisual.CALLISTO).colorProvider(PlanetColorProvider.rock(0.40f, 0.38f, 0.35f)).heightScale(0.100f).build());

        int satIdx = list.size();
        list.add(Planet.of("\u571f\u661f", base, 0.38f,
                PlanetLayer.of(PlanetLayerType.BASE, 4.00f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 4.12f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 6.40f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 4.20f, 2))
                .orbital(125f, 0.0017f).tilt(0.467f)
                .visual(PlanetVisual.SATURN).colorProvider(PlanetColorProvider.SATURN).build());
        list.add(Planet.moon("\u571f\u536b\u516d Titan", lowBase, 0.10f, 8.0f, 0.25f, 0f, satIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.30f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 0.36f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.40f, 2))
                .visual(PlanetVisual.TITAN).colorProvider(PlanetColorProvider.rock(0.85f, 0.55f, 0.25f)).heightScale(0.080f).build());
        list.add(Planet.moon("\u571f\u536b\u4e8c Enceladus", lowBase, 0.60f, 7.0f, 0.55f, 0f, satIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.16f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.20f, 2))
                .visual(PlanetVisual.ENCELADUS).colorProvider(PlanetColorProvider.enceladus()).heightScale(0.030f).build());

        list.add(Planet.of("\u5929\u738b\u661f", base, 0.30f,
                PlanetLayer.of(PlanetLayerType.BASE, 3.60f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 3.72f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 5.20f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 3.80f, 2))
                .orbital(165f, 0.0006f).tilt(1.71f)
                .visual(PlanetVisual.URANUS).colorProvider(PlanetColorProvider.gasGiant(0.55f, 0.75f, 0.85f)).build());

        list.add(Planet.of("\u6d77\u738b\u661f", base, 0.32f,
                PlanetLayer.of(PlanetLayerType.BASE, 3.40f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 3.52f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 5.20f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 3.60f, 2))
                .orbital(215f, 0.0003f).tilt(0.49f)
                .visual(PlanetVisual.NEPTUNE).colorProvider(PlanetColorProvider.gasGiant(0.35f, 0.55f, 0.90f)).build());

        int pluIdx = list.size();
        list.add(Planet.of("\u51a5\u738b\u661f", lowBase, 0.10f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.45f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.52f, 2))
                .orbital(270f, 0.00015f).tilt(2.09f)
                .visual(PlanetVisual.PLUTO).colorProvider(PlanetColorProvider.pluto(0.65f, 0.60f, 0.55f)).heightScale(0.100f).build());
        list.add(Planet.moon("\u5361\u620e", lowBase, 0.05f, 1.2f, 0.35f, 0f, pluIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.22f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.27f, 2))
                .visual(PlanetVisual.CHARON).colorProvider(PlanetColorProvider.rock(0.50f, 0.48f, 0.45f)).heightScale(0.100f).build());

        SolarSystem sys = new SolarSystem(list);
        sys.bindPlanetTechNodes();
        return sys;
    }

    /**
     * 将 TechTree 中带有 planetName 的节点自动分配到对应星球。
     */
    private void bindPlanetTechNodes() {
        for (Planet p : planets) {
            List<TechNode> bound = TechTree.nodesForPlanet(p.name());
            if (!bound.isEmpty()) {
                int idx = planets.indexOf(p);
                planets.set(idx, rebuildWithTechNodes(p, bound));
            }
        }
    }

    private static Planet rebuildWithTechNodes(Planet original, List<TechNode> nodes) {
        return Planet.of(original.name(), original.baseMesh(), original.defaultRotationSpeed(),
                        original.layers().toArray(new PlanetLayer[0]))
                .orbital(original.orbitalRadius(), original.orbitalSpeed())
                .tilt(original.axialTilt())
                .visual(original.visual())
                .colorProvider(original.colorProvider())
                .heightScale(original.heightScale())
                .techNodes(nodes)
                .build();
    }
}
