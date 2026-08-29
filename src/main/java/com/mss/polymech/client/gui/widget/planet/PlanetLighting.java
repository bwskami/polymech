package com.mss.polymech.client.gui.widget.planet;

/**
 * 全局光照模型 —— 所有图层（BASE / CLOUD / ATMOSPHERE / 碎石带）共用。
 *
 * <p>模型参考 Mindustry 的 planet.vert：
 * <ul>
 *   <li>Half-Lambert 漫反射：{@code (dot(N,L)+1)/2}</li>
 *   <li>Blinn-Phong 高光</li>
 *   <li>环境光 + 直射光分离：阴影只衰减直射光，不把最终颜色乘黑</li>
 * </ul>
 *
 * <p>光线方向存储为相机空间向量（与渲染坐标一致），太阳位于世界原点。
 */
public final class PlanetLighting {
    /** 环境光强度。 */
    public static final float AMBIENT = 0.18f;
    /** 暗部底色（统一由本模型决定，不再是各图层各写一份）。 */
    public static final float DARK_R = 0.10f;
    public static final float DARK_G = 0.12f;
    public static final float DARK_B = 0.16f;
    /** 高光参数。 */
    private static final float SPEC_POWER = 48f;
    private static final float SPEC_STRENGTH = 0.35f;

    private float dirX, dirY, dirZ = 1f;
    private float intensity = 1f;

    public float dirX() { return dirX; }
    public float dirY() { return dirY; }
    public float dirZ() { return dirZ; }
    public float intensity() { return intensity; }

    /**
     * 根据某个天体的世界位置更新全局光照方向。
     * 太阳位于世界原点，方向 = normalize(-worldPos)。
     */
    public void updateForBody(SolarSystem solarSystem, int pi, float simTime,
                              float cosY, float sinY, float cosX, float sinX) {
        float[] wp = solarSystem.worldPos(pi, simTime);
        updateForWorldPos(wp[0], wp[2], cosY, sinY, cosX, sinX);
    }

    /**
     * 直接根据世界坐标（XZ 平面）更新光照方向，用于小行星等不隶属于行星的物体。
     */
    public void updateForWorldPos(float wx, float wz,
                                  float cosY, float sinY, float cosX, float sinX) {
        float dx = -wx, dz = -wz; // 太阳在原点
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-5f) {
            dirX = 0; dirY = 1; dirZ = 0; intensity = 1f;
            return;
        }
        dx /= len; dz /= len;
        float rx = dx * cosY + dz * sinY;
        float rz1 = -dx * sinY + dz * cosY;
        float ry2 = -rz1 * sinX;
        float rz = rz1 * cosX;
        dirX = rx; dirY = ry2; dirZ = rz;
        // 距离衰减：近处更亮，远处更暗，保持在一个舒服的范围
        intensity = clamp(2.0f / (1f + len * 0.012f), 0.35f, 1.1f);
    }

    /** Half-Lambert 漫反射因子（0..1），比纯 dot 更柔和。 */
    public float halfLambert(float ndotl) {
        float hl = ndotl * 0.5f + 0.5f;
        return (float) Math.pow(hl, 1.2f);
    }

    /**
     * 直射光强度 0..1：halfLambert * 太阳强度 * (1 - 阴影)。
     * 被食时 shadow=1 -> 直射光为 0，只剩环境光，绝不会死黑。
     */
    public float direct(float ndotl, float shadow) {
        return clamp(halfLambert(ndotl) * intensity * (1f - shadow), 0f, 1f);
    }

    /** Blinn-Phong 高光，阴影区无高光。 */
    public float specular(float ndoth, float shadow) {
        float s = (float) Math.pow(Math.max(0f, ndoth), SPEC_POWER) * SPEC_STRENGTH;
        return s * intensity * (1f - shadow);
    }

    /** 受光面边缘暖光。 */
    public float rimWarm(float ndotl, float rim, float shadow) {
        return Math.max(0f, ndotl) * intensity * (1f - shadow) * rim;
    }

    /** 背光面边缘冷光（环境光的一部分，不受阴影影响）。 */
    public float rimCool(float ndotl, float rim) {
        return Math.max(0f, -ndotl) * rim * 0.35f;
    }

    /** 暗部蓝色偏移量。 */
    public float shadowBlue(float direct) {
        return (1f - direct) * 0.06f;
    }

    /**
     * 对表面点求值并写入 {@link SurfaceLight}。
     *
     * @param nx,ny,nz 表面法线（相机空间，已归一化）
     * @param viewX,viewY,viewZ 从表面指向相机的视线方向（相机空间，未归一化即可）
     * @param shadow 阴影系数 0..1（0 = 无阴影，1 = 全阴影）
     */
    public void evaluate(float nx, float ny, float nz,
                         float viewX, float viewY, float viewZ,
                         float shadow, SurfaceLight out) {
        evaluate(nx, ny, nz, viewX, viewY, viewZ, shadow, 0f, 0f, 0f, 0f, out);
    }

    /**
     * 带母星反射光的求值。
     *
     * @param reflX,reflY,reflZ 表面指向母星的方向（世界/相机空间均可，此处为相机空间），无反射传 0
     * @param reflStrength 反射光强度 0..1（母星大小/距离决定）
     */
    public void evaluate(float nx, float ny, float nz,
                         float viewX, float viewY, float viewZ,
                         float shadow, float reflX, float reflY, float reflZ,
                         float reflStrength, SurfaceLight out) {
        float ndotl = nx * dirX + ny * dirY + nz * dirZ;
        float direct = direct(ndotl, shadow);

        // Blinn-Phong 半角向量
        float vlen = (float) Math.sqrt(viewX * viewX + viewY * viewY + viewZ * viewZ);
        if (vlen < 1e-5f) { viewX = 0; viewY = 0; viewZ = -1; vlen = 1; }
        float hx = dirX + viewX / vlen;
        float hy = dirY + viewY / vlen;
        float hz = dirZ + viewZ / vlen;
        float hlen = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
        if (hlen < 1e-5f) { hx = 0; hy = 1; hz = 0; hlen = 1; }
        float ndoth = nx * hx / hlen + ny * hy / hlen + nz * hz / hlen;

        float rim = 1f - Math.abs(nz);
        rim = rim * rim;

        // 母星反射光：只有被食/暗面才明显，正对母星一侧更亮
        float refl = 0;
        if (reflStrength > 0 && (reflX != 0 || reflY != 0 || reflZ != 0)) {
            float rl = (float) Math.sqrt(reflX * reflX + reflY * reflY + reflZ * reflZ);
            if (rl > 1e-5f) {
                float ndotr = nx * reflX / rl + ny * reflY / rl + nz * reflZ / rl;
                refl = Math.max(0f, ndotr) * reflStrength;
            }
        }

        out.set(direct,
                specular(ndoth, shadow) * (1f - refl),
                rimWarm(ndotl, rim, shadow),
                rimCool(ndotl, rim),
                shadowBlue(direct),
                refl);
    }

    /**
     * 统一的颜色合成：albedo 与光照结果混合，输出到 outR/outG/outB（0..1）。
     * 这是全局唯一的一套表面着色公式。
     */
    public void colorize(float[] albedo, SurfaceLight sl, float[] out) {
        float d = sl.direct;
        float inv = 1f - d;
        // 母星反射光：用母星同色系暖光轻微提亮暗面
        float reflR = albedo[0] * sl.reflected * 0.5f;
        float reflG = albedo[1] * sl.reflected * 0.4f;
        float reflB = albedo[2] * sl.reflected * 0.3f;
        float r = albedo[0] * d + DARK_R * inv + sl.shadowBlue * 0.20f + reflR;
        float g = albedo[1] * d + DARK_G * inv + sl.shadowBlue * 0.30f + reflG;
        float b = albedo[2] * d + DARK_B * inv + sl.shadowBlue + reflB;
        r += 0.60f * sl.rimWarm + 0.04f * sl.rimCool + sl.specular;
        g += 0.35f * sl.rimWarm + 0.08f * sl.rimCool + sl.specular;
        b += 0.12f * sl.rimWarm + 0.20f * sl.rimCool + sl.specular;
        out[0] = Math.min(1f, r);
        out[1] = Math.min(1f, g);
        out[2] = Math.min(1f, b);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
