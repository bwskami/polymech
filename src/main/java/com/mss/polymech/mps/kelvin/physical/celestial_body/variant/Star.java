package com.mss.polymech.mps.kelvin.physical.celestial_body.variant;

import com.mss.polymech.mps.kelvin.physical.celestial_body.CelestialBody;
import net.minecraft.network.FriendlyByteBuf;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 恒星 —— <b>与 {@code org.cn_grass_block.kelvin.physical.celestial_body.variant.Star}
 * 同形</b>的自有实现（clean-room）。
 *
 * <h2>职责</h2>
 * 在 {@link CelestialBody} 之上只加一件事：<b>色温</b>，并由它推出渲染用的 RGB。
 *
 * <h2>为什么把"色温 → RGB"放在这里（照 space 0.1.3）</h2>
 * 恒星的颜色不是美术拍脑袋，而是<b>物理量</b>（表面温度）的派生物：数据包里给的是开尔文，
 * 颜色由黑体辐射近似算出。放在这里意味着"改数据包的温度，星星颜色跟着变"，
 * 而不是颜色与温度两处各写一份、迟早不一致。
 *
 * <p>{@link #TemperatureToRGB} 用的是经典的黑体色温近似（分段拟合 + 对数项）：
 * 1000K 红、6600K 附近偏白、≥6600K 蓝。温度先夹到 [1000, 40000] K —— 超出这段拟合就不可信。</p>
 */
public class Star extends CelestialBody {

    private double temperature = 0.0;
    /** 由 {@link #temperature} 推出的线性 RGB（0..1）。 */
    public double[] RGB = new double[]{1.0, 1.0, 1.0};

    public Star(String level, String name, Vector3d pos, Quaterniond rotate, double radius, double temperature) {
        super(level, name, pos, rotate, radius);
        this.setTemperature(temperature);
    }

    /** 设色温并**同步刷新** {@link #RGB}（两者不能各写一份，见类注释）。 */
    public void setTemperature(double temperature) {
        this.temperature = temperature;
        this.RGB = TemperatureToRGB(temperature);
    }

    @Override
    public void encode(FriendlyByteBuf buffer) {
        super.encode(buffer);
        buffer.writeDouble(this.temperature);
    }

    public static Star decode(FriendlyByteBuf buffer) {
        CelestialBody base = CelestialBody.decode(buffer);
        return new Star(base.level, base.getName(), base.getPos(), base.getRotate(),
                base.getRadius(), buffer.readDouble());
    }

    /**
     * 色温（K）→ 线性 RGB（0..1）。温度夹到 [1000, 40000] K —— 拟合区间之外不可信。
     */
    public static double[] TemperatureToRGB(double kelvin) {
        kelvin = Math.max(1000.0, Math.min(40000.0, kelvin));
        kelvin /= 100.0;
        double red;
        if (kelvin <= 66.0) {
            red = 255.0;
        } else {
            red = 329.698727446 * Math.pow(kelvin - 60.0, -0.1332047592);
            red = Math.max(0.0, Math.min(255.0, red));
        }

        double green;
        if (kelvin <= 66.0) {
            green = 99.4708025861 * Math.log(kelvin) - 161.1195681661;
        } else {
            green = 288.1221695283 * Math.pow(kelvin - 60.0, -0.0755148492);
        }
        green = Math.max(0.0, Math.min(255.0, green));

        double blue;
        if (kelvin >= 66.0) {
            blue = 255.0;
        } else if (kelvin <= 19.0) {
            blue = 0.0;
        } else {
            blue = 138.5177312231 * Math.log(kelvin - 10.0) - 305.0447927307;
            blue = Math.max(0.0, Math.min(255.0, blue));
        }

        return new double[]{red / 255.0, green / 255.0, blue / 255.0};
    }

    public double getTemperature() {
        return this.temperature;
    }
}
