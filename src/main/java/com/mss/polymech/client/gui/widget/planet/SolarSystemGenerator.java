package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 程序化生成一个恒星系的行星系统（真实恒星参数由 StarSystemCatalog 提供）。
 * 每个 StarSystem 调用一次，生成独立的 SolarSystem 场景。
 */
public final class SolarSystemGenerator {
    /** 标准化基准：以地球大小/轨道为 1。 */
    public static final float EARTH_RADIUS = 1.92f;
    public static final float EARTH_ORBIT = 35f;
    public static final float SUN_RADIUS = 8.5f;
    private static final float[] ORBIT_FACTORS = {
            0.57f, 0.77f, 1.00f, 1.26f, 1.65f,
            2.43f, 3.57f, 4.71f, 6.14f, 7.71f
    };
    private static final float MIN_GAP_STAR = 3.0f;    // 恒星表面到行星表面的最小间距
    private static final float MIN_GAP_PLANET = 2.5f;  // 相邻行星表面最小间距
    private static final float MIN_GAP_MOON = 0.6f;    // 行星表面到卫星表面最小间距

    private SolarSystemGenerator() {}

    public static SolarSystem generate(StarSystem system) {
        Random rnd = new Random(system.seed);
        Polyhedron base = Polyhedron.goldberg(3);
        Polyhedron low = Polyhedron.goldberg(2);
        Polyhedron atmoSphere = Polyhedron.sphere(16, 24);

        List<Planet> list = new ArrayList<>();

        // 恒星半径：按光谱缩放，但保证视觉上始终压过所有行星
        float starR = Math.max(SUN_RADIUS * system.star.radius, 5.0f);
        float innerOrbit = Math.max(EARTH_ORBIT * 0.57f, starR * 2.5f);
        float[] cool = system.star.coolColor();
        float[] hot = system.star.hotColor();
        float[][] grad = new float[5][3];
        for (int i = 0; i < 5; i++) {
            float t = i / 4f;
            grad[i] = new float[]{
                    lerp(cool[0], hot[0], t),
                    lerp(cool[1], hot[1], t),
                    lerp(cool[2], hot[2], t)
            };
        }
        Planet star = Planet.of(system.name, base, 0.06f,
                        PlanetLayer.of(PlanetLayerType.BASE, starR),
                        PlanetLayer.of(PlanetLayerType.ATMOSPHERE, starR * 1.04f, atmoSphere))
                .visual(PlanetVisual.star(system.star.r, system.star.g, system.star.b))
                .colorProvider(PlanetColorProvider.star(grad))
                .build();
        list.add(star);

        // ---- 行星 ----
        int planetCount = 5 + rnd.nextInt(5);
        float prevOrb = 0, prevR = 0;
        for (int i = 0; i < planetCount; i++) {
            float factor = ORBIT_FACTORS[Math.min(i, ORBIT_FACTORS.length - 1)] * (0.90f + rnd.nextFloat() * 0.20f);
            float orb = innerOrbit * factor;
            float rot = 0.02f + rnd.nextFloat() * 0.40f;
            float tilt = rnd.nextFloat() * 0.5f;
            boolean far = orb > innerOrbit * 2.2f;
            boolean gas = far || rnd.nextFloat() < 0.30f;
            float baseR = gas
                    ? EARTH_RADIUS * (1.6f + rnd.nextFloat() * 1.4f)
                    : EARTH_RADIUS * (0.28f + rnd.nextFloat() * 0.45f);
            // 行星本体不得大于恒星（气态巨行星最多到恒星半径 60%，岩石行星最多 45%）
            baseR = Math.min(baseR, starR * (gas ? 0.60f : 0.45f));

            // ---- 安全距离 ----
            orb = Math.max(orb, starR + baseR + MIN_GAP_STAR);
            if (i > 0) orb = Math.max(orb, prevOrb + prevR + baseR + MIN_GAP_PLANET);

            String pname = system.name + " " + (i + 1);
            Planet.Builder pb;
            if (gas) {
                float tr = 0.55f + rnd.nextFloat() * 0.45f;
                float tg = 0.45f + rnd.nextFloat() * 0.40f;
                float tb = 0.35f + rnd.nextFloat() * 0.45f;
                PlanetLayer[] layers;
                if (rnd.nextFloat() < 0.5f) {
                    layers = new PlanetLayer[]{
                            PlanetLayer.of(PlanetLayerType.BASE, baseR),
                            PlanetLayer.of(PlanetLayerType.ATMOSPHERE, baseR * 1.06f, atmoSphere),
                            PlanetLayer.of(PlanetLayerType.RING, Math.min(baseR * 1.8f, starR * 0.95f))
                    };
                } else {
                    layers = new PlanetLayer[]{
                            PlanetLayer.of(PlanetLayerType.BASE, baseR),
                            PlanetLayer.of(PlanetLayerType.ATMOSPHERE, baseR * 1.06f, atmoSphere)
                    };
                }
                pb = Planet.of(pname, base, rot, layers)
                        .orbital(orb, 0.04f / (float) Math.sqrt(Math.max(0.2, orb)))
                        .tilt(tilt)
                        .visual(PlanetVisual.withAtmosphere(tr, tg, tb, tr * 0.9f, tg * 0.9f, tb * 0.9f))
                        .colorProvider(PlanetColorProvider.gasGiant(tr, tg, tb));
                Planet gasPlanet = pb.build();
                int parentIdx = list.size();
                list.add(gasPlanet);
                // 卫星
                int moonCount = 1 + rnd.nextInt(3);
                for (int m = 0; m < moonCount; m++) {
                    float mr = baseR * (0.18f + rnd.nextFloat() * 0.15f);
                    float desiredMo = baseR * (2.0f + 0.8f * m + rnd.nextFloat() * 0.5f);
                    float moMin = baseR + mr + MIN_GAP_MOON;
                    float moMax = Math.max(moMin, orb - starR - mr - MIN_GAP_STAR);
                    float mo = Math.min(moMax, Math.max(moMin, desiredMo));
                    float ir = 0.45f + rnd.nextFloat() * 0.45f;
                    float ig = 0.45f + rnd.nextFloat() * 0.40f;
                    float ib = 0.45f + rnd.nextFloat() * 0.40f;
                    Planet moon = Planet.moon(pname + " " + (m + 1), low, 0.05f + rnd.nextFloat() * 0.3f,
                                    mo, 0.12f, rnd.nextFloat() * 0.3f, parentIdx,
                                    PlanetLayer.of(PlanetLayerType.BASE, mr))
                            .visual(PlanetVisual.of(ir, ig, ib))
                            .colorProvider(PlanetColorProvider.rock(ir, ig, ib))
                            .heightScale(0.03f)
                            .build();
                    list.add(moon);
                }
            } else {
                float rr = 0.45f + rnd.nextFloat() * 0.50f;
                float gg = 0.38f + rnd.nextFloat() * 0.42f;
                float bb = 0.28f + rnd.nextFloat() * 0.36f;
                pb = Planet.of(pname, base, rot,
                                PlanetLayer.of(PlanetLayerType.BASE, baseR),
                                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, baseR * 1.05f, atmoSphere))
                        .orbital(orb, 0.05f / (float) Math.sqrt(Math.max(0.2, orb)))
                        .tilt(tilt)
                        .visual(PlanetVisual.withAtmosphere(rr, gg, bb, rr * 0.8f, gg * 0.85f, bb * 0.9f))
                        .colorProvider(PlanetColorProvider.tintedRock(rr, gg, bb))
                        .heightScale(0.04f + rnd.nextFloat() * 0.05f);
                list.add(pb.build());
            }
            prevOrb = orb;
            prevR = baseR;
        }

        SolarSystem sys = new SolarSystem(list);
        return sys;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
