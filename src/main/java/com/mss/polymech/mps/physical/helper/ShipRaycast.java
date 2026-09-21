package com.mss.polymech.mps.physical.helper;

import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 船舱内部的方块射线 —— <b>与
 * {@code org.polaris2023.mps.physical.helper.ShipRaycast} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 在<b>物理体自己的方块网格</b>里做射线求交（不是世界方块）。给定世界空间的射线，
 * 返回"打中了哪个体的哪一格、局部坐标是什么、局部法线朝哪、世界命中点在哪"。
 *
 * <h2>为什么不能直接用世界射线（照 space 0.1.3）</h2>
 * 物理体上的方块<b>已经不在世界里了</b>——它们被搬进了投影维度
 * （见 {@code ProjectionManager} 的类注释）。世界里那一片是空气，射线打过去什么也碰不到。
 * 所以必须：<b>先把射线变换到物理体的局部坐标系</b>，在局部用原版的
 * {@link BlockGetter#traverseBlocks} 逐格走（于是"形状/碰撞箱/台阶"全部沿用原版语义），
 * 命中后再变换回世界坐标。
 *
 * <h2>三步走（本类的方法顺序就是它的实现顺序）</h2>
 * <ol>
 *   <li><b>粗筛</b>：先和每个体的世界 AABB 求交（{@link AABB#clip}）。
 *       AABB 无效时退化成一个半径 {@code 128·√3/2} 的立方 —— 那正是 128³ 投影盒的
 *       <b>外接球半径</b>，所以"包围盒还没算出来"的体也不会被漏掉。</li>
 *   <li><b>精求交</b>：把射线两端变换到局部（{@link #toLocal}），
 *       用 {@link #clipBlock} 逐格取方块形状求交。空气跳过；<b>无碰撞箱的方块
 *       按满格 {@link Shapes#block()} 处理</b>（草、火把这类不该"射线穿过去"）。</li>
 *   <li><b>取最近</b>：多个体都可能挡住，取距离最小的那个。</li>
 * </ol>
 *
 * <p>两个 epsilon 的用途不同，别合并：{@code AABB_ENTRY_EPSILON} 是把起点沿射线方向
 * 推进一点点，<b>避免"起点正好落在包围盒面上"时反复命中同一个面</b>；
 * {@code 1.0E-5} 在 {@link #toLocal} 里没有出现 —— 它只服务于推进。</p>
 */
public final class ShipRaycast {

    private static final double AABB_ENTRY_EPSILON = 1.0E-5;
    /** 128³ 投影盒的外接球半径（盒内任意点到中心的距离上限）。 */
    private static final double MAX_SHIP_RADIUS = 128.0 * Math.sqrt(3.0) * 0.5;

    private ShipRaycast() {
    }

    /** 求最近命中；无命中、参数非法或物理世界为空时返回 {@code null}。 */
    public static Result cast(PhysicalWorld physicalWorld, Vector3dc origin, Vector3dc direction, double maxDistance) {
        if (physicalWorld != null && origin != null && direction != null && isFinite(origin)
                && !(maxDistance <= 0.0) && Double.isFinite(maxDistance)) {
            Vector3d normalizedDirection = new Vector3d(direction);
            if (normalizedDirection.isFinite() && normalizedDirection.lengthSquared() != 0.0) {
                normalizedDirection.normalize();
                Vec3 worldOrigin = toVec3(origin);
                Vec3 worldEnd = toVec3(new Vector3d(normalizedDirection).mul(maxDistance).add(origin));
                Result nearest = null;

                for (PhysicalBody physicalBody : physicalWorld.getAllPhysicalBody()) {
                    Pose pose = pose(physicalBody);
                    if (pose == null) {
                        continue;
                    }
                    AABB worldAabb = shipAabb(physicalBody, pose.position());
                    if (worldAabb == null) {
                        continue;
                    }
                    // 起点已在盒内时直接用起点，否则与盒求交
                    Vec3 aabbHit = worldAabb.contains(worldOrigin)
                            ? worldOrigin
                            : worldAabb.clip(worldOrigin, worldEnd).orElse(null);
                    if (aabbHit == null) {
                        continue;
                    }
                    // 沿射线推进 epsilon：避免起点贴在盒面上时反复命中同一个面
                    Vec3 fakeRayStart = aabbHit.add(normalizedDirection.x * AABB_ENTRY_EPSILON,
                            normalizedDirection.y * AABB_ENTRY_EPSILON,
                            normalizedDirection.z * AABB_ENTRY_EPSILON);
                    Result hit = castFakeRay(physicalBody, pose, worldOrigin, fakeRayStart, worldEnd, maxDistance);
                    if (hit != null && (nearest == null || hit.distance() < nearest.distance())) {
                        nearest = hit;
                    }
                }

                return nearest;
            }
            return null;
        }
        return null;
    }

    /** 在局部坐标系里逐格走；命中后换回世界坐标并算距离。 */
    private static Result castFakeRay(PhysicalBody physicalBody, Pose pose, Vec3 worldOrigin,
                                      Vec3 aabbHit, Vec3 worldEnd, double maxDistance) {
        Vec3 localStart = toLocal(aabbHit, pose);
        Vec3 localEnd = toLocal(worldEnd, pose);
        if (localStart == null || localEnd == null) {
            return null;
        }
        BlockHitResult localHit = BlockGetter.traverseBlocks(localStart, localEnd, physicalBody,
                (body, blockPos) -> clipBlock(body, blockPos, localStart, localEnd), body -> null);
        if (localHit == null) {
            return null;
        }
        Vector3d localLocation = new Vector3d(localHit.getLocation().x, localHit.getLocation().y, localHit.getLocation().z);
        Vector3d worldLocation = pose.rotation().transform(new Vector3d(localLocation)).add(pose.position());
        double distance = worldOrigin.distanceTo(toVec3(worldLocation));
        return worldLocation.isFinite() && Double.isFinite(distance) && !(distance > maxDistance)
                ? new Result(physicalBody, localHit.getBlockPos().immutable(), localLocation,
                localHit.getDirection(), worldLocation, distance)
                : null;
    }

    /** 与单格方块的形状求交；空气返回 null，无形状的方块按满格处理。 */
    private static BlockHitResult clipBlock(PhysicalBody physicalBody, BlockPos blockPos, Vec3 start, Vec3 end) {
        BlockState state = physicalBody.getBlockState(blockPos.getX(), blockPos.getY(), blockPos.getZ());
        if (state.isAir()) {
            return null;
        }
        VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        return shape.clip(start, end, blockPos);
    }

    /** 取体的位姿与逆旋转；位姿非法（非有限 / 零四元数）时返回 null。 */
    private static Pose pose(PhysicalBody physicalBody) {
        if (physicalBody == null) {
            return null;
        }
        Vector3d position = physicalBody.getPos();
        Quaterniond rotation = physicalBody.getRotation();
        if (position.isFinite() && rotation.isFinite() && rotation.lengthSquared() != 0.0) {
            rotation.normalize();
            return new Pose(position, rotation, new Quaterniond(rotation).conjugate());
        }
        return null;
    }

    /**
     * 体的世界包围盒；无效或退化时退化成半径 {@link #MAX_SHIP_RADIUS} 的立方
     * （外接球半径，保证粗筛不漏）。
     */
    private static AABB shipAabb(PhysicalBody physicalBody, Vector3dc position) {
        double minX = physicalBody.worldAABB.minX;
        double minY = physicalBody.worldAABB.minY;
        double minZ = physicalBody.worldAABB.minZ;
        double maxX = physicalBody.worldAABB.maxX;
        double maxY = physicalBody.worldAABB.maxY;
        double maxZ = physicalBody.worldAABB.maxZ;
        if (!PhysicalBody.isValidAABB(physicalBody.worldAABB) || maxX <= minX || maxY <= minY || maxZ <= minZ) {
            minX = position.x() - MAX_SHIP_RADIUS;
            minY = position.y() - MAX_SHIP_RADIUS;
            minZ = position.z() - MAX_SHIP_RADIUS;
            maxX = position.x() + MAX_SHIP_RADIUS;
            maxY = position.y() + MAX_SHIP_RADIUS;
            maxZ = position.z() + MAX_SHIP_RADIUS;
        }
        return Double.isFinite(minX) && Double.isFinite(minY) && Double.isFinite(minZ)
                && Double.isFinite(maxX) && Double.isFinite(maxY) && Double.isFinite(maxZ)
                ? new AABB(minX, minY, minZ, maxX, maxY, maxZ)
                : null;
    }

    /** 世界点 → 局部点（先平移再乘逆旋转）。 */
    private static Vec3 toLocal(Vec3 worldPoint, Pose pose) {
        Vector3d local = pose.inverseRotation()
                .transform(new Vector3d(worldPoint.x, worldPoint.y, worldPoint.z).sub(pose.position()));
        return local.isFinite() ? toVec3(local) : null;
    }

    private static Vec3 toVec3(Vector3dc vector) {
        return new Vec3(vector.x(), vector.y(), vector.z());
    }

    private static boolean isFinite(Vector3dc vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    /** 体的位姿 + 预先算好的逆旋转（逆旋转每格都要用，不在循环里重复求逆）。 */
    private static record Pose(Vector3d position, Quaterniond rotation, Quaterniond inverseRotation) {
    }

    /** 命中结果：体 + <b>局部</b>格坐标/命中点/法线 + 世界命中点 + 距离。 */
    public static record Result(PhysicalBody physicalBody, BlockPos localBlockPos, Vector3d localLocation,
                                Direction localFace, Vector3d worldLocation, double distance) {
    }
}
