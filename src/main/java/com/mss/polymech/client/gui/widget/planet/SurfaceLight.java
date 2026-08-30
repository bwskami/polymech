package com.mss.polymech.client.gui.widget.planet;

/**
 * 一个表面点求值后的光照结果（无分配，直接复用）。
 * 由 {@link PlanetLighting#evaluate} 填充。
 */
public final class SurfaceLight {
    /** 直射光 0..1（已包含太阳强度与阴影衰减，不含环境光）。 */
    public float direct;
    /** Blinn-Phong 高光强度。 */
    public float specular;
    /** 受光面边缘光。 */
    public float rimWarm;
    /** 背光面边缘光。 */
    public float rimCool;
    /** 暗部冷色量。 */
    public float shadowBlue;
    /** 母星反射光（地照/木星照），让被食卫星不进入死黑。 */
    public float reflected;
    /** 自反射（bounce）：受光半球漫反射到背光半球的光，携带星球自身 albedo 色。 */
    public float bounce;

    public SurfaceLight() {}

    public void set(float direct, float specular, float rimWarm, float rimCool, float shadowBlue, float reflected) {
        set(direct, specular, rimWarm, rimCool, shadowBlue, reflected, 0f);
    }

    public void set(float direct, float specular, float rimWarm, float rimCool, float shadowBlue, float reflected, float bounce) {
        this.direct = direct;
        this.specular = specular;
        this.rimWarm = rimWarm;
        this.rimCool = rimCool;
        this.shadowBlue = shadowBlue;
        this.reflected = reflected;
        this.bounce = bounce;
    }
}
