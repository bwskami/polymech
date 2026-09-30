package com.mss.polymech.physics;

import org.joml.Vector3d;

/**
 * 牵引枪的<b>弹簧数学</b>（纯函数、零 Minecraft 依赖）—— 抽出来是为了能被离线探针
 * 直接调用验证，而不是"在探针里再抄一遍公式"。
 *
 * <h2>为什么是"目标加速度"而不是直接给力</h2>
 * 力 = 质量 × 加速度，所以把质量乘进调用方（{@code PhysgunItem} 用 {@code body.getMass()}）之后，
 * <b>手感与载荷质量无关</b>：几百 kg 的箱子和几十吨的船，误差 1 格都是 8 格/秒² 的响应。
 * 不乘质量的话两者会差 62.5 倍以上（见 {@code native/jni-smoketest/ThrustCalibrationProbe} 的标定表）。
 *
 * <h2>为什么必须限幅</h2>
 * <ul>
 *   <li>{@link #MAX_ERROR}：不限制误差的话，"抓一下 60 格外的船"第一帧就会给出 480 格/秒²，
 *       等于把船当炮弹打出去；</li>
 *   <li>{@link #MAX_A}：必须 <b>大于行星表面重力</b>（约 32 格/秒²）才提得起东西，
 *       但又不能大到"点一下就飞" —— 取 80（约 2.5 倍重力）。</li>
 * </ul>
 *
 * <h2>稳定性</h2>
 * ω = √Kp ≈ 2.83 rad/s（周期 2.2 秒），阻尼比 ζ = Kd/(2ω) ≈ 0.71（略欠阻尼，1–2 秒收敛）。
 * 物理子步 dt = 0.01 s ⇒ ω·dt ≈ 0.028 ≪ 1，显式积分稳定。
 * 离线判据见 {@code native/jni-smoketest/PhysgunDragProbe.java}。
 */
public final class PhysgunSpring {

    /** 位置增益（1/秒²）：误差 1 格 ⇒ 8 格/秒²。 */
    public static final double KP = 8.0;
    /** 速度阻尼（1/秒）。 */
    public static final double KD = 4.0;
    /** 加速度上限（格/秒²）：必须 > 行星表面重力(~32)。 */
    public static final double MAX_A = 80.0;
    /** 误差上限（格）。 */
    public static final double MAX_ERROR = 32.0;
    /** 力的持续时间（秒）：照参考推进器，= 一个 MC tick（物理 tickTime = 0.01 ⇒ 5 个子步）。 */
    public static final double FORCE_DURATION = 0.05;

    private PhysgunSpring() {
    }

    /**
     * 由"到目标点的误差向量"和"当前速度"算出**已限幅的目标加速度**（方块帧，格/秒²）。
     *
     * @param errorToTarget 目标点 − 刚体位置（会被本方法按 {@link #MAX_ERROR} 截断，<b>不会修改入参</b>）
     * @param velocity      刚体当前线速度（不会被修改）
     * @return 新的向量：{@code clamp(Kp·err − Kd·v, |a| ≤ MAX_A)}
     */
    public static Vector3d accel(Vector3d errorToTarget, Vector3d velocity) {
        double el = errorToTarget.length();
        Vector3d err = new Vector3d(errorToTarget);
        if (el > MAX_ERROR) {
            err.mul(MAX_ERROR / el);
        }
        Vector3d acc = err.mul(KP);
        if (velocity != null) {
            acc.sub(new Vector3d(velocity).mul(KD));
        }
        double al = acc.length();
        if (al > MAX_A) {
            acc.mul(MAX_A / al);
        }
        return acc;
    }

    /** 力 = 质量 × 目标加速度（方块帧力，与推进器的 1000 同一量纲）。 */
    public static Vector3d forceFor(double mass, Vector3d accel) {
        return new Vector3d(accel).mul(mass);
    }
}
