package com.mss.polymech.mps.kelvin.physical;

/**
 * kelvin 物理层的共用常量。
 *
 * <p><b>为什么单独一个类</b>：万有引力常数在 kelvin 里出现两处 —— 一处是
 * {@code SpaceWorld.getGravitationForce} 里硬编码的 {@code 6.6743E-11}，
 * 另一处是 {@code Planet} 取 space 模组配置
 * {@code SpaceModCommonConfig.GRAVITATION_GRAVITATIONAL_CONSTANT}。后者是 space 的配置项
 * （本项目没有那套配置系统），其默认值就是前者。这里收成一个具名常量，
 * 让"两处必须同值"这件事由编译器保证，而不是靠记忆。</p>
 */
public final class KelvinConstants {

    /** 万有引力常数（m³·kg⁻¹·s⁻²）—— 与 space 的默认配置值一致。 */
    public static final double GRAVITATIONAL_CONSTANT = 6.6743E-11;

    private KelvinConstants() {
    }
}
