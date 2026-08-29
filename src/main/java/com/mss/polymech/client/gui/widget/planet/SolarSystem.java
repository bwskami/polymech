package com.mss.polymech.client.gui.widget.planet;

import com.mss.polymech.techtree.Polyhedron;

import java.util.ArrayList;
import java.util.List;

/**
 * 太阳系：一组星球 + 公转轨道。
 * <p>
 * 太阳在原点 (0,0,0)，各行星按轨道半径分布。
 * 公转轨道在 XZ 平面（Y=0），轨道半径单位：1 单位 ≈ 1 AU。
 */
public final class SolarSystem {
    private final List<Planet> planets;
    private final float[] phases; // 每颗行星的随机初始相位角（弧度）

    public SolarSystem(List<Planet> planets) {
        this.planets = List.copyOf(planets);
        // 用固定种子生成随机初始位置，每次进入太阳系行星都不会排成一线
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

    /**
     * 计算第 i 颗行星在时刻 {@code t} 的世界坐标（XZ 平面公转）。
     * 太阳在原点，其余行星按轨道半径 + 公转角速度运动。
     */
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

    /** 根据名字查找行星索引，找不到返回 -1。 */
    public int indexOf(String name) {
        for (int i = 0; i < planets.size(); i++) {
            if (planets.get(i).name().equals(name)) return i;
        }
        return -1;
    }

    /** 构建完整太阳系。公转半径为缩放后的相对值。 */
    public static SolarSystem createDefault() {
        List<Planet> list = new ArrayList<>();
        // 高精度多面体用于行星表面（BASE / CLOUD），面数更多更圆滑
        Polyhedron base = Polyhedron.goldberg(3);
        // 科技网格 / 线框保持低面数（Goldberg 2），保留六边形+五边形风格
        Polyhedron atmoSphere = Polyhedron.sphere(16, 24);

        // ====== 太阳 ======
        list.add(Planet.of("太阳", base, 0.06f, 0f, 0f,
                PlanetLayer.of(PlanetLayerType.BASE, 7.80f)
        ));

        // ====== 水星 ======
        list.add(Planet.of("水星", base, 0.50f, 20f, 0.20f, 0.0006f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.55f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.65f, 2)
        ));

        // ====== 金星 ======
        list.add(Planet.of("金星", base, 0.20f, 27f, 0.080f, 3.09f,
                PlanetLayer.of(PlanetLayerType.BASE, 1.10f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 1.14f).withRotationSpeed(-0.45f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 1.18f).withRotationSpeed(-0.28f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 1.22f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 1.28f, 2)
        ));

        // ====== 地球 + 月球 ======
        int earthIdx = list.size();
        list.add(Planet.of("地球", base, 0.25f, 35f, 0.050f, 0.409f,
                PlanetLayer.of(PlanetLayerType.BASE, 1.92f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.02f).withRotationSpeed(0.22f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.06f),
                PlanetLayer.of(PlanetLayerType.CLOUD, 2.10f).withRotationSpeed(-0.15f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 2.14f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.TECH, 2.17f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 2.20f, 2)
        ));
        list.add(Planet.moon("月球", base, 0.10f, 3.5f, 0.30f, 0.117f, earthIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.50f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.56f, 2)
        ));

        // ====== 火星 + 火卫一/火卫二 ======
        int marsIdx = list.size();
        list.add(Planet.of("火星", base, 0.24f, 44f, 0.026f, 0.439f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.80f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 0.84f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.89f, 2)
        ));
        list.add(Planet.moon("火卫一", base, 0.80f, 1.6f, 0.60f, 0f, marsIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.18f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.22f, 2)
        ));
        list.add(Planet.moon("火卫二", base, 0.30f, 2.4f, 0.35f, 0f, marsIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.14f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.18f, 2)
        ));

        // ====== 木星 + 伽利略卫星 ======
        int jupIdx = list.size();
        list.add(Planet.of("木星", base, 0.40f, 75f, 0.0042f, 0.055f,
                PlanetLayer.of(PlanetLayerType.BASE, 4.80f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 4.98f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 5.10f, 2)
        ));
        list.add(Planet.moon("木卫一 Io", base, 0.40f, 6.0f, 0.50f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.28f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.33f, 2)
        ));
        list.add(Planet.moon("木卫二 Europa", base, 0.30f, 7.5f, 0.40f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.26f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.31f, 2)
        ));
        list.add(Planet.moon("木卫三 Ganymede", base, 0.20f, 9.5f, 0.28f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.34f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.39f, 2)
        ));
        list.add(Planet.moon("木卫四 Callisto", base, 0.15f, 11.5f, 0.22f, 0f, jupIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.32f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.37f, 2)
        ));

        // ====== 土星 + 土卫六等 + 星环 ======
        int satIdx = list.size();
        list.add(Planet.of("土星", base, 0.38f, 105f, 0.0017f, 0.467f,
                PlanetLayer.of(PlanetLayerType.BASE, 4.00f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 4.12f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 6.40f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 4.20f, 2)
        ));
        list.add(Planet.moon("土卫六 Titan", base, 0.10f, 8.0f, 0.25f, 0f, satIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.30f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 0.36f, atmoSphere),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.40f, 2)
        ));
        list.add(Planet.moon("土卫二 Enceladus", base, 0.60f, 7.0f, 0.55f, 0f, satIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.16f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.20f, 2)
        ));

        // ====== 天王星 + 星环 ======
        list.add(Planet.of("天王星", base, 0.30f, 140f, 0.0006f, 1.71f,
                PlanetLayer.of(PlanetLayerType.BASE, 3.60f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 3.72f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 5.20f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 3.80f, 2)
        ));

        // ====== 海王星 ======
        list.add(Planet.of("海王星", base, 0.32f, 185f, 0.0003f, 0.49f,
                PlanetLayer.of(PlanetLayerType.BASE, 3.40f),
                PlanetLayer.of(PlanetLayerType.ATMOSPHERE, 3.52f, atmoSphere),
                PlanetLayer.of(PlanetLayerType.RING, 5.20f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 3.60f, 2)
        ));

        // ====== 冥王星 + 卡戎 ======
        int pluIdx = list.size();
        list.add(Planet.of("冥王星", base, 0.10f, 230f, 0.00015f, 2.09f,
                PlanetLayer.of(PlanetLayerType.BASE, 0.45f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.52f, 2)
        ));
        list.add(Planet.moon("卡戎", base, 0.05f, 1.2f, 0.35f, 0f, pluIdx,
                PlanetLayer.of(PlanetLayerType.BASE, 0.22f),
                PlanetLayer.goldberg(PlanetLayerType.WIREFRAME, 0.27f, 2)
        ));

        return new SolarSystem(list);
    }

}
