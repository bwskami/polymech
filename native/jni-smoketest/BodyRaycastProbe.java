import com.mss.polymech.physics.BodyRaycast;

import java.util.List;

/**
 * 服务端"准星指着哪个物理体"的**离线判据**（{@link BodyRaycast} 是纯函数，只依赖 joml）。
 *
 * <h2>为什么这一条非有不可（2026-09-29 实机）</h2>
 * 用户报"点一下右键只有一瞬间的射线、无法长按"。根因是牵引枪在服务端查了<b>另一张表</b>
 * （MPS 的 {@code PhysicalWorld}），而那批体的位姿是 NaN ⇒ 求交全空 ⇒ 服务端回
 * {@code released} ⇒ 客户端本地预测的光束被立刻收回。
 *
 * <p>这里把新实现（在 {@code PhysicsBodyTracker} 的表上、与客户端 {@code PhysgunTarget}
 * 同一套 {@code RayBox} 数学）钉住，其中**专门包含**"位姿 NaN 的体必须被跳过而不是把整次求交
 * 弄成 NaN"这一项 —— 那正是当时把"最近距离"打成 {@code Double.MAX_VALUE} 的那个故障。</p>
 */
public final class BodyRaycastProbe {

    private static int fails = 0;

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "[ OK ] " : "[FAIL] ") + what + "    " + detail);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] args) {
        System.out.println("================ 服务端体求交离线判据（BodyRaycast）================");
        System.out.printf("参数：外扩=%.2f 格（与客户端 PhysgunTarget.INFLATE 一致）%n", BodyRaycast.INFLATE);
        System.out.println();

        // ---------- 1) 单体正打 ----------
        System.out.println("--- 1) 单体正打 ---");
        // 体在 (10,0,0)，一格方块在局部 (0,0,0) ⇒ 局部盒 x∈[-0.15,1.15]
        BodyRaycast.Body one = body(7L, 10, 0, 0, 0, 0, 0, 1, new int[]{0}, new int[]{0}, new int[]{0});
        BodyRaycast.Hit hit = BodyRaycast.nearest(List.of(one), 5, 0, 0, 1, 0, 0, 64);
        check("从 (5,0,0) 朝 +X 打 ⇒ 命中 #7", hit != null && hit.id() == 7L,
                hit == null ? "未命中" : ("#" + hit.id()));
        check("距离 = 4.85 格（进入外扩后的包围盒，而不是方块面）",
                hit != null && Math.abs(hit.distance() - 4.85) < 1e-9,
                hit == null ? "n/a" : String.format("%.6f", hit.distance()));
        check("局部命中点 x = -0.15（就是包围盒近面）",
                hit != null && Math.abs(hit.localX() + 0.15) < 1e-9,
                hit == null ? "n/a" : String.format("%.6f", hit.localX()));
        System.out.println();

        // ---------- 2) 取最近，而不是"第一个" ----------
        System.out.println("--- 2) 多个体取最近 ---");
        BodyRaycast.Body far = body(2L, 20, 0, 0, 0, 0, 0, 1, new int[]{0}, new int[]{0}, new int[]{0});
        BodyRaycast.Hit near = BodyRaycast.nearest(List.of(far, one), 5, 0, 0, 1, 0, 0, 64);
        check("远处体先出现在列表里 ⇒ 仍应选中近的 #7（不是拿第一个）",
                near != null && near.id() == 7L, near == null ? "未命中" : ("#" + near.id()));
        BodyRaycast.Hit onlyFar = BodyRaycast.nearest(List.of(far), 5, 0, 0, 1, 0, 0, 10);
        check("近的体超出 maxDistance 时返回 null（距离判据生效）", onlyFar == null,
                onlyFar == null ? "null" : String.format("%.3f", onlyFar.distance()));
        System.out.println();

        // ---------- 3) 体被转过（真正在分辨旋转的那一组）----------
        System.out.println("--- 3) 体绕 Y 转 90°（局部 +X → 世界 −Z）---");
        // 3 格长（局部 x = 0,1,2）；绕 Y 转 90°
        double s = Math.sin(Math.toRadians(45.0));
        double c = Math.cos(Math.toRadians(45.0));
        BodyRaycast.Body rotated = body(9L, 10, 0, 0, 0, s, 0, c,
                new int[]{0, 1, 2}, new int[]{0, 0, 0}, new int[]{0, 0, 0});
        // 从 (5,0,-2) 朝 +X：旋转后盒子在世界 z∈[-2,0) ⇒ 这根射线正好擦进体里
        BodyRaycast.Hit rotHit = BodyRaycast.nearest(List.of(rotated), 5, 0, -2, 1, 0, 0, 64);
        check("体转 90°：从 (5,0,-2) 朝 +X 打 ⇒ 命中", rotHit != null,
                rotHit == null ? "未命中" : String.format("t=%.4f", rotHit.distance()));
        BodyRaycast.Body straight = body(9L, 10, 0, 0, 0, 0, 0, 1,
                new int[]{0, 1, 2}, new int[]{0, 0, 0}, new int[]{0, 0, 0});
        BodyRaycast.Hit noRotHit = BodyRaycast.nearest(List.of(straight), 5, 0, -2, 1, 0, 0, 64);
        check("同一根射线若体未旋转 ⇒ 打不到（证明这组用例真的在分辨旋转）", noRotHit == null,
                noRotHit == null ? "未命中" : String.format("t=%.4f", noRotHit.distance()));
        System.out.println();

        // ---------- 4) 坏数据必须"跳过"而不是"污染整次求交" ----------
        System.out.println("--- 4) 坏位姿/坏数据（2026-09-29 的故障就是这一类）---");
        BodyRaycast.Body nan = body(1L, Double.NaN, Double.NaN, Double.NaN, 0, 0, 0, 1,
                new int[]{0}, new int[]{0}, new int[]{0});
        BodyRaycast.Hit nanOnly = BodyRaycast.nearest(List.of(nan), 5, 0, 0, 1, 0, 0, 64);
        check("位姿 NaN 的体被跳过 ⇒ 返回 null（而不是算出 NaN 距离把最小值撑成 MAX_VALUE）",
                nanOnly == null, nanOnly == null ? "null" : "竟命中");
        BodyRaycast.Hit mixed = BodyRaycast.nearest(List.of(nan, one), 5, 0, 0, 1, 0, 0, 64);
        check("坏体在前、好体在后 ⇒ 仍然命中好体（坏数据不能废掉整次求交）",
                mixed != null && mixed.id() == 7L, mixed == null ? "未命中" : ("#" + mixed.id()));
        BodyRaycast.Body zeroQuat = body(3L, 10, 0, 0, 0, 0, 0, 0,
                new int[]{0}, new int[]{0}, new int[]{0});
        check("零四元数（未初始化姿态）被跳过",
                BodyRaycast.nearest(List.of(zeroQuat), 5, 0, 0, 1, 0, 0, 64) == null, "跳过");
        BodyRaycast.Body emptyBody = body(4L, 10, 0, 0, 0, 0, 0, 1,
                new int[0], new int[0], new int[0]);
        check("没有方块的体被跳过",
                BodyRaycast.nearest(List.of(emptyBody), 5, 0, 0, 1, 0, 0, 64) == null, "跳过");
        check("空表 ⇒ null", BodyRaycast.nearest(List.of(), 5, 0, 0, 1, 0, 0, 64) == null, "null");
        check("零方向 ⇒ null（不产生除零）",
                BodyRaycast.nearest(List.of(one), 5, 0, 0, 0, 0, 0, 64) == null, "null");
        check("maxDistance <= 0 ⇒ null",
                BodyRaycast.nearest(List.of(one), 5, 0, 0, 1, 0, 0, 0) == null, "null");
        System.out.println();

        // ---------- 5) 擦边/背后 ----------
        System.out.println("--- 5) 擦边与背后 ---");
        BodyRaycast.Hit graze = BodyRaycast.nearest(List.of(one), 5, 1.10, 0, 1, 0, 0, 64);
        check("y=1.10 擦过外扩后的包围盒（1.15）⇒ 命中（「擦边也能抓」是照客户端的 INFLATE）",
                graze != null, graze == null ? "未命中" : String.format("t=%.3f", graze.distance()));
        BodyRaycast.Hit miss = BodyRaycast.nearest(List.of(one), 5, 1.20, 0, 1, 0, 0, 64);
        check("y=1.20 彻底错过 ⇒ 未命中", miss == null, miss == null ? "未命中" : "竟命中");
        BodyRaycast.Hit behind = BodyRaycast.nearest(List.of(one), 5, 0, 0, -1, 0, 0, 64);
        check("体在射线背后 ⇒ 未命中", behind == null, behind == null ? "未命中" : "竟命中");
        System.out.println();

        System.out.println(fails == 0 ? "==== 体求交判据 PASS ====" : ("==== 有 " + fails + " 项 FAIL ===="));
        if (fails != 0) {
            System.exit(1);
        }
    }

    private static BodyRaycast.Body body(long id, double px, double py, double pz,
                                         double qx, double qy, double qz, double qw,
                                         int[] xs, int[] ys, int[] zs) {
        return new BodyRaycast.Body(id, px, py, pz, qx, qy, qz, qw, xs, ys, zs);
    }
}
