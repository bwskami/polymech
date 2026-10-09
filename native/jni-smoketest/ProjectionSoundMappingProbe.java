import com.mss.polymech.physics.RayBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * <b>音效搬运</b>的离线判据：投影维度里的<b>一个点</b>要原样搬到物理体所在世界。
 *
 * <h2>被钉住的三件事</h2>
 * <ol>
 *   <li><b>往返闭合</b>：{@link RayBox#toWorldPoint} 与 {@link RayBox#toLocalPoint} 在任意刚体姿态下
 *       必须互逆（体被转过/挪过之后音效不能飞走）。含<b>落格稳定性</b>：映射回去 floor 之后
 *       必须还是原来那一格，否则破坏碎屑会落到隔壁格。</li>
 *   <li><b>单一来源</b>：{@link RayBox#toWorldCenter} 必须是 {@code toWorldPoint(l+0.5)} ——
 *       "格心"就是"点"的特例。两条路各写一遍必然漂移（本项目在"同一概念两份实现"上吃过亏），
 *       所以这里<b>直接断言它们逐位相等</b>，而不是各自算一遍"看起来对"。</li>
 *   <li><b>点级映射是必需的</b>：反例组模拟"先把点 floor 成格、再按格心搬"
 *       （{@code localToWorld(dx,dy,dz)} 只到格心的那条路），断言它的误差能超过半格、
 *       且<b>确实</b>会把落点送进另一个世界格 —— 于是"音效必须点级映射"有了证据，
 *       不是嘴上说说。</li>
 * </ol>
 *
 * <p><b>本探针直接调真实现</b>（{@code RayBox} 是纯函数、零 MC 依赖），不是复刻 ——
 * 复刻的断言只能钉住复刻自己。</p>
 *
 * <p>跑法（同其它探针，见 docs/mps-clone-plan.md 第 13 节）：
 * <pre>
 *   javac -encoding UTF-8 -cp "build\classes\java\main;&lt;joml&gt;" -d &lt;临时目录&gt; ProjectionSoundMappingProbe.java
 *   java -Dstdout.encoding=UTF-8 -cp "&lt;临时目录&gt;;build\classes\java\main;&lt;joml&gt;" ProjectionSoundMappingProbe
 * </pre>
 * 退出码非 0 = 有 FAIL。临时目录必须先清空（残留旧 class 会掩盖真实结果）。</p>
 */
public final class ProjectionSoundMappingProbe {

    private ProjectionSoundMappingProbe() {
    }

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  [PASS] %-56s %s%n", name, detail); }
        else    { failed++; System.out.printf("  [FAIL] %-56s %s%n", name, detail); }
    }

    private static final double EPS = 1.0e-9;

    // 若干"体被转过 + 挪过"的姿态（含 45°、90°、接近 180° 的组合）
    private static final Quaterniond[] ROTATIONS = {
            new Quaterniond(),
            new Quaterniond().rotateY(Math.PI / 4),
            new Quaterniond().rotateX(Math.PI / 2).rotateZ(Math.PI / 3),
            new Quaterniond().rotateY(Math.PI / 2).rotateX(-Math.PI / 5),
            new Quaterniond().rotateXYZ(0.7, -1.9, 2.6),
            new Quaterniond().rotateY(Math.PI - 0.02),
    };

    private static final Vector3d[] BODY_POS = {
            new Vector3d(0.0, 64.0, 0.0),
            new Vector3d(1234.5, -88.25, -9021.75),
            new Vector3d(-3.0e5, 4.0e4, 7.5e4),   // 太空里的远距离体（大坐标 = 浮点最吃紧的一档）
    };

    /** 局部点 → 世界点（调真实现）。 */
    private static Vector3d worldOf(int r, int p, double lx, double ly, double lz) {
        return RayBox.toWorldPoint(BODY_POS[p], ROTATIONS[r], lx, ly, lz);
    }

    public static void main(String[] args) {
        System.out.println("=== ProjectionSoundMappingProbe: 投影点 → 世界点（音效搬运）===");
        System.out.println();

        System.out.println("--- A1 组：toWorldPoint ⇄ toLocalPoint 往返闭合（任意姿态）---");
        double worst = 0.0;
        for (int r = 0; r < ROTATIONS.length; r++) {
            for (int p = 0; p < BODY_POS.length; p++) {
                for (double t = 0.0; t < 1.0; t += 0.125) {
                    Vector3d local = new Vector3d(-7.0 + t, 3.5 + t * 0.5, 42.0 - t);
                    Vector3d world = worldOf(r, p, local.x, local.y, local.z);
                    Vector3d back = RayBox.toLocalPoint(BODY_POS[p], ROTATIONS[r], world.x, world.y, world.z);
                    double err = Math.max(Math.max(Math.abs(back.x - local.x), Math.abs(back.y - local.y)),
                            Math.abs(back.z - local.z));
                    worst = Math.max(worst, err);
                }
            }
        }
        check("任意姿态下点映射往返闭合", worst < EPS,
                String.format("最大误差=%.3e（门槛 %.0e）", worst, EPS));

        System.out.println();
        System.out.println("--- A2 组：搬运后落格稳定（反查 floor 仍是原格 ⇒ 碎屑不落到隔壁格）---");
        // 采样只取<b>格内部</b>的点（偏移 ∈ (0,1)，刻意不碰 0/1 两条边界）：
        // 恰好落在格边界上的点（偏移恰为 0）经过一次浮点旋转后会差 1e-11，floor 必然可能翻到邻格 ——
        // 这是所有浮点几何的共性，不是本实现的缺陷；而且本项目<b>从不对局部点取整</b>：
        // 搬运只做正向（局部点 → 世界点），唯一的取整是 levelEvent 那步取世界格（整数格是它的入参形状）。
        boolean allSameCell = true;
        String badCase = "—";
        for (int r = 0; r < ROTATIONS.length && allSameCell; r++) {
            for (int p = 0; p < BODY_POS.length && allSameCell; p++) {
                for (int dx = -4; dx <= 4 && allSameCell; dx += 2) {
                    for (int dy = 0; dy <= 3 && allSameCell; dy++) {
                        for (int dz = -4; dz <= 4 && allSameCell; dz += 2) {
                            for (double ox = 0.05; ox <= 0.95 && allSameCell; ox += 0.11) {
                                for (double oz = 0.05; oz <= 0.95 && allSameCell; oz += 0.11) {
                                    // 格 (dx,dy,dz) 内部的一点（音效/事件落点就是这样的连续值）
                                    Vector3d local = new Vector3d(dx + ox, dy + 0.5, dz + oz);
                                    Vector3d world = worldOf(r, p, local.x, local.y, local.z);
                                    Vector3d back = RayBox.toLocalPoint(BODY_POS[p], ROTATIONS[r],
                                            world.x, world.y, world.z);
                                    int cx = (int) Math.floor(back.x);
                                    int cy = (int) Math.floor(back.y);
                                    int cz = (int) Math.floor(back.z);
                                    if (cx != dx || cy != dy || cz != dz) {
                                        allSameCell = false;
                                        badCase = String.format(
                                                "姿态%d 体%s 格(%d,%d,%d) 内点(%.2f,·,%.2f) → 反查(%d,%d,%d)",
                                                r, BODY_POS[p], dx, dy, dz, ox, oz, cx, cy, cz);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        check("搬运后反查格 == 原局部格", allSameCell, allSameCell ? "全部一致" : ("反例：" + badCase));

        System.out.println();
        System.out.println("--- B 组：格心是点的特例 —— toWorldCenter 必须 == toWorldPoint(l+0.5) ---");
        double worstCenter = 0.0;
        for (int r = 0; r < ROTATIONS.length; r++) {
            for (int p = 0; p < BODY_POS.length; p++) {
                for (int dx = -5; dx <= 5; dx += 5) {
                    for (int dy = -2; dy <= 2; dy += 2) {
                        for (int dz = -3; dz <= 3; dz += 3) {
                            Vector3d center = RayBox.toWorldCenter(BODY_POS[p], ROTATIONS[r], dx, dy, dz);
                            Vector3d point = worldOf(r, p, dx + 0.5, dy + 0.5, dz + 0.5);
                            worstCenter = Math.max(worstCenter, center.distance(point));
                        }
                    }
                }
            }
        }
        check("两条实现逐位一致（同一概念只有一份）", worstCenter == 0.0,
                String.format("最大差=%.3e", worstCenter));

        System.out.println();
        System.out.println("--- C 组（反例）：先 floor 成格、再按格心搬 —— 误差会超过半格 ---");
        // 反例写法 = localToWorld(dx,dy,dz) 那条只到格心的路：把格内一点换成它的格心
        double worstApprox = 0.0;
        for (int r = 0; r < ROTATIONS.length; r++) {
            for (int p = 0; p < BODY_POS.length; p++) {
                for (int dx = -3; dx <= 3; dx += 3) {
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int dz = -3; dz <= 3; dz += 3) {
                            for (double ox = -0.5; ox < 0.5; ox += 0.25) {
                                for (double oz = -0.5; oz < 0.5; oz += 0.25) {
                                    Vector3d exact = worldOf(r, p, dx + 0.5 + ox, dy + 0.5, dz + 0.5 + oz);
                                    Vector3d approx = RayBox.toWorldCenter(BODY_POS[p], ROTATIONS[r], dx, dy, dz);
                                    worstApprox = Math.max(worstApprox, exact.distance(approx));
                                }
                            }
                        }
                    }
                }
            }
        }
        check("格心近似的最大偏移 > 0.5 格（半格以内才不用点级映射）", worstApprox > 0.5,
                String.format("最大偏移=%.3f 格（同格内点的理论上限 √3/2≈%.3f）",
                        worstApprox, Math.sqrt(3.0) / 2.0));

        System.out.println();
        System.out.println("--- C2 组（反例的杀伤面）：格心近似会把落点送进另一个世界格 ---");
        int wrongCell = 0;
        int total = 0;
        double worstCellShift = 0.0;
        for (int r = 0; r < ROTATIONS.length; r++) {
            for (int p = 0; p < BODY_POS.length; p++) {
                for (int dx = -3; dx <= 3; dx += 3) {
                    for (int dy = 0; dy <= 2; dy++) {
                        for (int dz = -3; dz <= 3; dz += 3) {
                            for (double ox = -0.5; ox < 0.5; ox += 0.1) {
                                for (double oz = -0.5; oz < 0.5; oz += 0.1) {
                                    Vector3d exact = worldOf(r, p, dx + 0.5 + ox, dy + 0.5, dz + 0.5 + oz);
                                    Vector3d approx = RayBox.toWorldCenter(BODY_POS[p], ROTATIONS[r], dx, dy, dz);
                                    total++;
                                    // 客户端拿到的是 BlockPos（levelEvent 只吃整数格）→ 各自落到哪个世界格
                                    int ex = (int) Math.floor(exact.x);
                                    int ey = (int) Math.floor(exact.y);
                                    int ez = (int) Math.floor(exact.z);
                                    int ax = (int) Math.floor(approx.x);
                                    int ay = (int) Math.floor(approx.y);
                                    int az = (int) Math.floor(approx.z);
                                    if (ex != ax || ey != ay || ez != az) {
                                        wrongCell++;
                                        worstCellShift = Math.max(worstCellShift,
                                                Math.max(Math.max(Math.abs(ex - ax), Math.abs(ey - ay)),
                                                        Math.abs(ez - az)));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        check("格心近似确实会落进另一个世界格（反例有效）", wrongCell > 0,
                String.format("%d/%d 个采样落错格，最大错开 %d 格", wrongCell, total, (int) worstCellShift));

        System.out.println();
        System.out.printf("=== ProjectionSoundMappingProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
