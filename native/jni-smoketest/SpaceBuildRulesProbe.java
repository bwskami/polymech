import com.mss.polymech.physics.RayBox;
import com.mss.polymech.physics.SpaceBuildRules;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * "太空里放方块 = 造/并物理体"的**离线判据**（纯规则类，零 MC 依赖）。
 *
 * <h2>为什么这些能离线判</h2>
 * 两处最容易静默错的地方都是纯函数：
 * <ol>
 *   <li><b>放置判据表</b>（用户规则："只有在物理体旁边和人旁边的空地才能放"）；</li>
 *   <li><b>命中面的世界化</b>：体被牵引枪转过之后，局部面直接用会<b>把方块放到体的另一侧</b> ——
 *       实机只表现为"放歪了"，根因是数学。</li>
 * </ol>
 * 实机部分（事件是否真的触发、tracker 是否正确建碰撞体/广播）另有产物级与实机证据：
 * 打过补丁的 {@code net/minecraft/server/level/ServerPlayerGameMode} 调用
 * {@code CommonHooks.onItemRightClick}（全 jar 常量池扫描，见 docs §31.30）。
 */
public final class SpaceBuildRulesProbe {

    private static int fails = 0;

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "[ OK ] " : "[FAIL] ") + what + "    " + detail);
        if (!ok) {
            fails++;
        }
    }

    public static void main(String[] args) {
        System.out.println("================ 太空建造规则离线判据 ================");
        System.out.printf("参数：放置距离=%.1f 体上限=%d 冷却=%d tick%n",
                SpaceBuildRules.PLACE_DISTANCE, SpaceBuildRules.MAX_BODIES_PER_DIMENSION,
                SpaceBuildRules.COOLDOWN_TICKS);
        System.out.println("   （已去掉「离玩家多远」那条门：目标格只可能是「贴体相邻格」或「准星前方 "
                + SpaceBuildRules.PLACE_DISTANCE + " 格」，那条门只会挡住正面放置）");
        System.out.println();

        // ---------- 1) 放置判据表 ----------
        System.out.println("--- 1) 判据表 ---");
        decideCase("目标格被占 ⇒ 拒绝（哪怕贴着体）",
                true, false, true, 0, SpaceBuildRules.Decision.REFUSE_OCCUPIED);
        decideCase("目标格与玩家身体相交 ⇒ 拒绝",
                false, true, true, 0, SpaceBuildRules.Decision.REFUSE_TOO_CLOSE);
        decideCase("贴着物理体 ⇒ 允许（并进去，且不受体数上限限制）",
                false, false, true, SpaceBuildRules.MAX_BODIES_PER_DIMENSION,
                SpaceBuildRules.Decision.PLACE);
        decideCase("不贴体、体数没到顶 ⇒ 允许（正面 3 格放得下 —— 用户报的「不好放」就是这条被挡了）",
                false, false, false, 10, SpaceBuildRules.Decision.PLACE);
        decideCase("不贴体 + 体数到顶 ⇒ 拒绝（只挡新建）",
                false, false, false, SpaceBuildRules.MAX_BODIES_PER_DIMENSION,
                SpaceBuildRules.Decision.REFUSE_BODY_LIMIT);
        decideCase("边界：体数 = 上限−1 ⇒ 仍允许",
                false, false, false, SpaceBuildRules.MAX_BODIES_PER_DIMENSION - 1,
                SpaceBuildRules.Decision.PLACE);
        System.out.println();

        // ---------- 2) 拒绝文案必须是人话（否则玩家只会看到"没反应"）----------
        System.out.println("--- 2) 拒绝文案 ---");
        for (SpaceBuildRules.Decision d : new SpaceBuildRules.Decision[]{
                SpaceBuildRules.Decision.REFUSE_OCCUPIED, SpaceBuildRules.Decision.REFUSE_TOO_CLOSE,
                SpaceBuildRules.Decision.REFUSE_BODY_LIMIT}) {
            String msg = SpaceBuildRules.message(d);
            check("拒绝文案非空：" + d, msg != null && !msg.isEmpty(), msg);
        }
        System.out.println();

        // ---------- 3) 命中面的世界化（体转 90°；第一版就是在这里会放歪）----------
        System.out.println("--- 3) 局部面 → 世界面（体绕 Y 转 90°）---");
        Quaterniond rot90 = new Quaterniond().rotateY(Math.toRadians(90.0));
        // joml rotateY(90°): 局部 +X → 世界 (0,0,-1)
        int[] plusX = SpaceBuildRules.rotateFace(1, 0, 0, rot90);
        check("局部 +X 面 → 世界 (0,0,-1) 面",
                plusX[0] == 0 && plusX[1] == 0 && plusX[2] == -1, fmt(plusX));
        int[] plusZ = SpaceBuildRules.rotateFace(0, 0, 1, rot90);
        check("局部 +Z 面 → 世界 (+1,0,0) 面",
                plusZ[0] == 1 && plusZ[1] == 0 && plusZ[2] == 0, fmt(plusZ));
        int[] up = SpaceBuildRules.rotateFace(0, 1, 0, rot90);
        check("绕 Y 转：局部 +Y 面不动 → 世界 (0,1,0)",
                up[0] == 0 && up[1] == 1 && up[2] == 0, fmt(up));
        int[] noRot = SpaceBuildRules.rotateFace(0, 1, 0, null);
        check("拿不到旋转时原样返回（退回局部面）",
                noRot[0] == 0 && noRot[1] == 1 && noRot[2] == 0, fmt(noRot));
        System.out.println();

        // ---------- 4) 射线 ↔ 盒（牵引枪视觉的拾取；slab 法最容易写错符号）----------
        System.out.println("--- 4) RayBox：射线/盒求交（正打/擦过/背后/盒内/体转 90°）---");
        Vector3d boxMin = new Vector3d(-0.5, -0.5, -0.5);
        Vector3d boxMax = new Vector3d(0.5, 0.5, 0.5);
        double t1 = RayBox.intersect(new Vector3d(2, 0, 0), new Vector3d(-1, 0, 0), boxMin, boxMax);
        check("从 +X 打向原点 ⇒ t = 1.5", Math.abs(t1 - 1.5) < 1e-9, "实得 " + fmt(t1));
        double tMiss = RayBox.intersect(new Vector3d(2, 1, 0), new Vector3d(-1, 0, 0), boxMin, boxMax);
        check("平行擦过（y=1）⇒ 不命中", Double.isNaN(tMiss), "实得 " + fmt(tMiss));
        double tBehind = RayBox.intersect(new Vector3d(-2, 0, 0), new Vector3d(-1, 0, 0), boxMin, boxMax);
        check("盒在射线背后 ⇒ 不命中（tMin 被夹在 0）", Double.isNaN(tBehind), "实得 " + fmt(tBehind));
        double tInside = RayBox.intersect(new Vector3d(0, 0, 0), new Vector3d(1, 0, 0), boxMin, boxMax);
        check("起点在盒内 ⇒ t = 0", tInside == 0.0, "实得 " + fmt(tInside));

        // 3 格长（局部 +X 方向）的体，绕 Y 转 90° ⇒ 世界里应当朝 −Z 伸长
        int[] xs = {0, 1, 2};
        int[] ys = {0, 0, 0};
        int[] zs = {0, 0, 0};
        Vector3d[] bounds = RayBox.blockBounds(xs, ys, zs, 0.0);
        // ⚠️ 2026-09-29 修正：这里的期望原来写成 x∈[-0.5, 2.5]（把整数当"块中心"），是从
        //    RayBox 自己的实现推出来的 ⇒ 于是"判据通过"只证明了实现自洽、证明不了约定正确。
        //    真实约定是"块占 [x, x+1)"，三处独立证据：PhysicsBodyTracker.localToWorld 用 dx+0.5 求块心、
        //    PhysicsBodyRenderer 用 translate(dx,dy,dz) 画占 [0,1] 的原版模型、
        //    PhysicsBodyInteractionClient 用 dx+0.5 求块心。差了这半格，客户端拾取盒就会
        //    平移半格且比真实体大一圈 ⇒ "客户端命中、服务端 ShipRaycast 全空"。
        check("单格的局部 AABB = [0,1]³（块心在 0.5，与 localToWorld 的 dx+0.5 一致）",
                Math.abs(RayBox.blockBounds(new int[]{0}, new int[]{0}, new int[]{0}, 0.0)[0].x) < 1e-9
                        && Math.abs(RayBox.blockBounds(new int[]{0}, new int[]{0}, new int[]{0}, 0.0)[1].x - 1.0) < 1e-9,
                "实得 " + fmt(RayBox.blockBounds(new int[]{0}, new int[]{0}, new int[]{0}, 0.0)[0])
                        + " … " + fmt(RayBox.blockBounds(new int[]{0}, new int[]{0}, new int[]{0}, 0.0)[1]));
        check("方块集合的局部 AABB = x∈[0,3]（块占 [x, x+1)，不是 [x−0.5, x+0.5]）",
                Math.abs(bounds[0].x) < 1e-9 && Math.abs(bounds[1].x - 3.0) < 1e-9,
                "实得 [" + fmt(bounds[0]) + ", " + fmt(bounds[1]) + "]");
        Quaterniond rotY90 = new Quaterniond().rotateY(Math.toRadians(90.0));
        Vector3d bodyPos = new Vector3d(10, 0, 0);
        // 从 (5,0,-2) 朝 **+X** 打：旋转后盒子在世界 z∈[-2,0)（局部 +X 被转到世界 −Z），
        // 所以这根射线在 z=-2 处**打在覆盖范围内**（且局部命中点 x=2 ∈ [0,3]）；
        // 未旋转的盒子 z 只到 [0,1) ⇒ 打不到。
        // 这一对才是真正能分辨"有没有做旋转"的。（第一版我写反了方向，方向也要算，别只算盒子。）
        Vector3d lo = RayBox.toLocalPoint(bodyPos, rotY90, 5, 0, -2);
        Vector3d ld = RayBox.toLocalDirection(rotY90, 1, 0, 0);
        double tRot = RayBox.intersect(lo, ld, bounds[0], bounds[1]);
        check("体转 90°：从 (5,0,-2) 朝 +X 打 ⇒ 命中（t=5.0）",
                Math.abs(tRot - 5.0) < 1e-9, "实得 " + fmt(tRot));
        double tNoRot = RayBox.intersect(RayBox.toLocalPoint(bodyPos, new Quaterniond(), 5, 0, -2),
                RayBox.toLocalDirection(new Quaterniond(), 1, 0, 0), bounds[0], bounds[1]);
        check("同一根射线若按「未旋转」算 ⇒ 打不到（证明这组用例真的在分辨旋转）",
                Double.isNaN(tNoRot), "实得 " + fmt(tNoRot));
        System.out.println();

        System.out.println(fails == 0 ? "==== 规则判据 PASS ====" : ("==== 有 " + fails + " 项 FAIL ===="));
        if (fails != 0) {
            System.exit(1);
        }
    }

    private static void decideCase(String what, boolean occupied, boolean intersects, boolean adjacent,
                                   int bodyCount, SpaceBuildRules.Decision want) {
        SpaceBuildRules.Decision got = SpaceBuildRules.decide(occupied, intersects, adjacent, bodyCount);
        check(what, got == want, "期望 " + want + "，实得 " + got);
    }

    private static String fmt(int[] v) {
        return String.format("实得 (%d,%d,%d)", v[0], v[1], v[2]);
    }

    /** double 版（射线参量 t）：NaN 打印成「未命中」，避免看成 0。 */
    private static String fmt(double v) {
        return Double.isNaN(v) ? "未命中" : String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    /** Vector3d 版（AABB 角点）。 */
    private static String fmt(Vector3d v) {
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)", v.x, v.y, v.z);
    }
}
