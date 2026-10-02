import com.mss.polymech.physics.RayBox;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 牵引枪遗留缺口 (2) 的离线判据：<b>体局部格 ⇄ 世界格</b>的映射必须含刚体旋转。
 *
 * <h2>被修的缺陷（2026-10-01）</h2>
 * {@code PhysicsBodyTracker.findAdjacentBody} / {@code placeBlockAt} 原来把局部格换算成世界格用的是
 * <pre>
 *   world = tracked.origin + (dx, dy, dz)          // 轴对齐，不含旋转
 * </pre>
 * 而 {@code origin} 是<b>建体时</b>区域的最小角、{@code withTransform} 从不更新它。两个后果：
 * <ol>
 *   <li><b>体转过之后全错</b>：方块真实世界位置不再是 {@code origin + 局部}；</li>
 *   <li><b>体只平移过也错</b>：{@code origin} 是冻结的，体挪走之后它指的还是老地方。</li>
 * </ol>
 * 症状：在转过/挪过的物理体旁边放方块，要么吸不上去、要么吸进错误的格子。
 *
 * <h2>这一版怎么修的</h2>
 * 改判在<b>体局部空间</b>：把世界格变换到体局部（{@link RayBox#toLocalCell}，就是
 * {@code RayBox.toLocalPoint} 再逐轴 floor），再与体的方块列表比曼哈顿距离。
 * 局部空间里方块永远是轴对齐整数格，曼哈顿判据在那里才良定义；旋转天然被算进去，
 * 而且不再依赖冻结的 {@code origin}。
 *
 * <h2>为什么这个探针能钉住修复</h2>
 * 新映射<b>直接调用真实现</b>（{@link RayBox#toLocalCell} / {@link RayBox#toWorldCenter}，纯函数、
 * 零 MC 依赖），不是复刻。旧映射在本探针里是<b>被反驳的对象</b>，复刻它才是对的
 * （它已经从源码里删掉，无法再用源码契约钉 —— 反过来，"源码里不得再出现它"由
 * {@code check-artifact.ps1} 的 mustNot 契约钉住）。
 *
 * <p>最关键的一条是 A 组：<b>恒等旋转 + 体未移动时，新映射必须与旧映射逐位一致</b> ——
 * 这证明本次修复对"没转过也没挪过的体"是<b>行为中性</b>的，不是顺手改了语义。</p>
 */
public final class BodyLocalMappingProbe {

    private BodyLocalMappingProbe() {
    }

    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.printf("  [PASS] %-52s %s%n", name, detail); }
        else    { failed++; System.out.printf("  [FAIL] %-52s %s%n", name, detail); }
    }

    private static final double EPS = 1.0e-9;

    // ── 被反驳的旧映射（轴对齐、不含旋转）—— 只在本探针里存在，源码里已删 ──
    private static int[] oldLocalOf(double[] origin, int wx, int wy, int wz) {
        return new int[]{
                (int) Math.floor(wx - origin[0]),
                (int) Math.floor(wy - origin[1]),
                (int) Math.floor(wz - origin[2])};
    }

    private static double[] oldWorldCenterOf(double[] origin, int dx, int dy, int dz) {
        return new double[]{origin[0] + dx + 0.5, origin[1] + dy + 0.5, origin[2] + dz + 0.5};
    }

    private static final Quaterniond IDENTITY = new Quaterniond();

    public static void main(String[] args) {
        System.out.println("=== BodyLocalMappingProbe: 体局部格 ⇄ 世界格（含刚体旋转）===");
        System.out.println();

        // 一个"体位置 == 建体原点"的体，先在最简单的情形下确认行为中性
        Vector3d originPos = new Vector3d(100.0, 64.0, -200.0);
        double[] originArr = {originPos.x, originPos.y, originPos.z};

        System.out.println("--- A 组：★ 行为中性（恒等旋转 + 体未移动 ⇒ 新映射 == 旧映射）---");
        {
            boolean allWorldSame = true;
            boolean allLocalSame = true;
            double maxWorldDiff = 0.0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        Vector3d now = RayBox.toWorldCenter(originPos, IDENTITY, dx, dy, dz);
                        double[] was = oldWorldCenterOf(originArr, dx, dy, dz);
                        maxWorldDiff = Math.max(maxWorldDiff, Math.abs(now.x - was[0]));
                        maxWorldDiff = Math.max(maxWorldDiff, Math.abs(now.y - was[1]));
                        maxWorldDiff = Math.max(maxWorldDiff, Math.abs(now.z - was[2]));
                        if (Math.abs(now.x - was[0]) > EPS
                                || Math.abs(now.y - was[1]) > EPS
                                || Math.abs(now.z - was[2]) > EPS) {
                            allWorldSame = false;
                        }
                    }
                }
            }
            check("局部格→世界心：新映射与旧映射逐位一致", allWorldSame,
                    String.format("343 个格，最大差 %.3e", maxWorldDiff));

            int[] cell = new int[3];
            int checked = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        // 旧映射算出的世界格，再喂回新的反算 ⇒ 必须回到同一个局部格
                        int wx = (int) originArr[0] + dx;
                        int wy = (int) originArr[1] + dy;
                        int wz = (int) originArr[2] + dz;
                        RayBox.toLocalCell(originPos, IDENTITY, wx, wy, wz, cell);
                        int[] was = oldLocalOf(originArr, wx, wy, wz);
                        checked++;
                        if (cell[0] != was[0] || cell[1] != was[1] || cell[2] != was[2]) {
                            allLocalSame = false;
                        }
                    }
                }
            }
            check("世界格→局部格：新映射与旧映射逐位一致", allLocalSame,
                    String.format("%d 个格", checked));
        }

        System.out.println();
        System.out.println("--- B 组：★ 反例 —— 体绕 Y 转 90° 后，旧映射指向错误的世界格 ---");
        {
            // 绕 Y 转 90°：R·(x,y,z) = (z, y, -x)
            Quaterniond q90 = new Quaterniond().rotateY(Math.PI / 2);
            int dx = 2;
            int dy = 0;
            int dz = 0;
            Vector3d now = RayBox.toWorldCenter(originPos, q90, dx, dy, dz);
            double[] was = oldWorldCenterOf(originArr, dx, dy, dz);

            // 旧映射给 (102.5, 64.5, -199.5)；新映射给 (100.5, 64.5, -201.5)
            double diff = Math.hypot(Math.hypot(now.x - was[0], now.y - was[1]), now.z - was[2]);
            check("转 90° 后旧映射的世界心是错的（差 ≥ 2 格）", diff >= 2.0 - EPS,
                    String.format("旧=(%.1f,%.1f,%.1f) 新=(%.1f,%.1f,%.1f) 差=%.3f",
                            was[0], was[1], was[2], now.x, now.y, now.z, diff));

            // 关键：用"新的反算"去算旧映射给的那个世界格，得到的局部格不是 (2,0,0)
            int[] cellOfOld = new int[3];
            RayBox.toLocalCell(originPos, q90,
                    (int) Math.floor(was[0]), (int) Math.floor(was[1]), (int) Math.floor(was[2]),
                    cellOfOld);
            boolean oldIsWrongCell = cellOfOld[0] != dx || cellOfOld[1] != dy || cellOfOld[2] != dz;
            check("旧映射指的世界格 ≠ 真实局部格 (2,0,0)", oldIsWrongCell,
                    String.format("旧世界格→局部 = (%d,%d,%d)", cellOfOld[0], cellOfOld[1], cellOfOld[2]));

            // 而"新的正算"给的世界格，喂回"新的反算"必然回到 (2,0,0)
            int[] cellOfNew = new int[3];
            RayBox.toLocalCell(originPos, q90,
                    (int) Math.floor(now.x), (int) Math.floor(now.y), (int) Math.floor(now.z),
                    cellOfNew);
            check("新映射指的世界格 ⇒ 回到真实局部格 (2,0,0)",
                    cellOfNew[0] == dx && cellOfNew[1] == dy && cellOfNew[2] == dz,
                    String.format("(%d,%d,%d)", cellOfNew[0], cellOfNew[1], cellOfNew[2]));
        }

        System.out.println();
        System.out.println("--- C 组：★ 逆变换性质（分三层，别把量化极限当成 bug）---");
        {
            Quaterniond[] rots = {
                    new Quaterniond(),
                    new Quaterniond().rotateY(Math.PI / 2),
                    new Quaterniond().rotateX(Math.PI / 2),
                    new Quaterniond().rotateZ(Math.PI),
                    new Quaterniond().rotateY(Math.PI / 2).rotateX(Math.PI / 2),
                    new Quaterniond().rotateAxis(0.7, 0.3, -0.5, 0.2),
                    new Quaterniond().rotateAxis(2.9, -0.11, 0.93, 0.35)};
            // 前 5 组是 90° 的整数倍 ⇒ 把整数点阵映到自身（"格对齐"）；后 2 组是斜轴
            int alignedCount = 5;
            for (Quaterniond r : rots) {
                r.normalize();
            }
            // 体位置故意偏离建体原点，顺带证明"不再依赖 origin"
            Vector3d pos = new Vector3d(originPos).add(7.3, -2.9, 11.1);

            // C1：实值逆变换必须**精确**（这一条对所有旋转都成立）
            boolean allReal = true;
            double worstReal = 0.0;
            for (Quaterniond rot : rots) {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dy = -4; dy <= 4; dy++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            Vector3d w = RayBox.toWorldCenter(pos, rot, dx, dy, dz);
                            Vector3d back = RayBox.toLocalPoint(pos, rot, w.x, w.y, w.z);
                            double e = Math.max(Math.abs(back.x - (dx + 0.5)),
                                    Math.max(Math.abs(back.y - (dy + 0.5)),
                                            Math.abs(back.z - (dz + 0.5))));
                            worstReal = Math.max(worstReal, e);
                            if (e > 1.0e-9) {
                                allReal = false;
                            }
                        }
                    }
                }
            }
            check("★ 实值逆变换精确（局部→世界→局部 = 原值）", allReal,
                    String.format("7 组旋转 × 729 格，最大差 %.3e", worstReal));

            // C2：格对齐旋转（90° 整数倍）⇒ 整数格往返必须**精确**
            boolean allAligned = true;
            for (int i = 0; i < alignedCount; i++) {
                int[] cell = new int[3];
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dy = -4; dy <= 4; dy++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            Vector3d w = RayBox.toWorldCenter(pos, rots[i], dx, dy, dz);
                            RayBox.toLocalCell(pos, rots[i],
                                    (int) Math.floor(w.x), (int) Math.floor(w.y), (int) Math.floor(w.z),
                                    cell);
                            if (cell[0] != dx || cell[1] != dy || cell[2] != dz) {
                                allAligned = false;
                            }
                        }
                    }
                }
            }
            check("★ 格对齐旋转（90° 整数倍）整数格往返精确", allAligned, "5 组 × 729 格");

            // C3：斜轴旋转 ⇒ 整数格往返**有界**（≤1 格），这是网格量化的固有极限，不是缺陷。
            //     理由：floor(世界心) 把连续点吸附到世界整数格，而斜轴下局部点阵与世界点阵
            //     本就错位，两个格心最大相距 0.5·√3 ⇒ 反算最多落到相邻格。
            int worstOblique = 0;
            for (int i = alignedCount; i < rots.length; i++) {
                int[] cell = new int[3];
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dy = -4; dy <= 4; dy++) {
                        for (int dz = -4; dz <= 4; dz++) {
                            Vector3d w = RayBox.toWorldCenter(pos, rots[i], dx, dy, dz);
                            RayBox.toLocalCell(pos, rots[i],
                                    (int) Math.floor(w.x), (int) Math.floor(w.y), (int) Math.floor(w.z),
                                    cell);
                            worstOblique = Math.max(worstOblique,
                                    Math.max(Math.abs(cell[0] - dx),
                                            Math.max(Math.abs(cell[1] - dy), Math.abs(cell[2] - dz))));
                        }
                    }
                }
            }
            check("斜轴旋转：整数格往返误差有界（≤1 格 = 量化极限）", worstOblique <= 1,
                    String.format("实测最大偏差 %d 格（不是无界漂移）", worstOblique));

            // C4：对照 —— 旧映射的误差随"体位移"增长，是**无界**的
            int worstOld = 0;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dy = -4; dy <= 4; dy++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        Vector3d w = RayBox.toWorldCenter(pos, rots[0], dx, dy, dz);
                        int[] oldCell = oldLocalOf(originArr,
                                (int) Math.floor(w.x), (int) Math.floor(w.y), (int) Math.floor(w.z));
                        worstOld = Math.max(worstOld,
                                Math.max(Math.abs(oldCell[0] - dx),
                                        Math.max(Math.abs(oldCell[1] - dy), Math.abs(oldCell[2] - dz))));
                    }
                }
            }
            check("对照：旧映射在同一场景误差远大于 1 格（无界）", worstOld > 1,
                    String.format("旧映射最大偏差 %d 格（新映射 ≤ %d）", worstOld, worstOblique));
        }

        System.out.println();
        System.out.println("--- D 组：★ 平移（不旋转）也必须对 —— 旧映射用冻结的 origin，这里会错 ---");
        {
            // 体从建体原点被推走了 (10,0,0)，但没有旋转
            Vector3d movedPos = new Vector3d(originPos).add(10.0, 0.0, 0.0);
            int dx = 0;
            int dy = 0;
            int dz = 0;
            Vector3d now = RayBox.toWorldCenter(movedPos, IDENTITY, dx, dy, dz);
            double[] was = oldWorldCenterOf(originArr, dx, dy, dz);
            check("平移 10 格后旧映射仍指向老位置（差 10 格）",
                    Math.abs(now.x - was[0]) > 9.9,
                    String.format("旧 x=%.1f 新 x=%.1f", was[0], now.x));

            int[] cell = new int[3];
            RayBox.toLocalCell(movedPos, IDENTITY,
                    (int) Math.floor(now.x), (int) Math.floor(now.y), (int) Math.floor(now.z), cell);
            check("平移后新映射仍回到正确的局部格 (0,0,0)",
                    cell[0] == 0 && cell[1] == 0 && cell[2] == 0,
                    String.format("(%d,%d,%d)", cell[0], cell[1], cell[2]));

            // 旧映射反算一个"贴着平移后体"的世界格，会算出一个不存在的远处局部格
            int[] oldCell = oldLocalOf(originArr,
                    (int) Math.floor(now.x) + 1, (int) Math.floor(now.y), (int) Math.floor(now.z));
            check("旧反算把相邻格算成远处局部格（≠1）",
                    Math.abs(oldCell[0] - dx) > 1,
                    String.format("旧反算局部 = (%d,%d,%d)", oldCell[0], oldCell[1], oldCell[2]));
        }

        System.out.println();
        System.out.println("--- E 组：★ 邻接判据改在局部空间后，旋转体也能正确吸附 ---");
        {
            // 场景：一个沿局部 X 摆 3 格的体，绕 Y 转 90°，被推到某处。
            // 玩家在"旋转后第 3 格的外侧面"放方块 ⇒ 应该被识别为面相邻并吸进局部格 (3,0,0)。
            Quaterniond q90 = new Quaterniond().rotateY(Math.PI / 2);
            Vector3d pos = new Vector3d(originPos);
            int lastDx = 2;                       // 体现有局部格 0,1,2
            // 该格外侧面（局部 +X 方向再外一格）在世界里的位置：
            // 局部 (3,0,0) 的块心就是玩家要放的那一格
            Vector3d target = RayBox.toWorldCenter(pos, q90, lastDx + 1, 0, 0);
            int wx = (int) Math.floor(target.x);
            int wy = (int) Math.floor(target.y);
            int wz = (int) Math.floor(target.z);

            // 新的判据：世界格 → 局部格，再与方块列表比曼哈顿距离
            int[] cell = new int[3];
            RayBox.toLocalCell(pos, q90, wx, wy, wz, cell);
            int manhattanNew = 0;
            int best = Integer.MAX_VALUE;
            for (int ex = 0; ex <= lastDx; ex++) {
                best = Math.min(best, Math.abs(ex - cell[0]) + Math.abs(0 - cell[1]) + Math.abs(0 - cell[2]));
            }
            manhattanNew = best;
            check("新判据：旋转体的外侧格判为面相邻（曼哈顿 1）", manhattanNew == 1,
                    String.format("局部格 (%d,%d,%d) ⇒ 曼哈顿 %d", cell[0], cell[1], cell[2], manhattanNew));

            // 旧判据：把方块的世界格与"origin + 局部"比曼哈顿 —— 旋转后全错
            int manhattanOld = Integer.MAX_VALUE;
            for (int ex = 0; ex <= lastDx; ex++) {
                double[] wc = oldWorldCenterOf(originArr, ex, 0, 0);
                int ox = (int) Math.floor(wc[0]);
                int oy = (int) Math.floor(wc[1]);
                int oz = (int) Math.floor(wc[2]);
                manhattanOld = Math.min(manhattanOld,
                        Math.abs(ox - wx) + Math.abs(oy - wy) + Math.abs(oz - wz));
            }
            check("旧判据：同一个格子判为不相邻（曼哈顿 ≠ 1）⇒ 放不上去", manhattanOld != 1,
                    String.format("旧判据曼哈顿 = %d", manhattanOld));
        }

        System.out.println();
        System.out.println("--- F 组：退化输入（不该抛异常、不该产生 NaN）---");
        {
            int[] cell = new int[3];
            RayBox.toLocalCell(originPos, IDENTITY, 0, 0, 0, cell);
            check("世界原点也能反算（不抛异常）",
                    cell[0] == -(int) originPos.x && cell[1] == -(int) originPos.y
                            && cell[2] == -(int) originPos.z,
                    String.format("(%d,%d,%d)", cell[0], cell[1], cell[2]));

            // 非单位四元数：toWorldCenter/toLocalCell 只做 transform，不 normalize，
            // 但必须是有限值（喂进 floor 不会炸）
            Quaterniond dirty = new Quaterniond(0.5, 0.5, 0.5, 0.5).mul(3.0);
            Vector3d w = RayBox.toWorldCenter(originPos, dirty, 1, 2, 3);
            check("非单位四元数不产生 NaN/Inf",
                    Double.isFinite(w.x) && Double.isFinite(w.y) && Double.isFinite(w.z),
                    String.format("(%.2f,%.2f,%.2f)", w.x, w.y, w.z));
        }

        System.out.println();
        System.out.printf("=== BodyLocalMappingProbe: %d/%d 通过，失败 %d ===%n",
                passed, passed + failed, failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
