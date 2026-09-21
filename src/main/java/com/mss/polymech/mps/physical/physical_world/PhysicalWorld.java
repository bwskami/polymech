package com.mss.polymech.mps.physical.physical_world;

import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.rapier.helper.ColliderBody;
import com.mss.polymech.mps.rapier.helper.RapierWorld;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import com.mss.polymech.physics.NativePhysics;
import com.mss.polymech.physics.PhysicsNatives;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 物理世界（每个维度一个）—— <b>与 {@code org.polaris2023.mps.physical.physical_world.PhysicalWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 一个维度里的"物理宇宙"：持有 {@link RapierWorld}（求解器世界）、所有 {@link PhysicalBody}，
 * 以及这三类碰撞组的分配 —— 地形 {@code 1}、玩家 {@code 2}、物理体 {@code 4}。
 *
 * <h2>为什么是这个形态（照 space 0.1.3，别改）</h2>
 * <ul>
 *   <li><b>碰撞组在"进入世界"的入口统一分配</b>（{@link #addColliderBody}、
 *       {@link #addPhysicalBodyCollider}、{@link #addPlayerCollider}）。
 *       组是<b>双向</b>判定的，谁都不许在别处偷偷设 —— 否则"玩家撞不撞物理体"这种事会随
 *       调用顺序变化。地形的 {@code (1,-1)}、玩家 {@code (2,5)}、物理体 {@code (4,-1)}
 *       是 space 定死的三档。</li>
 *   <li><b>物理体 ↔ 碰撞体要有反查表</b>（{@link #physicalBodyByCollider}，IdentityHashMap + 同步包装）：
 *       Rapier 的回调只会给碰撞体，得能立刻反查到它属于哪个体（撞击事件、破坏方块要用）。
 *       用 <b>IdentityHashMap</b> 是因为 ColliderBody 没实现值相等，语义上就是要"同一个对象"。</li>
 *   <li><b>维度地面用原生半空间</b>：{@link #setMinY} 对应 space 的 {@code PhysicalWorld.setMinY}
 *       （非太空维度在 minBuildHeight 挂一个朝上的半空间，船掉出世界有东西接住）。
 *       我们这边由原生 {@link NativePhysics#worldSetFloor} 提供，一个世界只有一份，
 *       与 space"每个世界一个 floor 字段"的结构一致。</li>
 *   <li><b>每个 tick / 每个子步后都要刷体素的世界包围盒</b>（{@link #tick} / {@link #step}）：
 *       投影、剔除、同步范围都读它，而刚体位姿是物理线程在改的。</li>
 * </ul>
 */
public class PhysicalWorld extends RapierWorld {

    /** 地形 / 一类静态体。 */
    public static final int TERRAIN_COLLISION_GROUP = 1;
    /** 玩家。 */
    public static final int PLAYER_COLLISION_GROUP = 2;
    /** 物理体（船/建筑）。 */
    public static final int PHYSICAL_BODY_COLLISION_GROUP = 4;

    protected final ResourceLocation level;
    protected final Set<PhysicalBody> physicalBodies = ConcurrentHashMap.newKeySet();
    protected final Map<ColliderBody, PhysicalBody> physicalBodyByCollider =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** 维度地面当前的 y；{@code null} = 未挂。 */
    protected Double floorY = null;

    protected PhysicalWorld(ResourceLocation level, Vector3d g) {
        super(g);
        this.level = level;
    }

    // ==================== 步进 ====================

    /** 每 tick（20Hz）刷新所有物理体的世界包围盒。 */
    public void tick() {
        for (PhysicalBody physicalBody : this.physicalBodies) {
            physicalBody.updateWorldAABB();
        }
    }

    /** 每个物理子步之后刷新（刚体位姿刚被求解器改过）。 */
    @Override
    public void step() {
        super.step();
        for (PhysicalBody physicalBody : this.physicalBodies) {
            physicalBody.updateWorldAABB();
        }
    }

    // ==================== 维度地面 ====================

    /**
     * 挂/移/移动维度无限地面（对应 space 的 {@code setMinY}）。
     *
     * @param minY 地面高度；{@code null} = 移除
     */
    public void setMinY(Double minY) {
        if (this.floorY == null) {
            if (minY == null) {
                return;
            }
            if (PhysicsNatives.hasTier1()) {
                NativePhysics.worldSetFloor(rapierWorldHandle(), minY, true);
            }
            this.floorY = minY;
        } else if (minY == null) {
            if (PhysicsNatives.hasTier1()) {
                NativePhysics.worldSetFloor(rapierWorldHandle(), this.floorY, false);
            }
            this.floorY = null;
        } else {
            if (PhysicsNatives.hasTier1()) {
                NativePhysics.worldSetFloor(rapierWorldHandle(), minY, true);
            }
            this.floorY = minY;
        }
    }

    // ==================== 碰撞组的统一入口 ====================

    /** 无父碰撞体（地形一类）：挂到隐式固定体，组 {@code (1,-1)}。 */
    @Override
    public void addColliderBody(ColliderBody colliderBody) {
        super.addColliderBody(colliderBody);
        colliderBody.setCollisionGroups(TERRAIN_COLLISION_GROUP, -1);
    }

    /** 玩家碰撞体：组 {@code (2,4)}（只与物理体作用，不撞地形/自己）。 */
    public void addPlayerCollider(ColliderBody colliderBody, RigidBody rigidBody) {
        this.addColliderBody(colliderBody, rigidBody);
        colliderBody.setCollisionGroups(PLAYER_COLLISION_GROUP, PHYSICAL_BODY_COLLISION_GROUP);
    }

    // ==================== 物理体 ====================

    /**
     * 把一个物理体加进世界：挂载 → 加刚体 → 逐个加子块碰撞体 → 登记。
     *
     * @return 是否成功（重复加入、缺刚体或没有碰撞体数组都返回 false）
     */
    public boolean addPhysicalBody(PhysicalBody physicalBody) {
        if (this.physicalBodies.contains(physicalBody)) {
            return false;
        }
        physicalBody.attachToWorld(this);
        RigidBody rigidBody = physicalBody.getRigidBody();
        ColliderBody[][][] colliderBodies = physicalBody.getColliderBodies();
        if (rigidBody == null || colliderBodies == null) {
            return false;
        }
        this.addRigidBody(rigidBody);
        for (ColliderBody[][] layer : colliderBodies) {
            for (ColliderBody[] row : layer) {
                for (ColliderBody collider : row) {
                    if (collider != null) {
                        this.addPhysicalBodyCollider(physicalBody, collider);
                    }
                }
            }
        }
        this.physicalBodies.add(physicalBody);
        return true;
    }

    /** 物理体的碰撞体：组 {@code (4,-1)}，并登记反查。 */
    public void addPhysicalBodyCollider(PhysicalBody physicalBody, ColliderBody colliderBody) {
        this.physicalBodyByCollider.put(colliderBody, physicalBody);
        super.addColliderBody(colliderBody, physicalBody.getRigidBody());
        colliderBody.setCollisionGroups(PHYSICAL_BODY_COLLISION_GROUP, -1);
    }

    public void removePhysicalBodyCollider(ColliderBody colliderBody) {
        this.physicalBodyByCollider.remove(colliderBody);
        this.removeColliderBody(colliderBody);
    }

    public boolean removePhysicalBody(PhysicalBody physicalBody) {
        if (!this.physicalBodies.remove(physicalBody)) {
            return false;
        }
        physicalBody.onRemovedFromWorld();
        ColliderBody[][][] colliderBodies = physicalBody.getColliderBodies();
        if (colliderBodies != null) {
            for (ColliderBody[][] layer : colliderBodies) {
                for (ColliderBody[] row : layer) {
                    for (ColliderBody collider : row) {
                        if (collider != null) {
                            this.physicalBodyByCollider.remove(collider);
                            this.removeColliderBody(collider);
                        }
                    }
                }
            }
        }
        RigidBody rigidBody = physicalBody.getRigidBody();
        if (rigidBody != null) {
            this.removeRigidBody(rigidBody);
        }
        return true;
    }

    /** 锁定 = 切成固定体（建筑"落地生根"），解锁切回动态。 */
    public void setPhysicalBodyLocked(PhysicalBody physicalBody, boolean locked) {
        physicalBody.setLocked(locked);
        RigidBody rigidBody = physicalBody.getRigidBody();
        if (rigidBody != null) {
            rigidBody.setStatus(locked ? RigidBody.Type.FIXED : RigidBody.Type.DYNAMIC, !locked);
        }
    }

    public PhysicalBody getPhysicalBody(UUID uuid) {
        for (PhysicalBody physicalBody : this.physicalBodies) {
            if (physicalBody.getUuid().equals(uuid)) {
                return physicalBody;
            }
        }
        return null;
    }

    public PhysicalBody getPhysicalBody(RigidBody rigidBody) {
        for (PhysicalBody physicalBody : this.physicalBodies) {
            if (physicalBody.getRigidBody().equals(rigidBody)) {
                return physicalBody;
            }
        }
        return null;
    }

    public PhysicalBody getPhysicalBody(ColliderBody colliderBody) {
        return this.physicalBodyByCollider.get(colliderBody);
    }

    public List<PhysicalBody> getAllPhysicalBody() {
        return new ArrayList<>(this.physicalBodies);
    }

    public PhysicalBody getNearPhysicalBody(Vector3d pos) {
        double near = Double.MAX_VALUE;
        PhysicalBody nearest = null;
        for (PhysicalBody physicalBody : this.physicalBodies) {
            Vector3d bodyPos = physicalBody.getPos();
            if (bodyPos == null) {
                continue;
            }
            double distance = bodyPos.distance(pos);
            if (distance < near) {
                nearest = physicalBody;
                near = distance;
            }
        }
        return nearest;
    }

    public ResourceLocation getLevel() {
        return this.level;
    }
}
