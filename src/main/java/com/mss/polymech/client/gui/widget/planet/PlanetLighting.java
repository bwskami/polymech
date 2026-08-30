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
    public static final float AMBIENT = 0.14f;
    /** 高光参数。 */
    private static final float SPEC_POWER = 90f;
    private static final float SPEC_STRENGTH = 0.10f;

    /**
     * "亮纯暗灰"光照模型的两端色。
     * <p>
     * 光的颜色随受光程度在两者间插值——<b>明度与纯度绑定在一起变化</b>：
     * <ul>
     *   <li>受光多 → 高纯暖色恒星光（亮、纯）</li>
     *   <li>受光少 → 暗灰（暗、灰，纯度自然低）</li>
     * </ul>
     * 最终色 = albedo × lightColor(t)，没有独立的亮度增益、没有加色高光，
     * 因此亮部只会往"高纯暖色"偏，数学上不可能冲白（任何 t 下光色都是 R 占优）。
     */
    /** 亮端：高纯度暖色恒星光。 */
    private static final float SUN_R = 1.00f, SUN_G = 0.52f, SUN_B = 0.20f;
    /** 暗端：暗灰（微冷）。 */
    private static final float GRAY_R = 0.12f, GRAY_G = 0.14f, GRAY_B = 0.18f;

    /**
     * 染色强度随固有色饱和度自适应：
     * <ul>
     *   <li>高饱和（海洋/木星色带等有色星球）→ 趋近 {@link #TINT_MAX}，容易被染色</li>
     *   <li>低饱和（月球/水星等灰色星球）→ 趋近 {@link #TINT_MIN}，保住灰的本色</li>
     * </ul>
     * 避免纯乘法染色把灰色天体 100% 吃成光色、丢失辨识度。
     */
    private static final float TINT_MIN = 0.25f;
    private static final float TINT_MAX = 0.85f;

    private final BounceLightModel bounceModel = new BounceLightModel();
    private float dirX, dirY, dirZ = 1f;
    private float intensity = 1f;

    public float dirX() { return dirX; }
    public float dirY() { return dirY; }
    public float dirZ() { return dirZ; }
    public float intensity() { return intensity; }
    /** 环境光基线（常量）。注意：漫反射差异由各面自身受光程度决定，见 {@link BounceLightModel}。 */
    public float ambient() { return AMBIENT; }
    public BounceLightModel bounceModel() { return bounceModel; }

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

        // 漫反射 ∝ 受光程度：亮面自身散射，暗面接收受光半球的反射光
        float bounce = ndotl >= 0f
                ? bounceModel.faceScatter(direct) * (1f - shadow)
                : bounceModel.selfBounce(ndotl, intensity) * (1f - shadow * 0.5f);
        out.set(direct,
                specular(ndoth, shadow) * (1f - refl),
                rimWarm(ndotl, rim, shadow),
                rimCool(ndotl, rim),
                shadowBlue(direct),
                refl,
                bounce);
    }

    /**
     * 统一的颜色合成：乘法有色光照（Mindustry planet.frag 同款原理）。
     * <p>
     * <pre>
     * final = albedo × lightAmount × lightColor + specular
     * </pre>
     * <ul>
     *   <li><b>lightAmount</b>（标量）：受光程度 = 直射 + 自反射 + 母星反照，
     *       映射到 [AMBIENT, 1+]，只控制明度。</li>
     *   <li><b>lightColor</b>（RGB）：光的颜色——受光面按太阳暖白染色，
     *       背光面按冷天光染色。乘法保证色相只会向光色方向自然偏移，
     *       绝不会出现"蓝面变绿"这类加色污染。</li>
     *   <li><b>specular</b>：镜面反射物理上反射的是光源本色（白），故加色合理。</li>
     * </ul>
     */
    /**
     * 光的颜色：按受光程度在冷天光(AMB)与太阳暖金(SUN)之间插值。
     * <p>单一来源，供 {@link #colorize} 与云层/其它图层共用。
     *
     * @param lit 受光程度 0(纯背光)..1+(正对光)
     * @param out 输出 RGB 光色
     */
    public void lightColor(float lit, float[] out) {
        float t = clamp(lit, 0f, 1f);
        out[0] = GRAY_R + (SUN_R - GRAY_R) * t;
        out[1] = GRAY_G + (SUN_G - GRAY_G) * t;
        out[2] = GRAY_B + (SUN_B - GRAY_B) * t;
    }

    public void colorize(float[] albedo, SurfaceLight sl, float[] out) {
        // ---- 受光程度（标量）：直射 + 自反射 + 母星反照 ----
        float lit = clamp(sl.direct + sl.bounce + sl.reflected * 0.6f, 0f, 1.4f);
        float t = clamp(lit, 0f, 1f);

        // ---- 亮纯暗灰：光色随受光程度 暗灰 → 高纯暖色，明度与纯度绑定 ----
        float lr = GRAY_R + (SUN_R - GRAY_R) * t;
        float lg = GRAY_G + (SUN_G - GRAY_G) * t;
        float lb = GRAY_B + (SUN_B - GRAY_B) * t;

        // ---- 按固有色饱和度自适应染色 ----
        // 固有色饱和度（HSV 的 S）：越高越容易被光染色
        float maxC = Math.max(albedo[0], Math.max(albedo[1], albedo[2]));
        float minC = Math.min(albedo[0], Math.min(albedo[1], albedo[2]));
        float sat = maxC > 1e-5f ? (maxC - minC) / maxC : 0f;
        float tint = TINT_MIN + (TINT_MAX - TINT_MIN) * sat;

        // 完全染色：albedo × 光色
        float tr = albedo[0] * lr, tg = albedo[1] * lg, tb = albedo[2] * lb;
        // 中性基准：albedo × 光色亮度（只变明度、不动色相，固有色全保留）
        float ll = (lr + lg + lb) / 3f;
        float nr = albedo[0] * ll, ng = albedo[1] * ll, nb = albedo[2] * ll;
        // 按饱和度决定的比例混合
        out[0] = Math.min(1f, nr + (tr - nr) * tint);
        out[1] = Math.min(1f, ng + (tg - ng) * tint);
        out[2] = Math.min(1f, nb + (tb - nb) * tint);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
