package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;

import java.util.ArrayList;
import java.util.List;

/**
 * 太阳系：一组星球 + 公转轨道。
 * 太阳在原点 (0,0,0)，各行星按轨道半径分布。
 */
public final class SolarSystem {
    private final List<Planet> planets;
    private final float[] phases;

    public SolarSystem(List<Planet> planets) {
        this.planets = List.copyOf(planets);
        this.phases = new float[planets.size()];
        long rng = 0xCAFEBABEL;
        for (int i = 0; i < planets.size(); i++) {
            if (planets.get(i).orbitalRadius() == 0) { phases[i] = 0; continue; }
            rng = rng * 6364136223846793005L + 1442695040888963407L;
            phases[i] = ((int)(rng >>> 33)) / (float)(1L << 31) * 6.2832f;
        }
    }

    public List<Planet> planets() { return planets; }
    public int size() { return planets.size(); }
    public Planet get(int i) { return planets.get(i); }

    public float[] worldPos(int i, float t) {
        Planet p = planets.get(i);
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

    public int indexOf(String name) {
        for (int i = 0; i < planets.size(); i++) {
            if (planets.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    public static SolarSystem createDefault() {
        List<Planet> list = new ArrayList<>();
        Polyhedron base = Polyhedron.goldberg(3);
        Polyhedron atmoSphere = Polyhedron.sphere(16, 24);

        list.add(Planet.of("\u592a\u9633", base, 0.06f,
                PlanetLayer.of(PlanetLayerType.BASE, 8.50f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 8.80f, atmoSphere))
                .visual(PlanetVisual.SUN).build());

        list.add(Planet.of("\u6c34\u661f", base, 0.50f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.55f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.65f, 2))
                .orbital(20f, 0.20f).tilt(0.0006f)
                .visual(PlanetVisual.MERCURY).build());

        list.add(Planet.of("\u91d1\u661f", base, 0.20f,
                PlanetLayer.of(PlanetLayerType.BASE, 1.10f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 1.14f).withRotationSpeed(-0.45f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 1.18f).withRotationSpeed(-0.28f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 1.22f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 1.28f, 2))
                .orbital(27f, 0.080f).tilt(3.09f)
                .visual(PlanetVisual.VENUS).build());

        int earthIdx = list.size();
        list.add(Planet.of("\u5730\u7403", base, 0.25f,
                PlanetLayer.of(PlanetLayerType.BASE, 1.92f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.02f).withRotationSpeed(0.22f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.06f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.10f).withRotationSpeed(-0.15f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 2.14f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.TECH, 2.17f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 2.20f, 2))
                .orbital(35f, 0.050f).tilt(0.409f)
                .visual(PlanetVisual.EARTH).build());
        list.add(Planet.moon("\u6708\u7403", base, 0.10f, 3.5f, 0.30f, 0.117f, earthIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.50f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.56f, 2))
                .visual(PlanetVisual.MOON).build());

        int marsIdx = list.size();
        list.add(Planet.of("\u706b\u661f", base, 0.24f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.80f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 0.84f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.89f, 2))
                .orbital(44f, 0.026f).tilt(0.439f)
                .visual(PlanetVisual.MARS).build());
        list.add(Planet.moon("\u706b\u536b\u4e00", base, 0.80f, 1.6f, 0.60f, 0f, marsIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.18f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.22f, 2))
                .visual(PlanetVisual.PHOBOS).build());
        list.add(Planet.moon("\u706b\u536b\u4e8c", base, 0.30f, 2.4f, 0.35f, 0f, marsIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.14f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.18f, 2))
                .visual(PlanetVisual.DEIMOS).build());

        int jupIdx = list.size();
        list.add(Planet.of("\u6728\u661f", base, 0.40f,
                PlanetLayer.of(PlanetLayerType.BASE, 4.80f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 4.98f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 5.10f, 2))
                .orbital(85f, 0.0042f).tilt(0.055f)
                .visual(PlanetVisual.JUPITER).build());
        list.add(Planet.moon("\u6728\u536b\u4e00 Io", base, 0.40f, 6.0f, 0.50f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.28f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.33f, 2))
                .visual(PlanetVisual.IO).build());
        list.add(Planet.moon("\u6728\u536b\u4e8c Europa", base, 0.30f, 7.5f, 0.40f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.26f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.31f, 2))
                .visual(PlanetVisual.EUROPA).build());
        list.add(Planet.moon("\u6728\u536b\u4e09 Ganymede", base, 0.20f, 9.5f, 0.28f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.34f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.39f, 2))
                .visual(PlanetVisual.GANYMEDE).build());
        list.add(Planet.moon("\u6728\u536b\u56db Callisto", base, 0.15f, 11.5f, 0.22f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.32f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.37f, 2))
                .visual(PlanetVisual.CALLISTO).build());

        int satIdx = list.size();
        list.add(Planet.of("\u571f\u661f", base, 0.38f,
                PlanetLayer.of(PlanetLayerType.BASE, 4.00f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 4.12f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 6.40f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 4.20f, 2))
                .orbital(125f, 0.0017f).tilt(0.467f)
                .visual(PlanetVisual.SATURN).build());
        list.add(Planet.moon("\u571f\u536b\u516d Titan", base, 0.10f, 8.0f, 0.25f, 0f, satIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.30f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 0.36f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.40f, 2))
                .visual(PlanetVisual.TITAN).build());
        list.add(Planet.moon("\u571f\u536b\u4e8c Enceladus", base, 0.60f, 7.0f, 0.55f, 0f, satIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.16f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.20f, 2))
                .visual(PlanetVisual.ENCELADUS).build());

        list.add(Planet.of("\u5929\u738b\u661f", base, 0.30f,
                PlanetLayer.of(PlanetLayerType.BASE, 3.60f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 3.72f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 5.20f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 3.80f, 2))
                .orbital(165f, 0.0006f).tilt(1.71f)
                .visual(PlanetVisual.URANUS).build());

        list.add(Planet.of("\u6d77\u738b\u661f", base, 0.32f,
                PlanetLayer.of(PlanetLayerType.BASE, 3.40f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 3.52f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 5.20f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 3.60f, 2))
                .orbital(215f, 0.0003f).tilt(0.49f)
                .visual(PlanetVisual.NEPTUNE).build());

        int pluIdx = list.size();
        list.add(Planet.of("\u51a5\u738b\u661f", base, 0.10f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.45f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.52f, 2))
                .orbital(270f, 0.00015f).tilt(2.09f)
                .visual(PlanetVisual.PLUTO).build());
        list.add(Planet.moon("\u5361\u620e", base, 0.05f, 1.2f, 0.35f, 0f, pluIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.22f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.27f, 2))
                .visual(PlanetVisual.CHARON).build());

        return new SolarSystem(list);
    }
}
