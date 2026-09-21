package com.mss.polymech.mps.physical.helper;

import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import com.mss.polymech.mps.rapier.helper.RapierWorld;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 物理体射线查询的统一入口 —— <b>与
 * {@code org.polaris2023.mps.physical.helper.PhysicalRaycast} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 对外只暴露两个查询：<b>打物理体</b>（{@link #cast}）与<b>打地形</b>（{@link #castTerrain}），
 * 把两条完全不同的实现藏在后面。
 *
 * <h2>为什么这两条走的路根本不同（照 space 0.1.3）</h2>
 * <ul>
 *   <li><b>打物理体</b>：世界里的方块已经搬走了，Rapier 只有碰撞体、<b>没有方块信息</b>，
 *       所以不能直接用物理射线 —— 必须委托 {@link ShipRaycast}
 *       （先粗筛世界 AABB，再把射线变换到局部逐格走原版方块形状）。
 *       所以本方法只是把 {@code ShipRaycast.Result} <b>改名换面</b>成 {@code Hit}
 *       —— 保留这一层的意义是"让调用方只认一个入口"，而不是重复计算。</li>
 *   <li><b>打地形</b>：地形是真的 Rapier 碰撞体，直接 {@code castRay} 就行；
 *       但必须<b>排除掉物理体的碰撞体</b>（{@code getPhysicalBody(...) == null} 才认），
 *       否则"站在船上挖地"会打到自己脚下的船。</li>
 * </ul>
 *
 * <p>地形分支里的 {@code INSIDE_EPSILON} 是把命中点<b>沿法线往回缩</b>一点点，
 * 这样 {@link BlockPos#containing} 取到的是<b>被击中那一格</b>而不是相邻的空气格
 * —— 少这一步，"对着地面右键"会点到空气。</p>
 */
public final class PhysicalRaycast {

    private static final double INSIDE_EPSILON = 1.0E-5;

    private PhysicalRaycast() {
    }

    /** 打物理体：委托 {@link ShipRaycast}（见类注释为什么不能直接用物理射线）。 */
    public static Hit cast(PhysicalWorld physicalWorld, Vector3dc origin, Vector3dc direction, double maxDistance) {
        ShipRaycast.Result result = ShipRaycast.cast(physicalWorld, origin, direction, maxDistance);
        return result == null
                ? null
                : new Hit(result.physicalBody(), result.localBlockPos(), result.localLocation(),
                result.localFace(), result.worldLocation(), result.distance());
    }

    /** 打地形：物理射线 + 排除物理体碰撞体（见类注释）。 */
    public static BlockHit castTerrain(PhysicalWorld physicalWorld, Vector3dc origin, Vector3dc direction,
                                       double maxDistance) {
        Vector3d normalizedDirection = new Vector3d();
        RapierWorld.RayHit rayHit = castRay(physicalWorld, origin, direction, maxDistance, 1, normalizedDirection);
        if (rayHit == null) {
            return null;
        }
        // 命中的是物理体 → 不算地形
        if (physicalWorld.getPhysicalBody(physicalWorld.getColliderBody(rayHit.colliderHandle())) != null) {
            return null;
        }
        Vector3d normal = new Vector3d(rayHit.normal());
        if (normal.lengthSquared() == 0.0 || !normal.isFinite()) {
            return null;
        }
        normal.normalize();
        Vector3d location = new Vector3d(normalizedDirection).mul(rayHit.toi()).add(origin);
        // 沿法线往回缩 epsilon，保证取到的是被击中那一格（见类注释）
        Vector3d inside = new Vector3d(location).sub(new Vector3d(normal).mul(INSIDE_EPSILON));
        return new BlockHit(BlockPos.containing(inside.x, inside.y, inside.z), location, normal, rayHit.toi());
    }

    /** 参数校验 + 归一化方向，再交给 {@link PhysicalWorld#castRay}。 */
    private static RapierWorld.RayHit castRay(PhysicalWorld physicalWorld, Vector3dc origin, Vector3dc direction,
                                             double maxDistance, int collisionGroup, Vector3d outNormalizedDirection) {
        if (physicalWorld == null || origin == null || direction == null || !isFinite(origin)
                || maxDistance <= 0.0 || !Double.isFinite(maxDistance)) {
            return null;
        }
        Vector3d normalizedDirection = new Vector3d(direction);
        if (normalizedDirection.lengthSquared() == 0.0 || !normalizedDirection.isFinite()) {
            return null;
        }
        normalizedDirection.normalize();
        RapierWorld.RayHit rayHit = physicalWorld.castRay(origin, normalizedDirection, maxDistance, -1, collisionGroup);
        if (rayHit != null) {
            outNormalizedDirection.set(normalizedDirection);
        }
        return rayHit;
    }

    private static boolean isFinite(Vector3dc vector) {
        return Double.isFinite(vector.x()) && Double.isFinite(vector.y()) && Double.isFinite(vector.z());
    }

    /** 地形命中：世界格坐标 + 命中点 + 法线 + 距离。 */
    public static record BlockHit(BlockPos blockPos, Vector3d location, Vector3d normal, double distance) {
    }

    /** 物理体命中：局部坐标 + 世界命中点（见 {@link ShipRaycast.Result}）。 */
    public static record Hit(PhysicalBody physicalBody, BlockPos localBlockPos, Vector3d localLocation,
                             net.minecraft.core.Direction localFace, Vector3d worldLocation, double distance) {
    }
}
