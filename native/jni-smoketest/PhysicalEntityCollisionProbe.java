import com.mss.polymech.mps.physical.physical_body.ClientPhysicalBody;
import com.mss.polymech.physics.PhysicalEntityCollision;
import com.mss.polymech.physics.PhysicalEntityCollider;
import java.util.List;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 玩家 ↔ 物理体 OBB 扫掠解析器的<b>离线探针</b>（对应 SPEC A1/A2，纯 Java，不依赖 Minecraft 运行时、
 * 不依赖 P0 镜像桥、不依赖原生 DLL）。
 *
 * <p>它直接构造 mps {@link ClientPhysicalBody}（填体素方块 + 位姿/旋转）喂给
 * {@link PhysicalEntityCollision#resolve(PhysicalEntityCollider.EntityShape, Vector3d, List)}，
 * 并注入合成 {@code blockCollision} 谓词（「非空气即碰」= {@code state != 0}），使核心离线可单测。</p>
 *
 * <p>判据：</p>
 * <ul>
 *   <li><b>A1 高速不隧穿</b>：旋转体（45° Y）+ 大位移（20 格，远超 MAX_SEGMENT_LENGTH=2 ⇒ 分段）。
 *       断言 resolved 位移沿逼近轴停在近表面（不穿越到远端）。</li>
 *   <li><b>A2 已穿入可推出</b>：玩家盒初始与方块重叠 + 位移含朝重叠法向分量
 *       （{@code movement.dot(overlapNormal) < -1e-10}）。断言 overlap 分支移除了法向内分量
 *       （resolved.x≈0）、切向滑移分量存活（resolved.z>0）、且盒被推到方块表面之外（重叠归零）。</li>
 * </ul>
 *
 * <p>用法（需全量合并 classpath，因为本探针加载的类 import Minecraft 符号；见 docs/mps-clone-plan.md §13）：</p>
 * <pre>
 * javac -encoding UTF-8 -cp "build\classes\java\main;<merged>;<joml>" -d build\pm-p1 native\jni-smoketest\PhysicalEntityCollisionProbe.java
 * java -cp "build\pm-p1;build\classes\java\main;<merged>;<joml>;<slf4j>" PhysicalEntityCollisionProbe
 * </pre>
 */
public class PhysicalEntityCollisionProbe {

    /** 玩家碰撞箱半宽 / 半高 / 眼高（SPEC A 给定）。 */
    private static final double HALF_WIDTH = 0.3;
    private static final double HALF_HEIGHT = 0.9;
    private static final double EYE_HEIGHT = 1.62;
    /** 合成「非空气」方块 state（非零即有碰撞，供注入谓词判定）。 */
    private static final int SOLID = 1;

    private static int failures = 0;

    public static void main(String[] args) {
        // 注入离线谓词：非空气即碰（绕开 Block.stateById 的 MC 运行时注册表）。
        PhysicalEntityCollision.setBlockCollision(state -> state != 0);

        a1();
        a2();
        a3();

        if (failures == 0) {
            System.out.println();
            System.out.println("PASS: PhysicalEntityCollisionProbe (A1 + A2 + A3)");
            System.exit(0);
        } else {
            System.out.println();
            System.out.println("FAIL: PhysicalEntityCollisionProbe (" + failures + " failure(s))");
            System.exit(1);
        }
    }

    /** 造一个「直立」玩家盒（旋转 = 单位四元数），盒中心落在 (cx, cy, cz)。 */
    private static PhysicalEntityCollider.EntityShape box(double cx, double cy, double cz) {
        // 眼高 pivot + 盒底 offset：center = rotation·offset + pivot = (cx, cy, cz)
        Vector3d pivot = new Vector3d(cx, cy - HALF_HEIGHT + EYE_HEIGHT, cz);
        Vector3d offset = new Vector3d(0.0, HALF_HEIGHT - EYE_HEIGHT, 0.0);
        Vector3d halfExtents = new Vector3d(HALF_WIDTH, HALF_HEIGHT, HALF_WIDTH);
        return new PhysicalEntityCollider.EntityShape(null, pivot, offset, halfExtents, new Quaterniond());
    }

    /**
     * A1 高速不隧穿：45°（绕 Y）旋转的 4×4×4 实心方块在原点，玩家盒从 x=-10 以 20 格单 tick 位移逼近。
     * 无分段/无碰撞时 resolved.x=20（隧穿到远端 x=+10）；正确时应停在近表面（约 x≈6.87）。
     */
    private static void a1() {
        Quaterniond rot = new Quaterniond().rotateY(Math.PI / 4.0);
        ClientPhysicalBody body = new ClientPhysicalBody(new Vector3d(0.0, 0.0, 0.0), rot);
        body.fillBlockState(-2, -2, -2, 1, 1, 1, SOLID);

        PhysicalEntityCollider.EntityShape shape = box(-10.0, 0.0, 0.0);
        Vector3d resolved = PhysicalEntityCollision.resolve(shape, new Vector3d(20.0, 0.0, 0.0), List.of(body));

        // 隧穿则 resolved.x=20；停在近表面约 6.87。阈值取 10，两侧留足裕度。
        boolean noTunnel = resolved.x < 10.0 && resolved.x > 1.0;
        System.out.printf("A1 高速防隧穿: input=(20,0,0) → resolved=(%.4f, %.4f, %.4f)%n",
                resolved.x, resolved.y, resolved.z);
        check(noTunnel, "A1 不隧穿（resolved.x=" + resolved.x + "，应落在 (1,10)，隧穿则 =20）");
    }

    /**
     * A2 已穿入可推出：玩家盒中心 (0.5,0,0.5) 完全嵌入 1×4×1 实心方块（世界 [0,1]×[-2,2]×[0,1]）。
     * 重叠法向 = +X（最浅穿透轴）；位移 = (-0.5, 0, 1.0) —— 含朝重叠法向分量
     * （{@code dot(+X) = -0.5 < -1e-10}）+ 切向 +Z 分量。期望：overlap 分支（SweepHit(0,+X)）
     * 移除法向内分量（resolved.x≈0）、保留切向滑移（resolved.z≈1.0），并把盒推出方块表面之外。
     */
    private static void a2() {
        ClientPhysicalBody body = new ClientPhysicalBody(new Vector3d(0.0, 0.0, 0.0), new Quaterniond());
        body.fillBlockState(0, -2, 0, 0, 1, 0, SOLID);

        PhysicalEntityCollider.EntityShape start = box(0.5, 0.0, 0.5);
        // 前置自检：初始盒确实与方块重叠（否则 A2 前提不成立）。
        double overlap0 = overlap(start, body);
        check(overlap0 > 1.0E-3, "A2 前置：初始重叠深度=" + overlap0 + " 应 > 0（已穿入）");

        Vector3d resolved = PhysicalEntityCollision.resolve(start, new Vector3d(-0.5, 0.0, 1.0), List.of(body));
        PhysicalEntityCollider.EntityShape end = start.move(resolved);

        // depenetrate 先沿最小穿透轴（+X）推出静止重叠 ⇒ resolved.x 为正（推出方向）。
        boolean pushedOutward = resolved.x >= 0.0;
        boolean tangentialKept = resolved.z > 0.0;
        double overlapEnd = overlap(end, body);
        boolean pushedOut = overlapEnd < 1.0E-3;

        System.out.printf("A2 已穿入可推出: input=(-0.5,0,1) → resolved=(%.4f, %.4f, %.4f)，"
                        + "重叠 0→%.6f%n",
                resolved.x, resolved.y, resolved.z, overlapEnd);
        check(pushedOutward, "A2 法向朝推出方向（resolved.x=" + resolved.x + " 应 >=0，depenetrate 推出）");
        check(tangentialKept, "A2 切向滑移存活（resolved.z=" + resolved.z + " 应 >0，可自由滑动脱离）");
        check(pushedOut, "A2 推出方块表面之外（重叠深度=" + overlapEnd + " 应 <1e-3）");
    }

    /**
     * A3 旋转导致的静止重叠推出：玩家盒嵌进方块，movement=(0,0,0)（纯旋转/原地）。
     * 0.5.0 的 overlap 分支要求"朝法向移动"，纯旋转会被 early-return 跳过 —— 修复后应仍推出。
     */
    private static void a3() {
        ClientPhysicalBody body = new ClientPhysicalBody(new Vector3d(0.0, 0.0, 0.0), new Quaterniond());
        body.fillBlockState(0, -2, 0, 0, 1, 0, SOLID);

        PhysicalEntityCollider.EntityShape start = box(0.5, 0.0, 0.5);
        double overlap0 = overlap(start, body);
        check(overlap0 > 1.0E-3, "A3 前置：初始重叠深度=" + overlap0 + " 应 > 0（旋转穿入）");

        Vector3d resolved = PhysicalEntityCollision.resolve(start, new Vector3d(0.0, 0.0, 0.0), List.of(body));
        PhysicalEntityCollider.EntityShape end = start.move(resolved);
        double overlapEnd = overlap(end, body);

        System.out.printf("A3 旋转静止推出: input=(0,0,0) → resolved=(%.4f, %.4f, %.4f)，重叠 0→%.6f%n",
                resolved.x, resolved.y, resolved.z, overlapEnd);
        check(resolved.lengthSquared() > 1.0E-10, "A3 纯旋转（movement=0）也产生推出位移，而非早退");
        check(overlapEnd < 1.0E-3, "A3 推出后不再重叠（重叠=" + overlapEnd + " 应 <1e-3）");
    }

    /**
     * 玩家盒与物理体实心体素的重叠深度（离线判据）。
     *
     * <p>离线环境里 {@code body.worldAABB} 依赖 {@code Block.stateById}（真实 MC 注册表）不可用，
     * 所以这里改走 {@code getBlockStateInt}（读原始体素数据，不碰注册表）：把玩家盒（直立世界 AABB）
     * 的 8 个角变换到体局部空间，扫描局部包围盒内所有体素，逐个求 AABB 重叠并取最深轴。返回 0 = 不相交。</p>
     */
    private static double overlap(PhysicalEntityCollider.EntityShape shape, ClientPhysicalBody body) {
        Vector3d c = shape.center();
        Vector3d half = shape.halfExtents();
        Quaterniond inv = new Quaterniond(body.getRotation()).conjugate();
        Vector3d pos = body.getPos();
        Vector3d mn = new Vector3d(Double.POSITIVE_INFINITY);
        Vector3d mx = new Vector3d(Double.NEGATIVE_INFINITY);
        double[] xs = {c.x - half.x, c.x + half.x};
        double[] ys = {c.y - half.y, c.y + half.y};
        double[] zs = {c.z - half.z, c.z + half.z};
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vector3d local = new Vector3d(x, y, z).sub(pos);
                    inv.transform(local);
                    mn.min(local);
                    mx.max(local);
                }
            }
        }
        int minX = (int) Math.floor(mn.x), maxX = (int) Math.floor(mx.x);
        int minY = (int) Math.floor(mn.y), maxY = (int) Math.floor(mx.y);
        int minZ = (int) Math.floor(mn.z), maxZ = (int) Math.floor(mx.z);
        double maxPen = 0.0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (body.getBlockStateInt(x, y, z) != 0) {
                        double ox = Math.min(mx.x, x + 1.0) - Math.max(mn.x, x);
                        double oy = Math.min(mx.y, y + 1.0) - Math.max(mn.y, y);
                        double oz = Math.min(mx.z, z + 1.0) - Math.max(mn.z, z);
                        if (ox > 0.0 && oy > 0.0 && oz > 0.0) {
                            maxPen = Math.max(maxPen, Math.min(ox, Math.min(oy, oz)));
                        }
                    }
                }
            }
        }
        return maxPen;
    }

    private static void check(boolean ok, String message) {
        if (ok) {
            System.out.println("  [PASS] " + message);
        } else {
            System.out.println("  [FAIL] " + message);
            failures++;
        }
    }
}
