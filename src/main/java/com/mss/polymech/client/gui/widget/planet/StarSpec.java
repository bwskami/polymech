package com.mss.polymech.client.gui.widget.planet;

/**
 * 恒星光谱参数：决定恒星表面颜色、半径、光度。
 * 用于程序化生成不同光谱型的恒星（真实恒星系目录使用）。
 */
public final class StarSpec {
    public final String spectralType;
    public final float r, g, b;      // 表面基色（渐变映射冷端基色）
    public final float radius;       // 半径，太阳=1
    public final float luminosity;   // 光度，太阳=1
    public final float mass;         // 质量，太阳=1（用于重力井半径）

    public StarSpec(String spectralType, float r, float g, float b,
                    float radius, float luminosity, float mass) {
        this.spectralType = spectralType;
        this.r = r; this.g = g; this.b = b;
        this.radius = radius; this.luminosity = luminosity; this.mass = mass;
    }

    /** 表面色带冷端（偏橙红）。 */
    public float[] coolColor() {
        return new float[]{Math.min(1f, r), Math.min(1f, g * 0.55f + 0.10f), Math.min(1f, b * 0.35f + 0.02f)};
    }

    /** 表面色带热端（偏白/白热）。 */
    public float[] hotColor() {
        return new float[]{Math.min(1f, r * 0.95f + 0.05f), Math.min(1f, g * 0.90f + 0.10f), Math.min(1f, b * 0.85f + 0.15f)};
    }

    /** 可居住带距离（AU 近似）：0.95~1.37 * sqrt(luminosity)。 */
    public float habitableAU() {
        return (float) (1.0 * Math.sqrt(Math.max(0.01, luminosity)));
    }
}
