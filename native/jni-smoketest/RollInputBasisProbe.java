import com.mss.polymech.space.SpacePlayerData;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Method;

/**
 * <b>输入基底 vs 原版相机屏幕基底</b>对表 —— 定位"滚转 ±180 时左右键反向"。
 *
 * <p>用法：{@code java @&lt;argfile&gt;}（需要**全量 classpath**：要加载真的
 * {@code net.minecraft.client.Camera}）。</p>
 *
 * <h2>★ 为什么这一版和第一版完全不同（第一版是**死判据**）</h2>
 * 第一版没法 {@code new SpacePlayerData}（那个类当时带静态 {@code DeferredRegister}，
 * 类初始化要整套 FML 环境），于是它**按约定复刻**了 {@code initFromVanilla}/{@code rollBody}/
 * 输入映射/相机三元组的公式 —— <b>两边都是复刻</b>，复刻对复刻必然自洽，永远 3/3。
 * 结果就是：用户实机反馈"滚转还是反的"时，探针**仍然全过**。
 *
 * <p>现在两边都是真实现：</p>
 * <ul>
 *   <li>输入侧：{@code SpacePlayerData.viewFrame(...)} —— 正是 {@code SpaceInputRotationMixin}
 *       用的那一步（局部 +X = 屏幕左、+Y = 屏幕上、+Z = 视线）；</li>
 *   <li>相机侧：{@code SpacePlayerData.mcRotation(...)} —— 正是 {@code SpaceCameraMixin}
 *       交给原版的那三元组；再喂给**真的** {@code net.minecraft.client.Camera}
 *       （反射调它 protected 的 {@code setRotation(FFF)}），读它渲染时真正用的
 *       {@code getLeftVector()/getUpVector()/getLookVector()}。</li>
 * </ul>
 *
 * <h2>判据（与"玩家看到什么"直接对应，能 FAIL）</h2>
 * <pre>
 *   dot(按 A 得到的世界方向, 相机左) ≈ +1
 *   dot(按"抬头"得到的方向, 相机上)  ≈ +1
 *   dot(视线,              相机看)  ≈ +1
 * </pre>
 * 第一条就是用户报的那条：按 A 必须往**屏幕左**走。后面两条平时看不出来
 * （走路时竖直输入恒为 0），所以必须单独钉住。
 *
 * <p>{@link SpacePlayerData} 拆分（注册管道搬到 {@code SpaceAttachments}）就是为了让本探针
 * 能直接驱动真类 —— 见那个类的注释。</p>
 */
public class RollInputBasisProbe {

    private static int poses;
    private static int passed;
    private static int failed;
    /** 失败里最坏的一例，方便一眼看出错在哪。 */
    private static String worst = "";
    private static double worstDot = 2.0;

    public static void main(String[] args) throws Exception {
        double[] yaws = {0, 45, 90, 135, 180, -90, -135};
        double[] pitches = {-60, -30, 0, 30, 60};
        double[] rolls = {0, 30, 60, 90, 135, 180, -135, -90, -60, -30};

        Object camera;
        Method setRotation;
        Method getLeft;
        Method getUp;
        Method getLook;
        try {
            Class<?> camClass = Class.forName("net.minecraft.client.Camera");
            camera = camClass.getDeclaredConstructor().newInstance();
            setRotation = camClass.getDeclaredMethod("setRotation", float.class, float.class, float.class);
            setRotation.setAccessible(true);
            getLeft = camClass.getMethod("getLeftVector");
            getUp = camClass.getMethod("getUpVector");
            getLook = camClass.getMethod("getLookVector");
            System.out.println("相机基底来源：net.minecraft.client.Camera（**真原版类**）");
        } catch (Throwable t) {
            System.out.println("★ 无法离线加载 net.minecraft.client.Camera：" + t);
            System.out.println("  本探针必须拿'玩家真正看到的东西'当基准，否则又是复刻对复刻。");
            System.out.println("  请用全量 classpath 重跑（见 run-offline-checks.ps1 第 21 步的 @argfile）。");
            System.exit(2);
            return;
        }

        for (double yaw : yaws) {
            for (double pitch : pitches) {
                for (double roll : rolls) {
                    SpacePlayerData data = new SpacePlayerData();
                    data.initFromVanilla((float) yaw, (float) pitch);
                    data.rollBody(Math.toRadians(roll));

                    // ── 输入侧：就是 Mixin 用的那一步（真方法）──
                    Quaternionf frame = data.viewFrame(new Quaternionf());
                    Vector3f aDir = frame.transform(new Vector3f(1.0F, 0.0F, 0.0F));   // 原版约定 +X = 屏幕左
                    Vector3f uDir = frame.transform(new Vector3f(0.0F, 1.0F, 0.0F));
                    Vector3f fDir = frame.transform(new Vector3f(0.0F, 0.0F, 1.0F));

                    // ── 相机侧：真方法算三元组 → 真 Camera 渲染用的基底 ──
                    float[] mc = new float[3];
                    SpacePlayerData.mcRotation(data.facing(), data.left(), 0.0, mc);
                    setRotation.invoke(camera, mc[0], mc[1], mc[2]);
                    Vector3f camLeft = (Vector3f) getLeft.invoke(camera);
                    Vector3f camUp = (Vector3f) getUp.invoke(camera);
                    Vector3f camLook = (Vector3f) getLook.invoke(camera);

                    poses++;
                    double dotA = aDir.dot(camLeft);
                    double dotU = uDir.dot(camUp);
                    double dotF = fDir.dot(camLook);
                    if (dotA > 0.99 && dotU > 0.99 && dotF > 0.99) {
                        passed++;
                    } else {
                        failed++;
                        double worstHere = Math.min(dotA, Math.min(dotU, dotF));
                        if (worstHere < worstDot) {
                            worstDot = worstHere;
                            worst = String.format(java.util.Locale.ROOT,
                                    "yaw=%.0f pitch=%.0f roll=%.0f → dot(A,相机左)=%+.3f "
                                            + "dot(上,相机上)=%+.3f dot(视线,相机看)=%+.3f",
                                    yaw, pitch, roll, dotA, dotU, dotF);
                        }
                    }
                }
            }
        }

        System.out.printf(java.util.Locale.ROOT, "姿态 %d 个：PASS %d，FAIL %d%n", poses, passed, failed);
        if (failed > 0) {
            System.out.println("最坏一例：" + worst);
            System.out.println("  ⇒ '按 A 得到的方向'与'相机实际渲染的屏幕左'不一致：");
            System.out.println("     · dot(A,相机左) ≈ -1 ⇒ 相机不滚而输入滚了（或反之）—— 就是用户报的左右反；");
            System.out.println("     · dot(上,相机上) ≈ -1 ⇒ 相机上下也翻了（平时看不出来，竖直输入恒为 0）。");
        }
        System.out.printf("%n=== RollInputBasisProbe: %d/%d 通过，失败 %d ===%n", passed, poses, failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
