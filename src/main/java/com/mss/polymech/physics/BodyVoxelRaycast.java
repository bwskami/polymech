package com.mss.polymech.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * 物理体方块射线的<b>几何内核</b> —— 逐字照 space 0.3.0 的
 * {@code org.polaris2023.mps.collision.physical.helper.ShipRaycast}。
 *
 * <h2>为什么要有这个类（2026-10-06）</h2>
 * 破坏/放置物理体上的方块，本来一直用我们自己的 {@code PhysicsRaycast}：
 * 它把体的方块表拍成一个 {@code int[]}，<b>逐块当成单位立方体</b>做 slab 求交。两个问题：
 *
 * <ol>
 *   <li><b>方块形状被当成满格</b>：台阶、半砖、栅栏、玻璃板、草、火把……
 *       实际形状都不是 1×1×1，而 slab 一律按整格算 ⇒ 对着"看得见的空处"也会报命中。
 *       space 不是这么做的：它取 {@code state.getShape(...)} 的<b>真实形状</b>
 *       （空形状才退回满格 {@link Shapes#block()}）。</li>
 *   <li><b>slab 少夹了一次 far</b>：原实现在"某一轴提供进入点"的那个分支里
 *       只更新 {@code tmin} 而<b>没有</b> {@code tmax = min(tmax, tFar)}，
 *       于是三轴区间明明不相交（射线从方块旁边擦过去）也会报命中。
 *       反例（解析可验证）：方块 (0,0,0)，起点 (0.5, −1.788854, −2.236068)，
 *       方向 (0, 0.894427, 0.447214)，reach=10 ——
 *       X 平行 ⇒ 区间无限宽，Y 区间 ≈ [2, 3.118]，Z 区间 ≈ [5, 7.236]，
 *       三轴交集为空（真值：未命中），旧实现却报 "命中于 t=5"。
 *       这类"擦着方块边缘看"的姿态在游戏里到处都是，表现就是
 *       <b>准星对着这一格、服务端却收到另一格（或空气）</b> —— 挖不动 / 互动错格，且随角度时好时坏。</li>
 * </ol>
 *
 * <h2>space 的三步（本类只做第 2 步的几何部分）</h2>
 * <ol>
 *   <li>粗筛：与体的世界 AABB 求交（调用方做，见 {@code PhysicsBodyInteractionClient}）；</li>
 *   <li><b>精求交：把射线两端变换到体局部空间，用原版 {@link BlockGetter#traverseBlocks}
 *       逐格 DDA 走</b>，每格取真实形状求交 —— 就是本类的 {@link #traverse}；</li>
 *   <li>取最近（调用方做）。</li>
 * </ol>
 *
 * <p>用 {@code traverseBlocks} 而不是自己写 DDA，是"照抄"里最要紧的一条：
 * 它保证<b>与方块形状求交的顺序、边界归属、命中面法线</b>全部沿用原版语义 ——
 * 这些正是"指哪打哪"和"准星那一格 == 服务端那一格"所依赖的东西。</p>
 *
 * <p>本类<b>不依赖任何 Minecraft 运行时状态</b>（只用 {@link VoxelShape}/{@link Vec3} 这类纯几何类），
 * 所以能被离线探针直接调用（见 {@code native/jni-smoketest/BodyVoxelRaycastProbe.java}）。</p>
 */
public final class BodyVoxelRaycast {

    /**
     * 局部空间里"某一格的碰撞形状"。
     *
     * <p>返回 {@code null} 表示空气（space 的 {@code state.isAir()} 分支）；
     * 返回 {@link Shapes#block()} 表示"无碰撞箱的方块按满格处理"。</p>
     */
    @FunctionalInterface
    public interface LocalShapes {
        VoxelShape shapeAt(BlockPos local);
    }

    /** 局部空间命中：格、命中面（局部）、命中点（局部）。 */
    public record LocalHit(BlockPos cell, Direction face, Vec3 location) {
    }

    /** space 的 {@code AABB_ENTRY_EPSILON}：沿射线推进一点点，避免起点贴在盒面上时反复命中同一个面。 */
    public static final double AABB_ENTRY_EPSILON = 1.0E-5;

    /**
     * 128³ 投影盒的<b>外接球半径</b>（{@code 128·√3/2}）—— space 的 {@code MAX_SHIP_RADIUS}。
     * 体的世界 AABB 还没算出来/退化时用它兜底，保证粗筛不漏（只是慢一点，不会错）。
     */
    public static final double MAX_BODY_RADIUS = 128.0 * Math.sqrt(3.0) * 0.5;

    private BodyVoxelRaycast() {
    }

    /**
     * 在物理体<b>局部空间</b>里逐格走，取最近命中。
     *
     * <p>逐字照 space 的 {@code castFakeRay} → {@code BlockGetter.traverseBlocks(...)} →
     * {@code clipBlock(...)}：空气返回 null（继续走），有形状就 {@code shape.clip(...)}，
     * 形状为空则按满格 {@link Shapes#block()} 处理。</p>
     *
     * <p><b>两个端点都要给</b>（不是"起点+方向+距离"）：{@code traverseBlocks} 的参数就是
     * 一条线段的两个端点，方向由它自己推。所以调用方必须先把<b>世界空间的终点</b>
     * 也变换进局部空间 —— 只变起点会让方向在旋转体上整体偏掉。</p>
     *
     * @param localStart 局部空间起点（一般是"与体 AABB 的进入点"沿射线推进 epsilon 之后）
     * @param localEnd   局部空间终点（世界空间的射线终点变换过来）
     * @return 最近命中；没打到返回 {@code null}
     */
    public static LocalHit traverse(Vec3 localStart, Vec3 localEnd, LocalShapes shapes) {
        // 泛型 T = LocalShapes：traverseBlocks 只要求"把 context 原样喂回回调"，
        // 并不要求 context 是 BlockGetter（space 传的是 PhysicalBody，我们传形状表）。
        BlockHitResult hit = BlockGetter.traverseBlocks(localStart, localEnd, shapes,
                (s, pos) -> clip(s, pos, localStart, localEnd), s -> null);
        return hit == null
                ? null
                : new LocalHit(hit.getBlockPos().immutable(), hit.getDirection(), hit.getLocation());
    }

    /** 单格求交 —— space 的 {@code clipBlock}。 */
    private static BlockHitResult clip(LocalShapes shapes, BlockPos pos, Vec3 start, Vec3 end) {
        VoxelShape shape = shapes.shapeAt(pos);
        if (shape == null) {
            return null;                     // 空气：这一格不挡
        }
        if (shape.isEmpty()) {
            shape = Shapes.block();          // 没有碰撞箱的方块按满格（草/火把这类不该被射线穿过去）
        }
        return shape.clip(start, end, pos);
    }

    /**
     * 体的世界 AABB —— 对应 space 里体自己维护的 {@code worldAABB}。
     *
     * <p>由<b>局部格范围</b> + 位姿算：局部格占 {@code [x, x+1]}，所以最大角是 {@code max+1}。
     * 范围非法（null / 空体）或 8 个角变换后退化成零尺寸时，退回外接球立方
     * （{@link #cubeAround}）—— 粗筛只求"不漏"，慢一点不会错。</p>
     *
     * @param cellBounds {@code {minX, minY, minZ, maxX, maxY, maxZ}}；没有方块传 {@code null}
     */
    public static AABB worldAabb(int[] cellBounds, double px, double py, double pz, Quaterniondc rot) {
        if (cellBounds == null || cellBounds.length != 6) {
            return cubeAround(px, py, pz);
        }
        double wMinX = Double.MAX_VALUE;
        double wMinY = Double.MAX_VALUE;
        double wMinZ = Double.MAX_VALUE;
        double wMaxX = -Double.MAX_VALUE;
        double wMaxY = -Double.MAX_VALUE;
        double wMaxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            Vector3d v = new Vector3d((corner & 1) == 0 ? cellBounds[0] : cellBounds[3] + 1.0,
                    (corner & 2) == 0 ? cellBounds[1] : cellBounds[4] + 1.0,
                    (corner & 4) == 0 ? cellBounds[2] : cellBounds[5] + 1.0);
            rot.transform(v);
            double wx = px + v.x;
            double wy = py + v.y;
            double wz = pz + v.z;
            wMinX = Math.min(wMinX, wx);
            wMinY = Math.min(wMinY, wy);
            wMinZ = Math.min(wMinZ, wz);
            wMaxX = Math.max(wMaxX, wx);
            wMaxY = Math.max(wMaxY, wy);
            wMaxZ = Math.max(wMaxZ, wz);
        }
        if (!(wMaxX > wMinX) || !(wMaxY > wMinY) || !(wMaxZ > wMinZ)) {
            return cubeAround(px, py, pz);
        }
        return new AABB(wMinX, wMinY, wMinZ, wMaxX, wMaxY, wMaxZ);
    }

    /** 世界点 → 体局部点（先平移、再乘逆旋转）；非有限返回 {@code null}。 */
    public static Vec3 toLocal(Vec3 world, double px, double py, double pz, Quaterniondc inverseRot) {
        Vector3d v = inverseRot.transform(new Vector3d(world.x - px, world.y - py, world.z - pz));
        return v.isFinite() ? new Vec3(v.x, v.y, v.z) : null;
    }

    /** 体局部点 → 世界点（先乘旋转、再加体原点）；非有限返回 {@code null}。 */
    public static Vec3 toWorld(Vec3 local, double px, double py, double pz, Quaterniondc rot) {
        Vector3d v = rot.transform(new Vector3d(local.x, local.y, local.z)).add(px, py, pz);
        return v.isFinite() ? new Vec3(v.x, v.y, v.z) : null;
    }

    /** 以某点为中心、半径 {@link #MAX_BODY_RADIUS} 的立方 —— space 里 AABB 无效时的退化粗筛盒。 */
    public static AABB cubeAround(double x, double y, double z) {
        return new AABB(x - MAX_BODY_RADIUS, y - MAX_BODY_RADIUS, z - MAX_BODY_RADIUS,
                x + MAX_BODY_RADIUS, y + MAX_BODY_RADIUS, z + MAX_BODY_RADIUS);
    }
}
