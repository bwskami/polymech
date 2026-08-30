package com.mss.polymech.client.gui.widget.planet;

/**
 * 二次漫反射光照模型（Bounce Light）。
 * <p>
 * 物理依据：物体受光后自身会产生漫反射，且反射量与受光程度成正比——
 * 亮面（正对光）受光最多、漫反射最强；灰面（晨昏线）次之；暗面几乎不受光、
 * 自身不反射，但会接收到亮面反射过来的光。
 * <p>
 * 注意：这里的"受光程度"指表面朝向带来的受光差异（ndotl/direct），
 * 不是星球到恒星的距离。
 * <p>
 * 与 {@link PlanetLighting}（直射/高光/边缘光）和 {@link ShadowModel}（阴影/母星反照）
 * 职责分离，由 {@link PlanetLighting} 组合持有。
 */
public final class BounceLightModel {

    /** 亮面自身漫散射强度：受光面把部分光向四周散射（亚表面散射近似）。 */
    private static final float FACE_SCATTER_STRENGTH = 0.16f;
    /** 自反射强度：亮面半球把光反射到暗面半球的上限。 */
    private static final float SELF_BOUNCE_STRENGTH = 0.34f;

    /**
     * 亮面自身漫散射：一个面的漫反射量 ∝ 它的受光程度（direct）。
     * <p>
     * 亮面 direct≈1 → 满散射；灰面 direct≈0.4 → 四成；暗面不产生（在背面分支处理）。
     *
     * @param direct 该面的直射光量 0..1（受光程度）
     * @return 散射光强 0..FACE_SCATTER_STRENGTH
     */
    public float faceScatter(float direct) {
        return Math.max(0f, direct) * FACE_SCATTER_STRENGTH;
    }

    /**
     * 自反射：受光半球把自身 albedo 色的光漫反射到背光半球。
     * <p>
     * 来源强度 = 该天体受光半球的受光程度（{@code receivedLight}，
     * 即直射光强度 intensity——整个受光半球的能量来源）。
     * 在目标面的晨昏线处（ndotl ≈ 0⁻）最明显，向暗面深处衰减到 0。
     *
     * @param ndotl         该面法线与光方向的点积（&lt;0 表示背光面）
     * @param receivedLight 天体受光半球的受光强度（直射光 intensity）
     * @return bounce 光强
     */
    public float selfBounce(float ndotl, float receivedLight) {
        if (ndotl >= 0f) return 0f;
        // ndotl=0 → 1，ndotl≤-2/3 → 0：晨昏线附近最强，快速衰减
        float f = Math.max(0f, 1f + ndotl * 1.5f);
        return f * f * receivedLight * SELF_BOUNCE_STRENGTH;
    }
}
