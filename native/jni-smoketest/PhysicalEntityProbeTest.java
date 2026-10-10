import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.physics.PhysicalEntityCollider;
import com.mss.polymech.physics.PhysicalEntityProbe;
import com.mss.polymech.physics.PhysicsNatives;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3d;

/**
 * 载速探针 {@link PhysicalEntityProbe} 的<b>离线探针</b>（对应 SPEC A3，纯 Java + native，不启动游戏）。
 *
 * <p>它验证 {@code velocity()} 的<b>测量逻辑</b>（{@code onPhysicsStep} 在 tick%5==0 时读探针刚体
 * linvel 并按着地状态减重力分量）：</p>
 * <ul>
 *   <li><b>静止地面</b>（grounded=true、linvel=0）：velocity 水平分量 == 0；</li>
 *   <li><b>匀速平台</b>（grounded=true、linvel=+5 沿 X）：velocity.x ≈ 5。</li>
 * </ul>
 *
 * <p><b>如实标注（不是缺陷，是离线模拟边界）</b>：这里通过手动写探针刚体 linvel 模拟「被环境带着走」，
 * 验证 {@code velocity()} 的测量与复位逻辑；「探针接触真实移动平台、接触解算量出载速」这条完整链
 * 依赖 KINEMATIC 平台 + 接触，属游戏内验证范围（离线无 Entity，见 SPEC C 节「端到端实机取证」）。</p>
 *
 * <p>跑法（需 Minecraft 符号的 classpath，见 docs/mps-clone-plan.md §13）：</p>
 * <pre>
 * javac -encoding UTF-8 -cp "build\classes\java\main;<merged>;<joml>" -d build\pm-p3 native\jni-smoketest\PhysicalEntityProbeTest.java
 * java -Djava.library.path=src\main\resources\natives\windows_amd64 \
 *      -cp "build\pm-p3;build\classes\java\main;<merged>;<joml>;<slf4j>" PhysicalEntityProbeTest
 * </pre>
 */
public final class PhysicalEntityProbeTest {

    private static int failures = 0;

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [PASS] " + message);
        } else {
            System.out.println("  [FAIL] " + message);
            failures++;
        }
    }

    public static void main(String[] args) {
        if (!PhysicsNatives.ensureLoaded()) {
            System.err.println("原生库加载失败：" + PhysicsNatives.status());
            System.exit(2);
        }

        a3Still();
        a3Moving();

        if (failures == 0) {
            System.out.println();
            System.out.println("PASS: PhysicalEntityProbeTest (A3)");
            System.exit(0);
        } else {
            System.out.println();
            System.out.println("FAIL: PhysicalEntityProbeTest (" + failures + " failure(s))");
            System.exit(1);
        }
    }

    /** 静止地面：grounded、linvel=0 → velocity 水平分量 == 0。 */
    private static void a3Still() {
        ClientPhysicalWorld world = new ClientPhysicalWorld(
                ResourceLocation.fromNamespaceAndPath("test", "still"), new Vector3d(0.0, -9.8, 0.0));
        world.setTickTime(0.01);
        try {
            PhysicalEntityProbe probe = new PhysicalEntityProbe(PhysicalEntityCollider.create(0.3, 0.9));
            probe.bind(world);
            probe.sync(new Vector3d(0.0, 1.0, 0.0), true); // 离线测试缝：碰撞箱中心 + 着地
            probe.rigidBody().setLinvel(new Vector3d(0.0, 0.0, 0.0));
            world.up();
            world.step(); // tick=0 触发 onPhysicsStep（tickListener 在 worldStep 前跑）
            Vector3d v = probe.velocity();
            System.out.printf("A3 静止地面: velocity=(%.4f, %.4f, %.4f)%n", v.x, v.y, v.z);
            check(Math.abs(v.x) < 1.0e-6 && Math.abs(v.z) < 1.0e-6,
                    "静止地面 velocity 水平分量 == 0（实际 x=" + v.x + " z=" + v.z + "）");
        } finally {
            world.free();
        }
    }

    /** 匀速平台：grounded、linvel=+5 沿 X → velocity.x ≈ 5。 */
    private static void a3Moving() {
        ClientPhysicalWorld world = new ClientPhysicalWorld(
                ResourceLocation.fromNamespaceAndPath("test", "moving"), new Vector3d(0.0, -9.8, 0.0));
        world.setTickTime(0.01);
        try {
            PhysicalEntityProbe probe = new PhysicalEntityProbe(PhysicalEntityCollider.create(0.3, 0.9));
            probe.bind(world);
            probe.sync(new Vector3d(0.0, 1.0, 0.0), true); // 着地：velocity = linvel（不减重力）
            probe.rigidBody().setLinvel(new Vector3d(5.0, 0.0, 0.0));
            world.up();
            world.step(); // tick=0 触发 onPhysicsStep
            Vector3d v = probe.velocity();
            System.out.printf("A3 匀速平台: velocity=(%.4f, %.4f, %.4f)%n", v.x, v.y, v.z);
            check(Math.abs(v.x - 5.0) < 1.0e-3,
                    "匀速平台 velocity.x ≈ 5（实际 x=" + v.x + "，误差应 < 1e-3）");
        } finally {
            world.free();
        }
    }
}
