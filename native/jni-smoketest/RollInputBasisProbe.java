import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * <b>输入基底 vs 相机屏幕基底</b>对表 —— 定位"滚转 ±180 时左右键反向"。
 *
 * <p>用法：{@code java -cp &lt;classes&gt;;&lt;joml&gt; RollInputBasisProbe}（纯 joml，**不需要 MC**）。</p>
 *
 * <h2>为什么不直接 new SpacePlayerData</h2>
 * 它带静态 {@code DeferredRegister}，类初始化要求整套 FML 加载环境
 * （实测：{@code Not bootstrapped} → bootstrap 后又 {@code LoadingModList.get() is null}），
 * 离线探针进不去。所以这里**按它的约定解析构造**那组基底：
 * <pre>
 *   facing = (sin(yawRad)·cos(pitch), -sin(pitch), cos(yawRad)·cos(pitch))     // initFromVanilla
 *   left   = Ry(-(yaw+180)) · Rx(-pitch) · (1,0,0)                             // initFromVanilla
 *   roll   : left.rotateAxis(roll, facing)                                     // rollBody
 *   A 的方向 = screenLeft = -left                                              // SpaceInputRotationMixin
 *   竖直方向 = screenUp   = facing × screenLeft
 * </pre>
 * ⚠️ 这几条**复刻**由 {@code check-artifact.ps1} 的源码契约钉住（本项目的规矩：探针里复刻的公式
 * 必须钉到真实现），免得哪天 {@code SpacePlayerData} 改了约定而探针还按老的算。
 *
 * <h2>相机那侧不是复刻——是 MC 的字节码</h2>
 * {@code net.minecraft.client.Camera.setRotation(FFF)}（已 {@code javap -c} 核对）：
 * <pre>
 *   this.rotation.rotationYXZ((float)Math.PI - yRot * DEG2RAD, -xRot * DEG2RAD, -roll * DEG2RAD);
 *   this.left = LEFT.rotate(this.rotation, this.left);   // LEFT = (-1,0,0)
 *   this.up   = UP.rotate(this.rotation, this.up);       // UP   = (0,1,0)
 * </pre>
 * {@code yaw/pitch/roll} 由 {@code SpaceCameraMixin:128-155} 从基底算出（同一份复刻）。
 *
 * <h2>判据</h2>
 * 每个姿态都要 {@code dot(输入A方向, 相机左) ≈ +1}（= 按 A 确实是往屏幕左走）与
 * {@code dot(输入竖直, 相机上) ≈ +1}。前者就是用户报的那条；后者平时看不出来（走路时竖直输入恒为 0），
 * 所以必须单独钉住。
 */
public class RollInputBasisProbe {

    private static final double TORAD = Math.PI / 180.0;
    private static final double DEG = 180.0 / Math.PI;

    private static int passed;
    private static int failed;

    private static void check(boolean ok, String label, String detail) {
        if (ok) {
            passed++;
            System.out.printf("  [PASS] %-44s %s%n", label, detail);
        } else {
            failed++;
            System.out.printf("  [FAIL] %-44s %s%n", label, detail);
        }
    }

    /** 按 SpacePlayerData 的约定构造基底，并施加 roll。 */
    private static void buildBasis(double yawDeg, double pitchDeg, double rollDeg,
                                   Vector3d facingOut, Vector3d leftOut) {
        double pitchRad = pitchDeg * TORAD;
        double yawRad = -yawDeg * TORAD;
        facingOut.set(Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad));
        leftOut.set(1, 0, 0).rotateX(-pitchRad).rotateY(-(yawDeg + 180) * TORAD);
        // orthonormalize()
        leftOut.normalize();
        double d = leftOut.dot(facingOut);
        leftOut.sub(facingOut.x * d, facingOut.y * d, facingOut.z * d);
        leftOut.normalize();
        if (rollDeg != 0.0) {
            leftOut.rotateAxis(rollDeg * TORAD, facingOut.x, facingOut.y, facingOut.z);
            leftOut.normalize();
            double d2 = leftOut.dot(facingOut);
            leftOut.sub(facingOut.x * d2, facingOut.y * d2, facingOut.z * d2);
            leftOut.normalize();
        }
    }

    /**
     * 相机侧：SpaceCameraMixin 的 roll 计算 + MC 的 setRotation。
     *
     * @return {camLeftX,Y,Z, camUpX,Y,Z, rollDeg}
     */
    private static double[] cameraBasis(Vector3d facing, Vector3d left) {
        double pitch = -Math.asin(Math.max(-1.0, Math.min(1.0, facing.y))) * DEG;
        double yaw = -Math.atan2(facing.x, facing.z) * DEG;

        // SpaceCameraMixin:133-144
        Vector3d refLeft = new Vector3d(1, 0, 0);
        refLeft.rotateX(-pitch * TORAD);
        refLeft.rotateY(-(yaw + 180) * TORAD);
        double dd = refLeft.dot(facing);
        refLeft.sub(facing.x * dd, facing.y * dd, facing.z * dd);
        if (refLeft.lengthSquared() > 1e-12) {
            refLeft.normalize();
        }
        // ⚠️ 前导负号**不能漏**：SpaceCameraMixin:142 写的是 `-Math.atan2(...)`。
        //    本探针第一版就是漏了它，凭空造出"偏差 = 2×roll"的假结论。
        //    （同类事故第 4 次：-q90 / 浮点取模 / getLong / 这个。）
        double rollDeg = -Math.atan2(left.cross(refLeft, new Vector3d()).dot(facing),
                left.dot(refLeft)) * DEG;
        // 注：生产代码随后还会做一次 `polymech$closestAngle(roll, lastRoll)` 的等价角归一。
        //     那只会给 roll 加减 360k，而 Rz(θ) 是 360° 周期的 ⇒ **不影响相机朝向**，
        //     故本探针无需复刻（它只影响存下来的 lastRoll 数值）。

        // MC Camera.setRotation(FFF)
        Quaternionf q = new Quaternionf().rotationYXZ(
                (float) (Math.PI - yaw * TORAD), (float) (-pitch * TORAD), (float) (-rollDeg * TORAD));
        Vector3f camLeft = q.transform(new Vector3f(-1.0f, 0.0f, 0.0f));
        Vector3f camUp = q.transform(new Vector3f(0.0f, 1.0f, 0.0f));
        return new double[]{camLeft.x, camLeft.y, camLeft.z, camUp.x, camUp.y, camUp.z, rollDeg};
    }

    /** 直接按 MC 的公式算相机左右/上下（给定 yaw/pitch/roll）。 */
    private static void mcCam(double yawDeg, double pitchDeg, double rollDeg,
                              Vector3d leftOut, Vector3d upOut) {
        Quaternionf q = new Quaternionf().rotationYXZ(
                (float) (Math.PI - yawDeg * TORAD),
                (float) (-pitchDeg * TORAD),
                (float) (-rollDeg * TORAD));
        Vector3f cl = q.transform(new Vector3f(-1.0f, 0.0f, 0.0f));
        Vector3f cu = q.transform(new Vector3f(0.0f, 1.0f, 0.0f));
        leftOut.set(cl.x, cl.y, cl.z);
        upOut.set(cu.x, cu.y, cu.z);
    }

    /**
     * B 组：<b>正面视角</b>（vanilla 在 {@code setup} 末尾会自己
     * {@code setRotation(yRot+180, -xRot)} 把镜头翻到人物正面）。
     *
     * <p>看向背后 = 绕"头的上轴"转 180° ⇒ 不变量是：<b>相机上 = 头的上（不变）、相机左 = −头的左（取反）</b>。
     * 而"头绕视线滚了 θ"在这个反向视图里应当变成"绕反向视线滚 −θ" —— 但**这个负号我不靠推导定**：
     * 两个候选 {@code +roll} / {@code -roll} 都算一遍，看哪个满足不变量，由程序给答案。</p>
     */
    private static void groupFrontView(double[] yaws, double[] pitches, double[] rolls) {
        int onlyPlus = 0;
        int onlyMinus = 0;
        int both = 0;
        int neither = 0;
        for (double yaw : yaws) {
            for (double pitch : pitches) {
                for (double roll : rolls) {
                    Vector3d facing = new Vector3d();
                    Vector3d left = new Vector3d();
                    buildBasis(yaw, pitch, roll, facing, left);
                    Vector3d screenLeftHead = new Vector3d(left).negate();
                    Vector3d screenUpHead = new Vector3d(facing).cross(screenLeftHead, new Vector3d()).normalize();

                    Vector3d camL = new Vector3d();
                    Vector3d camU = new Vector3d();
                    double fYaw = yaw + 180.0;
                    double fPitch = -pitch;

                    mcCam(fYaw, fPitch, roll, camL, camU);
                    boolean plusOk = camU.dot(screenUpHead) > 0.9999
                            && camL.dot(new Vector3d(screenLeftHead).negate()) > 0.9999;
                    mcCam(fYaw, fPitch, -roll, camL, camU);
                    boolean minusOk = camU.dot(screenUpHead) > 0.9999
                            && camL.dot(new Vector3d(screenLeftHead).negate()) > 0.9999;

                    if (plusOk && minusOk) {
                        both++;
                    } else if (plusOk) {
                        onlyPlus++;
                    } else if (minusOk) {
                        onlyMinus++;
                    } else {
                        neither++;
                    }
                }
            }
        }
        System.out.printf(java.util.Locale.ROOT,
                "%nB 组（正面视角）：只有 +roll 成立 %d 个，只有 -roll 成立 %d 个，两者都成立 %d 个，都不成立 %d 个%n",
                onlyPlus, onlyMinus, both, neither);
        // 「两者都成立」的 75 个是 roll=0 / ±180 那类**本来就不区分** +roll 与 -roll 的退化姿态
        // （两者只差 360k）⇒ 它们不参与判定，只看"能区分的那 45 个"。
        if (neither == 0 && onlyPlus == 0 && onlyMinus > 0) {
            System.out.println("  ⇒ 判定：正面视角下 roll 必须**取反**（-roll）");
        } else if (neither == 0 && onlyMinus == 0 && onlyPlus > 0) {
            System.out.println("  ⇒ 判定：正面视角下 roll **保持不变**（+roll）");
        } else {
            System.out.println("  ⇒ 判定：无法唯一确定（见上面的计数）—— 不许猜，需另行取证");
        }
        check(neither == 0 && (onlyPlus == 0 || onlyMinus == 0),
                "3 正面视角下 roll 的符号可唯一确定（不变量成立）",
                String.format(java.util.Locale.ROOT, "可区分姿态里：+roll=%d  -roll=%d（都成立=%d = 退化姿态）",
                        onlyPlus, onlyMinus, both));
    }

    public static void main(String[] args) {
        double[] yaws = {0, 90, -90, 180, 37};
        double[] pitches = {0, 45, -60};
        double[] rolls = {0, 30, 90, -90, 179.9, -179.9, 180, -180};

        int cases = 0;
        int leftBad = 0;
        int upBad = 0;
        double worstLeft = 1.0;
        double worstUp = 1.0;
        String firstLeftFail = null;
        String firstUpFail = null;

        System.out.println(" yaw  pitch    roll | dot(输入A,相机左) dot(输入竖,相机上)  camRoll");
        for (double yaw : yaws) {
            for (double pitch : pitches) {
                for (double roll : rolls) {
                    Vector3d facing = new Vector3d();
                    Vector3d left = new Vector3d();
                    buildBasis(yaw, pitch, roll, facing, left);
                    Vector3d inLeft = new Vector3d(left).negate();
                    Vector3d inUp = new Vector3d(facing).cross(inLeft, new Vector3d()).normalize();
                    double[] cam = cameraBasis(facing, left);

                    double dl = inLeft.x * cam[0] + inLeft.y * cam[1] + inLeft.z * cam[2];
                    double du = inUp.x * cam[3] + inUp.y * cam[4] + inUp.z * cam[5];
                    cases++;
                    worstLeft = Math.min(worstLeft, dl);
                    worstUp = Math.min(worstUp, du);
                    boolean lBad = dl < 0.9999;
                    boolean uBad = du < 0.9999;
                    if (lBad) {
                        leftBad++;
                        if (firstLeftFail == null) {
                            firstLeftFail = String.format(java.util.Locale.ROOT,
                                    "yaw=%.0f pitch=%.0f roll=%.0f ⇒ dot=%.4f", yaw, pitch, roll, dl);
                        }
                    }
                    if (uBad) {
                        upBad++;
                        if (firstUpFail == null) {
                            firstUpFail = String.format(java.util.Locale.ROOT,
                                    "yaw=%.0f pitch=%.0f roll=%.0f ⇒ dot=%.4f", yaw, pitch, roll, du);
                        }
                    }
                    if (lBad || uBad || Math.abs(roll) >= 179.0) {
                        System.out.printf(java.util.Locale.ROOT,
                                "%4.0f %6.0f %7.1f | %15.4f %17.4f %8.1f%s%n",
                                yaw, pitch, roll, dl, du, cam[6],
                                (lBad ? "   ← 左右反!" : "") + (uBad ? "   ← 竖直反!" : ""));
                    }
                }
            }
        }

        System.out.printf(java.util.Locale.ROOT,
                "%n共 %d 个姿态：左右不一致 %d，竖直不一致 %d；最差点积 左右 %.4f / 竖直 %.4f%n",
                cases, leftBad, upBad, worstLeft, worstUp);

        check(leftBad == 0, "1 按 A 永远等于相机屏幕左（含任意 roll）",
                leftBad == 0 ? "全部 " + cases + " 个姿态一致"
                        : leftBad + "/" + cases + " 不一致，首个：" + firstLeftFail);
        check(upBad == 0, "2 竖直输入永远等于相机屏幕上",
                upBad == 0 ? "全部 " + cases + " 个姿态一致"
                        : upBad + "/" + cases + " 不一致，首个：" + firstUpFail);

        groupFrontView(yaws, pitches, rolls);

        System.out.printf("%n=== RollInputBasisProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
