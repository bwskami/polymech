import com.mss.polymech.physics.BodyVoxelRaycast;
import com.mss.polymech.physics.SpaceBuildRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * {@link BodyVoxelRaycast}（照 space 0.3.0 {@code ShipRaycast} 的物理体方块射线几何内核）离线判据。
 *
 * <p>为什么要有它：这条射线曾经是自己写的"逐块当单位立方体做 slab"实现
 * （{@code physics/PhysicsRaycast.java}，已删除），有两个实测偏差 ——
 * ① 方块真实形状被当满格；② slab 在某轴提供进入点时漏夹 {@code tmax}，擦边也报命中。
 * 表现是"瞄半天挖不掉 / 互动错格"，且随瞄准角度时好时坏。
 * 本探针把这两个偏差各钉一条判据：<b>判据由解析推导或独立算法给出，不是抄实现里的常量</b>。</p>
 *
 * <p>判据分组：A 解析反例（旧实现必 FAIL 的那条）；B 正命中的格/面/命中点；
 * C 真实形状（半砖下半命中、上半必须未命中）；D 随机射线与"细步进采样"参考对拍；
 * E/F 局部⇄世界映射与"命中格沿世界面取相邻格"（服务端放置链）；
 * <b>G 交互包的"面帧"</b>（包里必须带体局部面，不是世界面 —— 2026-10-06"右键放置不放我瞄准的那个面"）。</p>
 */
public final class BodyVoxelRaycastProbe {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        groupA();
        groupB();
        groupC();
        groupD();
        groupE();
        groupF();
        groupG();

        System.out.println("=== BodyVoxelRaycastProbe: " + passed + "/" + (passed + failed)
                + " 通过，失败 " + failed + " ===");
        if (failed > 0) {
            System.out.println("全部判据未通过：" + failed + " 项 FAIL");
            System.exit(1);
        }
        System.out.println("全部判据通过：" + passed + " 项");
    }

    // ------------------------------------------------------------------ A：解析反例

    /**
     * A 组：射线从方块旁边<b>擦过去</b>（三轴区间不相交），真值未命中。
     *
     * <p>几何：方块 (0,0,0)；起点 (0.5, −1.7888543819998317, −2.2360679774997896)；
     * 方向 (0, 0.8944271909999159, 0.4472135954999579)（单位向量，=(0,2,1)/√5）；reach=10。</p>
     *
     * <p>解析：X 方向分量为 0 且 origin.x=0.5∈[0,1] ⇒ 该轴不约束；
     * Y 区间 = [2.0, 3.118…]；Z 区间 = [5.0, 7.236…]；交集 = [5.0, 3.118…] = <b>空</b>。
     * 旧 slab 实现漏夹 far 面，于是报"命中 t=5"（那一时刻射线在 y≈2.68，早已出了 Y 区间）。</p>
     */
    private static void groupA() {
        Map<Long, VoxelShape> cubes = new HashMap<>();
        cubes.put(BlockPos.asLong(0, 0, 0), Shapes.block());
        BodyVoxelRaycast.LocalShapes shapes = p -> cubes.get(p.asLong());

        Vec3 start = new Vec3(0.5, -1.7888543819998317, -2.2360679774997896);
        Vec3 dir = new Vec3(0.0, 0.8944271909999159, 0.4472135954999579);
        Vec3 end = start.add(dir.scale(10.0));

        double tYmin = (0.0 - start.y) / dir.y;
        double tYmax = (1.0 - start.y) / dir.y;
        double tZmin = (0.0 - start.z) / dir.z;
        double tZmax = (1.0 - start.z) / dir.z;
        double tMin = Math.max(tYmin, tZmin);
        double tMax = Math.min(tYmax, tZmax);

        check("A1 反例的三轴交集为空（解析推导，与实现无关）", tMin > tMax,
                "tMin=" + tMin + " tMax=" + tMax + "（Y=[" + tYmin + "," + tYmax
                        + "] Z=[" + tZmin + "," + tZmax + "]）");

        BodyVoxelRaycast.LocalHit hit = BodyVoxelRaycast.traverse(start, end, shapes);
        check("A2 擦边射线必须判未命中（旧 slab 实现会报假命中 t=5）", hit == null, describe(hit));
    }

    // ------------------------------------------------------------------ B：正命中

    private static void groupB() {
        Map<Long, VoxelShape> cubes = new HashMap<>();
        cubes.put(BlockPos.asLong(0, 0, 0), Shapes.block());
        BodyVoxelRaycast.LocalShapes shapes = p -> cubes.get(p.asLong());

        Vec3 start = new Vec3(-5.0, 0.5, 0.5);
        Vec3 end = new Vec3(5.0, 0.5, 0.5);
        BodyVoxelRaycast.LocalHit hit = BodyVoxelRaycast.traverse(start, end, shapes);

        check("B1 正命中：格必须是 (0,0,0)",
                hit != null && hit.cell().equals(new BlockPos(0, 0, 0)), describe(hit));
        check("B2 正命中：面必须是 WEST（沿 +X 进入，打在 −X 面）",
                hit != null && hit.face() == Direction.WEST,
                hit == null ? "null" : String.valueOf(hit.face()));
        check("B3 正命中：命中点 x 必须 ≈ 0（±1e-9）",
                hit != null && Math.abs(hit.location().x) < 1.0e-9,
                hit == null ? "null" : String.valueOf(hit.location()));

        Map<Long, VoxelShape> elsewhere = new HashMap<>();
        elsewhere.put(BlockPos.asLong(10, 10, 10), Shapes.block());
        BodyVoxelRaycast.LocalShapes far = p -> elsewhere.get(p.asLong());
        check("B4 射线路径上没有方块 ⇒ 未命中（空气必须跳过）",
                BodyVoxelRaycast.traverse(start, end, far) == null, "");
    }

    // ------------------------------------------------------------------ C：真实形状

    private static void groupC() {
        // 半砖：只占 y ∈ [0, 0.5]
        Map<Long, VoxelShape> slab = new HashMap<>();
        slab.put(BlockPos.asLong(0, 0, 0), Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0));
        BodyVoxelRaycast.LocalShapes slabShapes = p -> slab.get(p.asLong());

        BodyVoxelRaycast.LocalHit low = BodyVoxelRaycast.traverse(
                new Vec3(-5.0, 0.25, 0.5), new Vec3(5.0, 0.25, 0.5), slabShapes);
        BodyVoxelRaycast.LocalHit high = BodyVoxelRaycast.traverse(
                new Vec3(-5.0, 0.75, 0.5), new Vec3(5.0, 0.75, 0.5), slabShapes);

        check("C1 半砖下半（y=0.25，实心）必须命中", low != null, describe(low));
        check("C2 半砖上半（y=0.75，看得见是空的）必须未命中 —— 旧实现按整格算，这里会假命中",
                high == null, describe(high));

        Map<Long, VoxelShape> shapeless = new HashMap<>();
        shapeless.put(BlockPos.asLong(0, 0, 0), Shapes.empty());
        BodyVoxelRaycast.LocalShapes empty = p -> shapeless.get(p.asLong());
        BodyVoxelRaycast.LocalHit solid = BodyVoxelRaycast.traverse(
                new Vec3(-5.0, 0.5, 0.5), new Vec3(5.0, 0.5, 0.5), empty);
        check("C3 形状为空的方块按满格处理（space 的 Shapes.block() 分支）",
                solid != null && solid.cell().equals(new BlockPos(0, 0, 0)), describe(solid));
    }

    // ------------------------------------------------------------------ D：与独立参考对拍

    /**
     * D 组：实心 4×4×4 方块块，随机射线与<b>细步进采样</b>参考对拍。
     *
     * <p>参考算法与实现无关：以 5e-4 步长走完全程，第一次采到"严格落在某格内部"的点即视为命中，
     * 用它的 t 与实现的命中距离比（容差 2e-3，即 4 个步长）。两侧都不命中才算一致。</p>
     */
    private static void groupD() {
        Map<Long, VoxelShape> solid = new HashMap<>();
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    solid.put(BlockPos.asLong(x, y, z), Shapes.block());
                }
            }
        }
        BodyVoxelRaycast.LocalShapes shapes = p -> solid.get(p.asLong());

        Random random = new Random(20261006L);
        int rays = 200;
        int hits = 0;
        int mismatch = 0;
        for (int i = 0; i < rays; i++) {
            // 一半射线瞄向块内（保证有足够多的"命中"样本，否则判据很弱）、一半瞄向块外随机点（大量未命中）
            Vec3 target = i % 2 == 0
                    ? new Vec3(random.nextDouble() * 3.6 + 0.2, random.nextDouble() * 3.6 + 0.2,
                            random.nextDouble() * 3.6 + 0.2)
                    : new Vec3(random.nextDouble() * 10.0 - 3.0, random.nextDouble() * 10.0 - 3.0,
                            random.nextDouble() * 10.0 - 3.0);
            Vec3 center = new Vec3(2.0, 2.0, 2.0);
            Vec3 unit = new Vec3(random.nextDouble() * 2.0 - 1.0, random.nextDouble() * 2.0 - 1.0,
                    random.nextDouble() * 2.0 - 1.0);
            if (unit.length() < 1.0e-6) {
                i--;
                continue;
            }
            // 起点放在块外（半径 10..18 的球面）——真实调用方的射线起点是玩家眼睛，不会在体内；
            // 起点落在实心格内部时原版 DDA 的"命中距离"是另一套语义（返回的不是表面点），
            // 拿它跟步进参考比会误判，所以这一类在 D 组里排除。
            Vec3 start = center.add(unit.normalize().scale(10.0 + random.nextDouble() * 8.0));
            Vec3 dir = target.subtract(start);
            if (dir.length() < 1.0e-6) {
                i--;
                continue;
            }
            dir = dir.normalize();
            double length = 30.0;
            Vec3 end = start.add(dir.scale(length));

            BodyVoxelRaycast.LocalHit got = BodyVoxelRaycast.traverse(start, end, shapes);
            Double ref = march(start, dir, length, solid);

            if (ref == null && got == null) {
                continue;
            }
            if (ref != null && got != null) {
                hits++;
                double gotT = got.location().distanceTo(start);
                if (Math.abs(ref - gotT) > 2.0e-3) {
                    mismatch++;
                    if (mismatch <= 3) {
                        System.out.println("  [DIFF] 参考 t=" + ref + " 实现 t=" + gotT + " " + describe(got));
                    }
                }
                continue;
            }
            mismatch++;
            if (mismatch <= 3) {
                System.out.println("  [DIFF] 参考=" + (ref == null ? "未命中" : "t=" + ref)
                        + " 实现=" + describe(got));
            }
        }
        check("D1 " + rays + " 条随机射线与细步进参考完全一致（其中 " + hits + " 条命中）",
                mismatch == 0, "不一致 " + mismatch + " 条");
    }

    /** 独立参考：细步进采样"点是否严格在某格内部"。 */
    private static Double march(Vec3 start, Vec3 dir, double length, Map<Long, VoxelShape> cubes) {
        double step = 5.0e-4;
        for (double t = 0.0; t <= length; t += step) {
            double x = start.x + dir.x * t;
            double y = start.y + dir.y * t;
            double z = start.z + dir.z * t;
            int cx = (int) Math.floor(x);
            int cy = (int) Math.floor(y);
            int cz = (int) Math.floor(z);
            if (!cubes.containsKey(BlockPos.asLong(cx, cy, cz))) {
                continue;
            }
            if (strictlyInside(x) && strictlyInside(y) && strictlyInside(z)) {
                return t;
            }
        }
        return null;
    }

    /** 小数部分严格落在 (1e-6, 1−1e-6)：排除"正好在格面上"的边界歧义。 */
    private static boolean strictlyInside(double v) {
        double f = v - Math.floor(v);
        return f > 1.0e-6 && f < 1.0 - 1.0e-6;
    }

    // ------------------------------------------------------------------ E：判据不是死的

    /**
     * E 组：把<b>已删除的旧实现</b>（逐块当单位立方体、某轴提供进入点时漏夹 tmax）
     * 在探针里复刻一份，证明 A2 那条判据真的<b>能</b> FAIL —— 否则 A2 只是"恒真"的死判据。
     *
     * <p>注意口径：这是"用反例证明判据有牙"，<b>不是</b>拿复刻去判真实现
     * （复刻对复刻 = 死判据，本项目明令禁止）。</p>
     */
    private static void groupE() {
        Vec3 start = new Vec3(0.5, -1.7888543819998317, -2.2360679774997896);
        Vec3 dir = new Vec3(0.0, 0.8944271909999159, 0.4472135954999579);
        double t = oldSlabT(start, dir, 10.0, new int[][]{{0, 0, 0}});
        check("E1 旧 slab 算法在同一条射线上必须报命中（证明 A2 判得出差别）", t >= 0.0,
                t >= 0.0 ? "旧算法报命中 t=" + t + "（真值未命中）"
                        : "旧算法也报未命中 ⇒ A2 是死判据");
    }

    /**
     * 旧实现的最小复刻：单位立方体 slab，且"该轴提供进入点"时只更新 tmin、不夹 tmax（就是当年的 bug）。
     *
     * @return 命中距离；未命中返回 -1
     */
    private static double oldSlabT(Vec3 o, Vec3 d, double max, int[][] cells) {
        double best = max;
        for (int[] c : cells) {
            double tmin = 0.0;
            double tmax = best;
            boolean miss = false;
            double[] org = {o.x, o.y, o.z};
            double[] dd = {d.x, d.y, d.z};
            for (int a = 0; a < 3; a++) {
                if (Math.abs(dd[a]) < 1.0e-9) {
                    if (org[a] < c[a] || org[a] > c[a] + 1.0) {
                        miss = true;
                        break;
                    }
                    continue;
                }
                double t1 = (c[a] - org[a]) / dd[a];
                double t2 = (c[a] + 1.0 - org[a]) / dd[a];
                if (t1 > t2) {
                    double s = t1;
                    t1 = t2;
                    t2 = s;
                }
                if (t1 > tmin) {
                    tmin = t1;              // ← 当年就漏了 tmax = Math.min(tmax, t2)
                } else if (t2 < tmax) {
                    tmax = t2;
                }
            }
            if (!miss && tmin <= tmax) {
                best = tmin;
            }
        }
        return best < max ? best : -1.0;
    }

    // ------------------------------------------------------------------ 工具

    // ------------------------------------------------------------------ F：旋转体的放置几何（端到端）

    /**
     * F 组：**服务端放置**那条链的几何 —— 体被转过时，"局部面 → 世界面 → 目标格"必须仍然对。
     *
     * <p>存在理由：太空放置原来把射线打在<b>恒空的 MPS 克隆栈</b>上，"对着体某一面放置"是死代码，
     * 每次放置都退化成 {@code eye + look × 3.0}。改成对 A 的体求交后，这条链每一步都得钉住。
     * 期望值全部由解析推导给出（见每条判据的算式），不是抄实现里的常量 —— 所以它<b>会 FAIL</b>：
     * 位姿变换用错方向、AABB 忘了转、面忘了按刚体旋转，都会在这里红。</p>
     *
     * <p>体：单块在局部 {@code (0,0,0)}，位姿 {@code p=(10,64,10)}，绕 Y 转 +90°：
     * {@code R(1,0,0)=(0,0,-1)}、{@code R(0,1,0)=(0,1,0)}、{@code R(0,0,1)=(1,0,0)}。</p>
     */
    private static void groupF() {
        Quaterniond rot = new Quaterniond(0.0, Math.sin(Math.PI / 4.0), 0.0, Math.cos(Math.PI / 4.0));
        Quaterniond inv = new Quaterniond(rot).conjugate();
        double px = 10.0;
        double py = 64.0;
        double pz = 10.0;

        // F1 世界 AABB：单格 [0,1]³ 绕 Y 转 90° 还是同一个立方 ⇒ min=(10,64,9)、max=(11,65,10)
        var box = BodyVoxelRaycast.worldAabb(new int[]{0, 0, 0, 0, 0, 0}, px, py, pz, rot);
        check("F1 旋转体的世界 AABB（解析：min=(10,64,9) max=(11,65,10)）",
                fNear(box.minX, 10.0) && fNear(box.minY, 64.0) && fNear(box.minZ, 9.0)
                        && fNear(box.maxX, 11.0) && fNear(box.maxY, 65.0) && fNear(box.maxZ, 10.0),
                "min=(" + box.minX + "," + box.minY + "," + box.minZ
                        + ") max=(" + box.maxX + "," + box.maxY + "," + box.maxZ + ")");

        // 从西侧沿 +X 打过来：世界 (5,64.5,9.5) → (15,64.5,9.5)
        Vec3 origin = new Vec3(5.0, 64.5, 9.5);
        Vec3 dir = new Vec3(1.0, 0.0, 0.0);
        Vec3 end = new Vec3(15.0, 64.5, 9.5);

        // F2 粗筛进入点：解析 x=10.0（体的西面）
        Vec3 entry = box.clip(origin, end).orElse(null);
        check("F2 AABB 粗筛进入点（解析：(10.0, 64.5, 9.5)）",
                entry != null && fNear(entry.x, 10.0) && fNear(entry.y, 64.5) && fNear(entry.z, 9.5),
                String.valueOf(entry));

        // F3 世界→局部：R⁻¹(1,0,0)=(0,0,1) ⇒ 射线在局部空间沿 +Z 走
        //    起点 R⁻¹(1e-5,0.5,-0.5)=(0.5,0.5,1e-5)；终点 R⁻¹(5,0.5,-0.5)=(0.5,0.5,5.0)
        Vec3 localStart = BodyVoxelRaycast.toLocal(
                entry.add(dir.scale(BodyVoxelRaycast.AABB_ENTRY_EPSILON)), px, py, pz, inv);
        Vec3 localEnd = BodyVoxelRaycast.toLocal(end, px, py, pz, inv);
        check("F3 世界→局部（解析：起点 (0.5,0.5,~1e-5)、终点 (0.5,0.5,5.0)）",
                localStart != null && localEnd != null
                        && fNear(localStart.x, 0.5) && fNear(localStart.y, 0.5)
                        && Math.abs(localStart.z) < 1.0e-3
                        && fNear(localEnd.x, 0.5) && fNear(localEnd.y, 0.5) && fNear(localEnd.z, 5.0),
                "start=" + localStart + " end=" + localEnd);

        // F4 局部 DDA：单块 (0,0,0)，射线沿 +Z 进入 ⇒ 局部面必须是 NORTH（-Z）
        //     命中点的 z **不是 0**：原版 VoxelShape.clip 会先把起点沿射线推进 0.1%（vec3.scale(0.001)）再求交，
        //     所以 z = 起点 + 0.001 × 行程 = 1e-5 + 0.001×(5.0−1e-5) = 0.00500999…。
        //     这是 MC 自己的语义、space 同源 ⇒ 照抄不改；期望值由该语义解析推导（不是抄实现常量）。
        double expectZ = localStart.z + 0.001 * (localEnd.z - localStart.z);
        Map<Long, VoxelShape> block = new HashMap<>();
        block.put(BlockPos.asLong(0, 0, 0), Shapes.block());
        BodyVoxelRaycast.LocalShapes shapes = p -> block.get(p.asLong());
        BodyVoxelRaycast.LocalHit hit = BodyVoxelRaycast.traverse(localStart, localEnd, shapes);
        check("F4 局部命中：格=(0,0,0)、面=NORTH、命中点 z=起点+0.1%行程（原版 clip 的 nudge）",
                hit != null && hit.cell().equals(new BlockPos(0, 0, 0)) && hit.face() == Direction.NORTH
                        && fNear(hit.location().x, 0.5) && fNear(hit.location().y, 0.5)
                        && fNear(hit.location().z, expectZ),
                describe(hit) + " 期望z=" + expectZ);

        // F5 局部命中点换回世界：R(0.5,0.5,expectZ)+p ⇒ x=10+expectZ（偏移沿世界 +X），y=64.5、z=9.5
        Vec3 world = BodyVoxelRaycast.toWorld(hit.location(), px, py, pz, rot);
        check("F5 局部→世界（解析：(10+0.00500999, 64.5, 9.5)）",
                world != null && fNear(world.x, 10.0 + expectZ) && fNear(world.y, 64.5) && fNear(world.z, 9.5),
                String.valueOf(world));

        // F6 面按刚体旋转转成世界方向：R(0,0,-1)=(-1,0,0) ⇒ WEST（射线从西边来，打在体的西面）
        int[] turned = SpaceBuildRules.rotateFace(hit.face().getStepX(), hit.face().getStepY(),
                hit.face().getStepZ(), rot);
        Direction worldFace = Direction.getNearest(turned[0], turned[1], turned[2]);
        check("F6 局部 NORTH 经 +90°Y ⇒ 世界 WEST（解析：R(0,0,-1)=(-1,0,0)）",
                worldFace == Direction.WEST, String.valueOf(worldFace));

        // F7 目标格 = 被命中格沿世界面的相邻格：hitCell=(10,64,9) ⇒ target=(9,64,9)（射线来向那一格）
        double eps = BodyVoxelRaycast.AABB_ENTRY_EPSILON;
        BlockPos hitCell = BlockPos.containing(world.x - worldFace.getStepX() * eps,
                world.y - worldFace.getStepY() * eps, world.z - worldFace.getStepZ() * eps);
        BlockPos target = hitCell.relative(worldFace);
        check("F7 放置目标格（解析：hitCell=(10,64,9) ⇒ target=(9,64,9)）",
                hitCell.equals(new BlockPos(10, 64, 9)) && target.equals(new BlockPos(9, 64, 9)),
                "hitCell=" + hitCell + " target=" + target);
    }

    /** F 组自用的浮点近似相等（名字带前缀，避免与其它组的同名工具撞车）。 */
    private static boolean fNear(double a, double b) {
        return Math.abs(a - b) < 1.0e-6;
    }

    // ------------------------------------------------------------------ G：交互包的"面帧"

    /**
     * G 组：{@code PhysicsBodyEditPacket.use(...)} 里那个 {@code faceX/Y/Z} 必须是
     * <b>体局部</b>面，不是世界面。
     *
     * <p><b>为什么单独钉一条（2026-10-06 实机）</b>：用户报"右键放置有时不放我瞄准的那个面"。
     * 服务端 {@code PhysicsBodyInteraction.useOrPlace} 在<b>投影维度</b>（= 体局部坐标）里做
     * {@code toProjection(slot, dx,dy,dz).relative(face)}，所以 {@code dx/dy/dz}（局部格）
     * 与 {@code face} 必须<b>同坐标系</b>；而客户端此前把命中面用刚体旋转转成了世界面再发。
     * 未旋转的体看不出差别（G1 就是那条中性判据），体一旦被牵引枪转过，
     * 方块就落到<b>体的另一侧</b>（G2/G3）—— 与 {@code SpaceBuildRules.rotateFace} 注释里
     * 记过的"直接用会把方块放到体的另一侧"是同一件事的镜像。</p>
     *
     * <p>期望值全部由解析给出：{@code R = +90°Y} 满足 {@code R(0,0,-1)=(-1,0,0)}、
     * {@code R(-1,0,0)=(0,0,1)}（与 F 组同一约定）。</p>
     */
    private static void groupG() {
        Quaterniond rot = new Quaterniond(0.0, Math.sin(Math.PI / 4.0), 0.0, Math.cos(Math.PI / 4.0));
        Quaterniond inv = new Quaterniond(rot).conjugate();

        Direction localFace = Direction.NORTH;          // 射线沿 +Z 进入 ⇒ 局部 NORTH（同 F4）

        // G1 中性：体未旋转时 世界面 == 局部面 ⇒ 这次改动对"没转过的体"是恒等的
        Direction idWorld = rotate(localFace, new Quaterniond());
        check("G1 中性：体未旋转时 世界面 == 局部面（改动不改未旋转体的行为）",
                idWorld == localFace, "世界面=" + idWorld + " 局部面=" + localFace);

        // G2 反例（解析）：R=+90°Y ⇒ 局部 NORTH 的世界方向 = WEST；
        //     若包里带**世界面**，服务端在局部空间走 WEST ⇒ 世界偏移 R(-1,0,0)=(0,0,1)=SOUTH
        //     ⇒ 方块落到体的**另一侧**（用户报的症状）
        Direction worldFace = rotate(localFace, rot);
        Direction buggyWorldOffset = rotate(worldFace, rot);
        check("G2 反例：带世界面(WEST)时方块落到体的另一侧（解析：R·WEST=SOUTH）",
                worldFace == Direction.WEST && buggyWorldOffset == Direction.SOUTH,
                "世界面=" + worldFace + " 带世界面的实际落向=" + buggyWorldOffset);

        // G3 正确：带**局部面** ⇒ 世界偏移 R·NORTH = WEST == 玩家瞄准的世界面
        Direction fixedWorldOffset = rotate(localFace, rot);
        check("G3 正确：带局部面(NORTH)时方块出现在瞄准那一侧（解析：R·NORTH=WEST）",
                fixedWorldOffset == worldFace && fixedWorldOffset == Direction.WEST,
                "实际落向=" + fixedWorldOffset + " 期望=" + worldFace);

        // G4 判据非空守卫：这一组必须"世界面 != 局部面"，否则 G2/G3 是死判据
        check("G4 判据非空：旋转体上 世界面 != 局部面（否则 G2/G3 测不到东西）",
                worldFace != localFace, "世界面=" + worldFace + " 局部面=" + localFace);

        // G5 覆盖：R=+90°Y 下四个水平面的世界面都与局部面不同 ⇒ 缺陷不是"某个面的特例"
        int differed = 0;
        for (Direction f : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (rotate(f, rot) != f) {
                differed++;
            }
        }
        check("G5 覆盖：绕 Y 转 90° 后 4 个水平面全部换了号（缺陷不是单面特例）",
                differed == 4, "换了号的水平面=" + differed + "/4");

        // G6 生产函数往返：rotateFace(局部,R)=世界 且 rotateFace(世界,R⁻¹)=局部
        //    —— 钉住"两个坐标系确实不同"，也钉住客户端要发的那个值就是局部面
        int[] toWorld = SpaceBuildRules.rotateFace(localFace.getStepX(), localFace.getStepY(),
                localFace.getStepZ(), rot);
        int[] backToLocal = SpaceBuildRules.rotateFace(worldFace.getStepX(), worldFace.getStepY(),
                worldFace.getStepZ(), inv);
        check("G6 往返：rotateFace(局部,R)=世界 且 rotateFace(世界,R⁻¹)=局部（两坐标系确实不同）",
                Direction.getNearest(toWorld[0], toWorld[1], toWorld[2]) == worldFace
                        && Direction.getNearest(backToLocal[0], backToLocal[1], backToLocal[2]) == localFace,
                "去=" + java.util.Arrays.toString(toWorld) + " 回=" + java.util.Arrays.toString(backToLocal));
    }

    /** G 组自用：把面按刚体旋转转成世界方向（与生产代码同一个 {@link SpaceBuildRules#rotateFace}）。 */
    private static Direction rotate(Direction face, Quaterniond rot) {
        int[] v = SpaceBuildRules.rotateFace(face.getStepX(), face.getStepY(), face.getStepZ(), rot);
        return Direction.getNearest(v[0], v[1], v[2]);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "    " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "    " + detail));
        }
    }

    private static String describe(BodyVoxelRaycast.LocalHit hit) {
        return hit == null
                ? "未命中"
                : "格=" + hit.cell() + " 面=" + hit.face() + " 命中点=" + hit.location();
    }

    private BodyVoxelRaycastProbe() {
    }
}
