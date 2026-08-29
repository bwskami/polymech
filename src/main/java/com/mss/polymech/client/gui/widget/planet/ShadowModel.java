package com.mss.polymech.client.gui.widget.planet;

import java.util.Arrays;

/**
 * 统一的阴影系统 —— 所有天体间的遮挡阴影都在这里计算。
 *
 * <p>规则：
 * <ul>
 *   <li>卫星在其母星表面投射阴影；</li>
 *   <li>母星在其卫星表面投射阴影（卫星被食）；</li>
 *   <li>阴影只衰减直射光，不直接把颜色乘黑。</li>
 * </ul>
 *
 * <p>每帧预计算一次阴影投射者列表，之后逐顶点查询只遍历实际能遮挡该天体的少量天体。
 */
public final class ShadowModel {
    private final SolarSystem solarSystem;
    /** casters[pi] = 能遮挡 pi 的天体下标列表。 */
    private final int[][] casters;
    /** 每个天体的 BASE 层半径。 */
    private final float[] baseRadius;

    public ShadowModel(SolarSystem solarSystem) {
        this.solarSystem = solarSystem;
        int n = solarSystem.size();
        baseRadius = new float[n];
        for (int i = 0; i < n; i++) {
            Planet p = solarSystem.get(i);
            float r = 0;
            for (PlanetLayer l : p.layers()) {
                if (l.type() == PlanetLayerType.BASE) { r = l.radius(); break; }
            }
            baseRadius[i] = r;
        }
        casters = new int[n][];
        int[] tmp = new int[n];
        for (int pi = 0; pi < n; pi++) {
            int cnt = 0;
            for (int qi = 0; qi < n; qi++) {
                if (qi == pi) continue;
                // 卫星遮挡母星
                if (solarSystem.get(qi).parentId() == pi) tmp[cnt++] = qi;
                // 母星遮挡卫星
                else if (solarSystem.get(pi).parentId() == qi) tmp[cnt++] = qi;
            }
            casters[pi] = Arrays.copyOf(tmp, cnt);
        }
    }

    /** 该天体是否可能被任何其他天体遮挡。 */
    public boolean hasShadow(int pi) {
        return casters[pi].length > 0;
    }

    /**
     * 计算母星反射光（地照/木星照）。
     * 当卫星被食或处于背光面时，母星反射光让暗面保留可见细节，而不是死黑。
     *
     * @param outDir 输出：表面指向母星的方向（相机空间，已归一化），长度 3
     * @return 反射光强度 0..1
     */
    public float parentReflection(int pi, float[] localV, float layerR, float sc, float ss,
                                  float tilt, float simTime, float[] outDir) {
        int parentId = solarSystem.get(pi).parentId();
        if (parentId < 0) return 0;
        float[] parentWP = solarSystem.worldPos(parentId, simTime);
        float parentR = baseRadius[parentId];
        if (parentR < 0.01f) return 0;

        float[] planetWP = solarSystem.worldPos(pi, simTime);
        float planetWx = planetWP[0], planetWz = planetWP[2];

        // 顶点行星局部世界坐标（与 occlusion 相同的旋转顺序）
        float lx = (localV[0] * sc - localV[2] * ss) * layerR;
        float lz = (localV[0] * ss + localV[2] * sc) * layerR;
        float ly = localV[1] * layerR;
        float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        float vX = lx * ct - ly * st + planetWx;
        float vY = lx * st + ly * ct;
        float vZ = lz + planetWz;

        float dx = parentWP[0] - vX;
        float dy = 0f - vY;
        float dz = parentWP[2] - vZ;
        float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1e-5f) return 0;
        dx /= dist; dy /= dist; dz /= dist;

        // 需要由调用方提供相机参数才能转相机空间；这里只返回世界方向，
        // 由调用方调用 PlanetLighting 转换或直接使用世界法线比较。
        // 实际上这里返回世界方向数组，调用方在 drawBaseLayer 中可通过 cameraTo 的旋转转换。
        outDir[0] = dx; outDir[1] = dy; outDir[2] = dz;
        // 反射强度 = 母星视大小比例，上限 0.5，避免过曝
        float strength = Math.min(0.5f, parentR / Math.max(dist, 1f) * 0.8f);
        return Math.max(0f, strength);
    }

    /**
     * 计算天体 pi 表面某顶点的阴影系数（0 = 无阴影，1 = 全阴影）。
     *
     * @param localV  顶点在网格局部空间的单位球坐标
     * @param layerR  图层半径
     * @param sc,ss   天体自转角的正余弦
     * @param tilt    轴倾角（弧度）
     * @param simTime 模拟时间（秒）
     */
    public float occlusion(int pi, float[] localV, float layerR, float sc, float ss,
                           float tilt, float simTime) {
        int[] c = casters[pi];
        if (c.length == 0) return 0;

        float[] planetWP = solarSystem.worldPos(pi, simTime);
        float planetWx = planetWP[0], planetWz = planetWP[2];
        float sunDx = -planetWx, sunDz = -planetWz;
        float sunLen = (float) Math.sqrt(sunDx * sunDx + sunDz * sunDz);
        if (sunLen < 0.01f) return 0;
        float swx = sunDx / sunLen, swz = sunDz / sunLen;

        // 顶点行星局部世界坐标：自转 -> 轴倾角（与 cameraTo 保持同一旋转顺序）
        float lx = (localV[0] * sc - localV[2] * ss) * layerR;
        float lz = (localV[0] * ss + localV[2] * sc) * layerR;
        float ly = localV[1] * layerR;
        float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
        float relX = lx * ct - ly * st;
        float relY = lx * st + ly * ct;
        float relZ = lz;

        float maxShadow = 0;
        for (int qi : c) {
            float casterR = baseRadius[qi];
            if (casterR < 0.01f) continue;
            float[] casterWP = solarSystem.worldPos(qi, simTime);
            float casterRelX = casterWP[0] - planetWx;
            float casterRelZ = casterWP[2] - planetWz;

            float dx = relX - casterRelX;
            float dy = relY;
            float dz = relZ - casterRelZ;
            float dotSun = dx * swx + dz * swz;
            if (dotSun > 0) continue; // 顶点在投射者朝向太阳的一侧

            float perpX = dx - dotSun * swx;
            float perpY = dy;
            float perpZ = dz - dotSun * swz;
            float perpDist = (float) Math.sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ);

            // 阴影锥随距离轻微扩张，半影带柔和过渡
            float coneExpand = 1f + Math.abs(dotSun) * 0.025f;
            float effectiveR = casterR * coneExpand;
            if (perpDist < effectiveR) {
                maxShadow = Math.max(maxShadow, 1f);
            } else if (perpDist < effectiveR * 1.6f) {
                float pen = 1f - (perpDist - effectiveR) / (effectiveR * 0.6f);
                maxShadow = Math.max(maxShadow, pen);
            }
        }
        return maxShadow;
    }
}
