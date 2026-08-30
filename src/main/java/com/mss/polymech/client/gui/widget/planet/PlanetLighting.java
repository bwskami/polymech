package com.mss.polymech.client.gui.widget.planet;

/**
 * 标准光照模型 —— 所有图层（BASE / CLOUD / ATMOSPHERE / 碎石带）共用。
 *
 * <p>三个光源各自独立求和（无分支、无昼夜特判）：
 * <pre>
 *   lit = direct + bounce + ambient + refl*0.6
 * </pre>
 * <ul>
 *   <li>direct = max(0, ndotl) × intensity × (1 - shadow)   — 恒星直射 (Lambert)</li>
 *   <li>bounce = direct × 0.16                                — 亮面散射（与 direct 同源）</li>
 *   <li>ambient = 0.06 × intensity × sunVisibility            — 环境光</li>
 *   <li>refl = max(0, dot(n, parentDir)) × reflStrength      — 母星反射（地照）</li>
 * </ul>
 * 昼夜面是 max(0, ndotl) 的自然结果，不是代码分支。
 */
public final class PlanetLighting {
    /** 环境光基线（常量），供云层/碎石带等非行星图层共用。 */
    public static final float AMBIENT = 0.14f;

    private static final float SUN_R = 1.00f, SUN_G = 0.52f, SUN_B = 0.20f;
    private static final float GRAY_R = 0.12f, GRAY_G = 0.14f, GRAY_B = 0.18f;
    private static final float TINT_MIN = 0.25f;
    private static final float TINT_MAX = 0.85f;

    private float dirX, dirY, dirZ = 1f;
    private float intensity = 1f;

    public float dirX() { return dirX; }
    public float dirY() { return dirY; }
    public float dirZ() { return dirZ; }
    public float intensity() { return intensity; }
    public float ambient() { return AMBIENT; }

    public void updateForBody(SolarSystem solarSystem, int pi, float simTime,
                              float cosY, float sinY, float cosX, float sinX) {
        float[] wp = solarSystem.worldPos(pi, simTime);
        updateForWorldPos(wp[0], wp[2], cosY, sinY, cosX, sinX);
    }

    public void updateForWorldPos(float wx, float wz,
                                  float cosY, float sinY, float cosX, float sinX) {
        float dx = -wx, dz = -wz;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-5f) { dirX = 0; dirY = 1; dirZ = 0; intensity = 1f; return; }
        dx /= len; dz /= len;
        float rx = dx * cosY + dz * sinY;
        float rz1 = -dx * sinY + dz * cosY;
        float ry2 = -rz1 * sinX;
        float rz = rz1 * cosX;
        dirX = rx; dirY = ry2; dirZ = rz;
        intensity = clamp(2.0f / (1f + len * 0.012f), 0.35f, 1.1f);
    }

    // ---- Lambert 直射 ----

    /** Lambert 直射光：max(0, ndotl) × intensity × (1 - shadow)。 */
    public float direct(float ndotl, float shadow) {
        return Math.max(0f, ndotl) * intensity * (1f - shadow);
    }

    /** 带全局可见度的直射（供 CPU 路径使用，globalSun 目前只影响 ambient）。 */
    public float direct(float ndotl, float shadow, float globalSun) {
        return direct(ndotl, shadow);
    }

    // ---- 两个 evaluate 版本：camera-space 与 local-space ----

    public void evaluate(float nx, float ny, float nz,
                         float viewX, float viewY, float viewZ,
                         float shadow, SurfaceLight out) {
        evaluate(nx, ny, nz, viewX, viewY, viewZ, shadow, 0f, 0f, 0f, 0f, out);
    }

    public void evaluate(float nx, float ny, float nz,
                         float viewX, float viewY, float viewZ,
                         float shadow, float reflX, float reflY, float reflZ,
                         float reflStrength, SurfaceLight out) {
        float ndotl = nx * dirX + ny * dirY + nz * dirZ;
        float d = direct(ndotl, shadow);
        float refl = computeRefl(nx, ny, nz, reflX, reflY, reflZ, reflStrength);
        out.set(d, 0f, 0f, 0f, 0f, refl, d * 0.16f);
    }

    public void evaluateLocal(float nx, float ny, float nz,
                              float lx, float ly, float lz,
                              float viewX, float viewY, float viewZ,
                              float shadow, float globalSun,
                              float reflX, float reflY, float reflZ,
                              float reflStrength, SurfaceLight out) {
        float ndotl = nx * lx + ny * ly + nz * lz;
        float d = direct(ndotl, shadow);
        float refl = computeRefl(nx, ny, nz, reflX, reflY, reflZ, reflStrength);
        float ambient = 0.06f * intensity * globalSun;
        out.set(d, 0f, 0f, 0f, 0f, refl, d * 0.16f + ambient);
    }

    private static float computeRefl(float nx, float ny, float nz,
                                      float rx, float ry, float rz, float strength) {
        if (strength <= 0f || (rx == 0 && ry == 0 && rz == 0)) return 0f;
        float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rl < 1e-5f) return 0f;
        return Math.max(0f, (nx * rx + ny * ry + nz * rz) / rl) * strength;
    }

    // ---- 颜色合成（GPU/CPU 共用） ----

    public void lightColor(float lit, float[] out) {
        float t = clamp(lit, 0f, 1f);
        out[0] = GRAY_R + (SUN_R - GRAY_R) * t;
        out[1] = GRAY_G + (SUN_G - GRAY_G) * t;
        out[2] = GRAY_B + (SUN_B - GRAY_B) * t;
    }

    public void colorize(float[] albedo, SurfaceLight sl, float[] out) {
        float lit = clamp(sl.direct + sl.bounce + sl.reflected * 0.6f, 0f, 1.4f);
        float t = clamp(lit, 0f, 1f);
        float lr = GRAY_R + (SUN_R - GRAY_R) * t;
        float lg = GRAY_G + (SUN_G - GRAY_G) * t;
        float lb = GRAY_B + (SUN_B - GRAY_B) * t;
        float maxC = Math.max(albedo[0], Math.max(albedo[1], albedo[2]));
        float minC = Math.min(albedo[0], Math.min(albedo[1], albedo[2]));
        float sat = maxC > 1e-5f ? (maxC - minC) / maxC : 0f;
        float tint = TINT_MIN + (TINT_MAX - TINT_MIN) * sat;
        float tr = albedo[0] * lr, tg = albedo[1] * lg, tb = albedo[2] * lb;
        float ll = (lr + lg + lb) / 3f;
        float nr = albedo[0] * ll, ng = albedo[1] * ll, nb = albedo[2] * ll;
        out[0] = Math.min(1f, nr + (tr - nr) * tint);
        out[1] = Math.min(1f, ng + (tg - ng) * tint);
        out[2] = Math.min(1f, nb + (tb - nb) * tint);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
