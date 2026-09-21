import com.google.gson.JsonObject;
import com.mss.polymech.mps.kelvin.physical.CelestialBodyForce;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashSet;
import java.util.Set;

/**
 * kelvin 力模型（{@code CelestialBodyForce}）的离线测试 —— 纯数据类（gson + joml），无 MC 依赖。
 *
 * <p>钉住两个"以后会悄悄坏"的设计点：</p>
 * <ol>
 *   <li><b>同名即同一股力</b>：{@code equals}/{@code hashCode} 只比名字。这是"续期/替换"语义的前提 ——
 *       若哪天有人"顺手"把 force 也纳入相等判定，力池就会从"替换"退化成"叠加"，
 *       表现为按住推进器推力越来越大。</li>
 *   <li><b>{@code POSITIVE_INFINITY} 是永久力</b>（引力就是这种）。{@code doTime} 对它必须无影响。</li>
 * </ol>
 *
 * <p>跑法见 {@code docs/mps-clone-plan.md}（需要 gson 在 classpath）。</p>
 */
public final class KelvinForceTest {

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
        // ① 同名即同一股力（可替换、不叠加）
        Set<CelestialBodyForce> pool = new HashSet<>();
        pool.add(new CelestialBodyForce("thruster", new Vector3d(1.0, 0.0, 0.0), 0.05));
        pool.add(new CelestialBodyForce("thruster", new Vector3d(9.0, 0.0, 0.0), 0.05));
        check(pool.size() == 1, "同名力只占一项（池大小=" + pool.size() + "）");
        check(new CelestialBodyForce("a", new Vector3d(1, 0, 0), 1.0)
                        .equals(new CelestialBodyForce("a", new Vector3d(99, 0, 0), 9.0)),
                "同名的两股力相等（相等只看名字，与向量/时长无关）");

        // ② 无穷时长是永久力：扣步长后仍是无穷
        CelestialBodyForce gravity = new CelestialBodyForce("Gravitation",
                new Vector3d(0.0, -9.8, 0.0), Double.POSITIVE_INFINITY);
        gravity.doTime(0.01);
        check(Double.isInfinite(gravity.getTime()), "永久力扣一步后仍是无穷（time=" + gravity.getTime() + "）");

        // ③ 有限时长会真的被扣（每步扣步长 → 等效冲量与步长无关）
        CelestialBodyForce push = new CelestialBodyForce("push", new Vector3d(2.0, 0.0, 0.0), 0.05);
        push.doTime(0.01);
        check(Math.abs(push.getTime() - 0.04) < 1e-12,
                String.format("有限力按步长递减（time=%.4f ≈ 0.04）", push.getTime()));

        // ④ 沿局部 +Z 分解推力：单位姿态下就是 (0,0,mag)
        Vector3d d = CelestialBodyForce.decomposeForce(7.5, new Quaterniond());
        check(Math.abs(d.x) < 1e-12 && Math.abs(d.y) < 1e-12 && Math.abs(d.z - 7.5) < 1e-12,
                String.format("decomposeForce 单位姿态 -> (%.3f,%.3f,%.3f)", d.x, d.y, d.z));

        // ⑤ JSON 往返（存档形态）
        CelestialBodyForce src = new CelestialBodyForce("Gravitation", 1.5, -2.5, 3.5, 12.0);
        JsonObject json = src.toJsonObject();
        CelestialBodyForce back = CelestialBodyForce.getFromJsonObject(json);
        check(back != null
                        && back.getName().equals("Gravitation")
                        && Math.abs(back.getX() - 1.5) < 1e-12
                        && Math.abs(back.getY() + 2.5) < 1e-12
                        && Math.abs(back.getZ() - 3.5) < 1e-12
                        && Math.abs(back.getTime() - 12.0) < 1e-12,
                "JSON 往返无损：" + back);
        check(CelestialBodyForce.getFromJsonObject(new JsonObject()) == null,
                "缺字段的 JSON 返回 null（调用方跳过该条）");

        if (failures > 0) {
            System.err.println("KELVIN FORCE TEST FAILED: " + failures + " 项不通过");
            System.exit(1);
        }
        System.out.println("KELVIN FORCE TEST PASSED");
    }
}
