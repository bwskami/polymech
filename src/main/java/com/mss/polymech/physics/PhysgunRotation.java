package com.mss.polymech.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 牵引枪的<b>角伺服数学</b>（纯函数、零 Minecraft 依赖）—— 与 {@link PhysgunSpring} 并列，
 * 抽出来是为了能被离线探针直接调用验证，而不是"在探针里再抄一遍公式"。
 *
 * <h2>照的是参考的哪一半</h2>
 * 参考（{@code Simulated-Project} 的 {@code PhysicsStaffServerHandler.physicsTick}）每个物理 tick
 * 重挂一个 {@code FreeConstraint} 并给 6 个轴设<b>马达</b>：
 * <pre>
 *   // 角轴：目标角 0、带刚度与阻尼 —— 这是一个 PD 伺服
 *   for (ConstraintJointAxis angularAxis : ConstraintJointAxis.ANGULAR)
 *       constraint.setMotor(angularAxis, 0.0, angularStiffness, angularDamping, false, 0.0);
 *   // 线轴：目标点表达在"由 drag orientation 定义的坐标系"里
 *   localGoal.set(playerRelativeGoal).add(eyePos);
 *   orientation.transformInverse(localGoal);
 *   constraint.setMotor(LINEAR_X/Y/Z, localGoal, linearStiffness, linearDamping, false, 0.0);
 * </pre>
 * 所以参考的<b>位置与朝向都走弹簧-阻尼伺服</b>，<b>不是</b> {@code setPose}/{@code setRotation}。
 * 我们线性那半已经有 {@link PhysgunSpring}；这个类补的是<b>角</b>那半，
 * 形式与它逐项对应（见 {@link #angularAccel} 的注释表）。
 *
 * <h2>★ 为什么惯量要补偿（与参考不同，这是刻意的偏离）</h2>
 * 参考给的是<b>固定刚度的马达</b>（{@code angularStiffness = 10000}、{@code angularDamping = 850}），
 * 由它的物理引擎按惯量缩放 —— 于是<b>大船转得慢</b>。若我们照抄"固定力矩"，
 * 离线探针实测：10 倍惯量的体阻尼比从 0.71 掉到 0.22，3 秒后还差 <b>37.75°</b>
 * （见 {@code PhysgunRotationProbe} 的 D 组）—— 那会变成"能拖动重船、却转不动它"，
 * 与线性那半"手感与载荷质量无关"（{@link PhysgunSpring#forceFor} 的类注释）
 * 自相矛盾，枪的两个自由度手感会分裂。
 *
 * <p>所以这里和线性一样<b>把惯量乘进调用方</b>：增益是"目标角加速度"（rad/s²），
 * 调用方再乘惯量得力。惯量用 {@link #estimateInertia} 估，
 * 公式与建体时用的 {@link PhysicsBodyMass#apply} <b>同一个</b>（{@code m·s²/6}），
 * 不另发明一套。<b>代价</b>：惯量只用于把增益归一化，是量级估计，
 * 不追求与 Rapier 内部值逐位相等（原生层没有惯量读取接口）。</p>
 *
 * <h2>稳定性</h2>
 * {@code ω = √KP = 2.83 rad/s}（周期 2.2 秒）、阻尼比 {@code ζ = KD/(2ω) = 0.71}
 * （略欠阻尼，2% 整定时间约 2.0 秒）—— 与 {@link PhysgunSpring} 同一组手感，
 * 且<b>与载荷惯量无关</b>。物理子步 {@code dt = 0.01 s ⇒ ω·dt ≈ 0.028 ≪ 1}，显式积分稳定。
 */
public final class PhysgunRotation {

    /** 角位置增益（1/秒²）：误差 1 rad ⇒ 8 rad/秒²。与 {@link PhysgunSpring#KP} 同一个数。 */
    public static final double KP = PhysgunSpring.KP;
    /** 角速度阻尼（1/秒）：与 {@link PhysgunSpring#KD} 同一个数。 */
    public static final double KD = PhysgunSpring.KD;

    /**
     * 角加速度上限（rad/秒²）。
     *
     * <p>与线性 {@link PhysgunSpring#MAX_A} 同理：没有上限时，抓一个"朝反方向 180°"的体
     * 第一帧就会给 {@code KP·π ≈ 25} rad/秒²；取 20 略小于它，
     * 于是极限情况被削一点、正常误差（&lt; 2.5 rad）完全不受影响。
     * 注意它<b>与惯量无关</b>（因为已经补偿过），所以轻重体的上限一致。</p>
     */
    public static final double MAX_ANG_ACCEL = 20.0;

    /** 角误差上限（弧度）：最短弧天然 ≤ π，这里显式夹一下，防止未归一化输入导致爆值。 */
    public static final double MAX_ANGLE_ERROR = Math.PI;

    private PhysgunRotation() {
    }

    /**
     * 从"当前朝向"到"目标朝向"的<b>最短弧</b>轴角。
     *
     * <p>约定：{@code q_err = target · current⁻¹}（世界系误差），若 {@code w < 0} 逐分量取反
     * —— 四元数双重覆盖（{@code q} 与 {@code -q} 是同一个旋转），不取反就会走"长弧"，
     * 表现是"物体朝反方向绕一大圈"。</p>
     *
     * @param current 当前朝向（<b>不会被修改</b>）
     * @param target  目标朝向（<b>不会被修改</b>）
     * @return {@code {ax, ay, az, angle}}：轴已归一化，{@code angle ∈ [0, π]}；
     *         两个朝向相同（或输入退化）时返回 {@code {0,0,0,0}}
     */
    public static double[] errorAxisAngle(Quaterniond current, Quaterniond target) {
        if (current == null || target == null
                || !current.isFinite() || !target.isFinite()
                || current.lengthSquared() < 1.0e-12 || target.lengthSquared() < 1.0e-12) {
            return new double[]{0.0, 0.0, 0.0, 0.0};
        }
        Quaterniond c = new Quaterniond(current).normalize();
        Quaterniond t = new Quaterniond(target).normalize();
        Quaterniond err = new Quaterniond(t).mul(c.conjugate());
        err.normalize();
        if (err.w < 0.0) {
            // 双重覆盖：取 -q 走最短弧
            err.set(-err.x, -err.y, -err.z, -err.w);
        }
        double sinHalf = Math.sqrt(Math.max(0.0, err.x * err.x + err.y * err.y + err.z * err.z));
        double angle = 2.0 * Math.atan2(sinHalf, err.w);
        if (angle > MAX_ANGLE_ERROR) {
            angle = MAX_ANGLE_ERROR;
        }
        if (sinHalf < 1.0e-9 || angle < 1.0e-9) {
            return new double[]{0.0, 0.0, 0.0, 0.0};
        }
        double inv = 1.0 / sinHalf;
        return new double[]{err.x * inv, err.y * inv, err.z * inv, angle};
    }

    /**
     * 由"最短弧轴角"和"当前角速度"算出**已限幅的目标角加速度**（rad/秒²）。
     *
     * <p>与 {@link PhysgunSpring#accel} 逐项对应，量纲从"格/秒²"换成"弧度/秒²"：</p>
     * <pre>
     *   PhysgunSpring.accel :  a = clamp(Kp·err − Kd·v, |a| ≤ MAX_A)     → 再乘质量得力
     *   本方法              :  α = clamp(Kp·axis·angle − Kd·ω, |α| ≤ MAX_ANG_ACCEL) → 再乘惯量得力矩
     * </pre>
     *
     * @param axisAngle {@link #errorAxisAngle} 的返回值（<b>不会被修改</b>）
     * @param angVel    刚体当前角速度（rad/s；{@code null} 视作 0，<b>不会被修改</b>）
     * @return 新的向量：应经 {@link #torqueFor} 乘惯量后作为力矩冲量施加
     */
    public static Vector3d angularAccel(double[] axisAngle, Vector3d angVel) {
        if (axisAngle == null || axisAngle.length < 4) {
            return new Vector3d();
        }
        double angle = axisAngle[3];
        Vector3d acc = new Vector3d(axisAngle[0], axisAngle[1], axisAngle[2]).mul(KP * angle);
        if (angVel != null) {
            acc.sub(new Vector3d(angVel).mul(KD));
        }
        double len = acc.length();
        if (len > MAX_ANG_ACCEL) {
            acc.mul(MAX_ANG_ACCEL / len);
        }
        return acc;
    }

    /** 力矩 = 惯量 × 目标角加速度（N·m）；与 {@link PhysgunSpring#forceFor} 对称。 */
    public static Vector3d torqueFor(double inertia, Vector3d angularAccel) {
        return new Vector3d(angularAccel).mul(inertia);
    }

    /**
     * 估转动惯量（kg·m²）：{@code m·s²/6}，{@code s = ∛blocks}。
     *
     * <p><b>必须与 {@link PhysicsBodyMass#apply} 里那句保持一致</b>
     * （那里是 {@code inertia = extra * s * s / 6.0}）—— 同一套近似，
     * 否则"补的质量惯量"与"伺服以为的惯量"会对不上。
     * 离线探针 {@code PhysgunRotationProbe} 与 {@code check-artifact.ps1} 的源码契约会钉住它。</p>
     *
     * @param mass   刚体总质量（kg，取 {@code bodyGetMass}）
     * @param blocks 该体的方块数（≥1）
     */
    public static double estimateInertia(double mass, int blocks) {
        double s = Math.cbrt(Math.max(1, blocks));
        return mass * s * s / 6.0;
    }

    /** 力矩冲量（N·m·s）= 力矩 × 持续时间（默认 {@link PhysgunSpring#FORCE_DURATION}，给按 MC tick 施力的路径用）。 */
    public static Vector3d torqueImpulse(Vector3d torque) {
        return torqueImpulse(torque, PhysgunSpring.FORCE_DURATION);
    }

    /**
     * 力矩冲量（N·m·s）= 力矩 × 持续时间 —— <b>持续时间由调用方给</b>。
     *
     * <p>施力节奏决定这个参数：按 MC tick 施力给 0.05，按 100Hz 物理子步施力给
     * {@code PhysicsStepThread.STEP_SECONDS}（0.01）——同一份力矩、不同摊法，
     * 平均效果一致。冲量口径与 {@link PhysgunSpring#forceFor} 对称，公式只有这一处。</p>
     */
    public static Vector3d torqueImpulse(Vector3d torque, double duration) {
        return new Vector3d(torque).mul(duration);
    }
}
