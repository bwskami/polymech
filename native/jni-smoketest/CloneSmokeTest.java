import com.mss.polymech.mps.rapier.helper.ColliderBody;
import com.mss.polymech.mps.rapier.helper.RapierWorld;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import com.mss.polymech.physics.PhysicsNatives;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 高仿 MPS 克隆层（{@code com.mss.polymech.mps.rapier.helper}）的<b>离线端到端测试</b>。
 *
 * <p>{@code RapierWorld} / {@code RigidBody} / {@code ColliderBody} 三个类**零 Minecraft import**
 * （已实测），所以不需要启动游戏就能跑。验的正是克隆层最容易错、也最难靠"编译通过"发现的三件事：</p>
 * <ol>
 *   <li><b>分离语义</b>：构造只存参数，{@code addRigidBody} 才落原生；</li>
 *   <li><b>{@code up()} 的 flush 时序</b>：碰撞体推迟到 {@code up()} 创建（对齐 MPS 的 operation buffer），
 *       因此"先 addColliderBody、再 setCollisionGroups"这种 MPS 调用顺序能生效；</li>
 *   <li><b>碰撞组真的生效</b>：立方体由 (2,-1) 与地面 (1,-1) 互相作用而停在地面上。</li>
 * </ol>
 *
 * <p>跑法（需 slf4j-api 在 classpath，因为 {@code PhysicsNatives} 用它记日志）：</p>
 * <pre>
 * javac -encoding UTF-8 -cp build\classes\java\main -d &lt;out&gt; native\jni-smoketest\CloneSmokeTest.java
 * java -Djava.library.path=src\main\resources\natives\windows_amd64 \
 *      -cp "&lt;out&gt;;build\classes\java\main;&lt;slf4j-api.jar&gt;" CloneSmokeTest
 * </pre>
 */
public final class CloneSmokeTest {

    private static int failures = 0;

    private static void check(boolean ok, String message) {
        if (!ok) {
            System.err.println("FAIL: " + message);
            failures++;
            return;
        }
        System.out.println("ok: " + message);
    }

    public static void main(String[] args) {
        if (!PhysicsNatives.ensureLoaded()) {
            System.err.println("原生库加载失败：" + PhysicsNatives.status());
            System.exit(2);
        }
        System.out.println("原生库：" + PhysicsNatives.status());

        RapierWorld w = new RapierWorld(0.0, -9.8, 0.0);
        try {
            check(w.isAvailable(), "RapierWorld 创建（句柄 " + w.rapierWorldHandle() + "）");
            w.setTickTime(0.01);

            // ① 分离语义
            RigidBody body = new RigidBody(RigidBody.Type.DYNAMIC,
                    new Vector3d(0.0, 3.0, 0.0), new Quaterniond(), 1.0);
            check(!body.isAttached(), "分离语义：构造后未插入世界");
            w.addRigidBody(body);
            check(body.isAttached(), "addRigidBody 后已插入（id=" + body.getHandle() + "）");

            // ② 碰撞体延迟到 up() 创建 —— 先设组再 flush（MPS 的调用顺序）
            ColliderBody cb = new ColliderBody(ColliderBody.Type.CUBOID,
                    new Vector3d(0.0, 0.0, 0.0), new Quaterniond(), 0.02, 0.5, 0.5, 0.5);
            cb.setCollisionGroups(2, -1);
            w.addColliderBody(cb, body);
            check(!cb.isAttached(), "碰撞体在 up() 之前不创建（对齐 MPS 的 flush 时序）");
            w.up();
            check(cb.isAttached(), "up() 之后碰撞体已创建（含碰撞组）");

            // ③ 地面
            RigidBody ground = new RigidBody(RigidBody.Type.FIXED,
                    new Vector3d(0.0, -0.5, 0.0), new Quaterniond());
            w.addRigidBody(ground);
            ColliderBody gcb = new ColliderBody(ColliderBody.Type.CUBOID,
                    new Vector3d(0.0, 0.0, 0.0), new Quaterniond(), 0.02, 5.0, 0.5, 5.0);
            gcb.setCollisionGroups(1, -1);
            w.addColliderBody(gcb, ground);
            w.up();
            check(gcb.isAttached(), "地面碰撞体已创建");

            // ④ 步进：碰撞组互相作用 → 停在地面
            for (int i = 0; i < 300; i++) {
                w.up();
                w.step();
            }
            Vector3d p = body.getPos();
            check(p != null && Math.abs(p.y - 0.5) < 0.3,
                    "clone 层步进后落在地面 y=" + (p == null ? "null" : String.format("%.3f", p.y)));

            // ⑤ ABI 6 实时属性路径（改一个属性就走对应的 setter）
            cb.setFriction(0.9);
            cb.setRestitution(0.1);
            check(Math.abs(cb.getFriction() - 0.9) < 1e-9, "setFriction 记录正确（实时下发不抛异常）");

            // ⑥ 分离对象：摘出世界再插回来（跨维度搬运的原子步骤）
            long mem = w.extractRigidBody(body);
            check(mem > 0 && !body.isAttached(), "extractRigidBody -> " + mem + "（已摘出世界）");
            check(w.insertRigidBody(body), "insertRigidBody 插回世界");

            // ⑦ 世界重力读写（ABI 6 走原生 worldGetGravity）
            Vector3d g = w.getG();
            check(Math.abs(g.y + 9.8) < 1e-6,
                    String.format("getG() = (%.3f,%.3f,%.3f)", g.x, g.y, g.z));
            check(w.getRigidBodiesSize() >= 2, "getRigidBodiesSize = " + w.getRigidBodiesSize());
        } finally {
            w.free();
        }

        if (failures > 0) {
            System.err.println("CLONE SMOKE FAILED: " + failures + " 项不通过");
            System.exit(1);
        }
        System.out.println("CLONE SMOKE PASSED");
    }
}
