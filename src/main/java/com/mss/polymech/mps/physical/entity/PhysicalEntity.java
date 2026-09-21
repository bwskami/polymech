package com.mss.polymech.mps.physical.entity;

import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import com.mss.polymech.mps.rapier.helper.ColliderBody;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 实体（玩家）的双刚体 —— <b>与
 * {@code org.polaris2023.mps.physical.entity.PhysicalEntity} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 让一个实体（玩家）由物理驱动，同时保留"被船/平台带着走"的能力。
 *
 * <h2>为什么必须是这个形态（照 space 0.1.3，逐条都有理由）</h2>
 * <ol>
 *   <li><b>两个刚体</b>：主刚体（真正决定位置）+ 兄弟刚体（同位置同尺寸的第二只盒子）。
 *       兄弟刚体的作用只有一个：<b>用它的接触结果量出"这一步环境给了我多少速度"</b>。
 *       站在移动的船上时，船面把它推着走，于是下一步
 *       {@code main = sibling + own} 就把玩家带上了船 —— 不需要任何"猜玩家在推什么"的启发式。</li>
 *   <li><b>{@code gravity_scale = 0}</b>：Rapier 不对玩家施加重力，重力只走原版那条路
 *       （经 {@code own = 位移 × 20} 进来）。两个来源会叠成双份（下沉 / 贴地抖动）。</li>
 *   <li><b>碰撞体永不旋转 + {@code lockRotations}</b>：旋转盒子的"角"扎进地形/船体后，
 *       解算器把角推出来的冲量方向很诡异 —— 那一整类"贴着墙被弹开"由此消失。</li>
 *   <li><b>碰撞组 {@code (2,5)} / {@code (5,5)}</b>：两个盒子同位置，靠分组<b>互不作用</b>
 *       （{@code 2 & 5 == 0}），否则求解器会把它们互相弹开。玩家主碰撞体只与地形 {@code (1,-1)}、
 *       物理体 {@code (4,-1)} 作用。</li>
 *   <li><b>摩擦 20 / 弹性 0 / 组合规则 0 与 1（Average 与 Min）</b>：配合"速度每子步重写"，
 *       高摩擦让玩家能立刻被地面/甲板抓住。</li>
 *   <li><b>{@link #afterStep()} 必须在 100Hz 子步边界跑</b>（由
 *       {@link ClientPhysicalWorld#step()} 驱动）：它每子步把主刚体速度<b>硬写</b>成
 *       {@code sibling + own}。这个"硬写"正是受控的来源 —— 解算器给的反弹（与
 *       {@code own} 同轴反号）会在下一步被 {@code own} 抵消掉，玩家永远不会被弹飞；
 *       一旦改成"按兑现比例缩放 {@code own}"或加"速度上限夹持"，抵消项就没了，
 *       反作用力原封不动落到玩家身上（这正是我们踩过的坑）。</li>
 *   <li><b>写回前判 {@code delta² > 1e-8}</b>：数值没变就不写、不唤醒，静置刚体才能真正休眠。</li>
 * </ol>
 *
 * <h2>尚未移植（不猜）</h2>
 * {@code getStatus()} 等需要先核准 {@code RigidBody} 侧语义的访问器。
 */
public final class PhysicalEntity {

    private static final double DIMENSION_EPSILON = 1.0E-6;
    /** 每个刚体的质量：25 + 25（合计 50 kg）。 */
    public static final double PLAYER_MASS = 50.0;

    private volatile RigidBody rigidBody;
    private ColliderBody colliderBody;
    private volatile RigidBody siblingBody;
    private ColliderBody siblingColliderBody;
    private volatile PhysicalWorld physicalWorld;
    /** 玩家自己的输入速度（{@code 位移 × 20}），整体替换以保证跨线程读安全。 */
    private volatile Vector3d ownVelocity = new Vector3d();
    private final Vector3d rapierVelocity = new Vector3d();
    private volatile double halfWidth;
    private double halfHeight;
    private double friction = 2.0;

    /**
     * 建（或重建）该实体的双刚体。
     *
     * @param entity        被驱动的实体（位置上取<b>脚底</b>，碰撞体在局部抬 {@code halfHeight}）
     * @param physicalWorld 目标世界；为 null 时什么都不做（space 亦然）
     */
    public PhysicalEntity(Entity entity, PhysicalWorld physicalWorld) {
        if (physicalWorld == null) {
            return;
        }
        Vector3d entityPosition = new Vector3d(entity.getX(), entity.getY(), entity.getZ());
        if (!entityPosition.isFinite()) {
            return;
        }
        // 换世界：先拆旧的，避免两个世界各留一半
        if (this.physicalWorld != null && this.physicalWorld != physicalWorld) {
            this.destroy();
        }
        this.physicalWorld = physicalWorld;
        if (this.rigidBody == null) {
            this.rigidBody = new RigidBody(RigidBody.Type.DYNAMIC, entityPosition, new Quaterniond(),
                    new Vector3d(), 25.0, new Vector3d(), new Vector3d(), new Vector3d(),
                    0.0, 0.0, 0.0, true);
            physicalWorld.addRigidBody(this.rigidBody);
            this.rigidBody.setCCD(false);
            this.siblingBody = new RigidBody(RigidBody.Type.DYNAMIC, entityPosition, new Quaterniond(),
                    new Vector3d(), 25.0, new Vector3d(), new Vector3d(), new Vector3d(),
                    0.0, 0.0, 0.0, true);
            physicalWorld.addRigidBody(this.siblingBody);
            this.siblingBody.setCCD(false);
        }

        double targetHalfWidth = Math.max(1.0E-4, entity.getBbWidth() * 0.5);
        double targetHalfHeight = Math.max(1.0E-4, entity.getBbHeight() * 0.5);
        if (Math.abs(this.halfWidth - targetHalfWidth) > DIMENSION_EPSILON
                || Math.abs(this.halfHeight - targetHalfHeight) > DIMENSION_EPSILON) {
            this.halfWidth = targetHalfWidth;
            this.halfHeight = targetHalfHeight;
            if (this.colliderBody != null) {
                physicalWorld.removeColliderBody(this.colliderBody);
            }
            if (this.siblingColliderBody != null) {
                physicalWorld.removeColliderBody(this.siblingColliderBody);
            }
            this.colliderBody = new ColliderBody(ColliderBody.Type.CUBOID,
                    new Vector3d(0.0, this.halfHeight, 0.0), new Quaterniond(), 0.02,
                    this.halfWidth, this.halfHeight, this.halfWidth);
            physicalWorld.addColliderBody(this.colliderBody, this.rigidBody);
            this.colliderBody.setCollisionGroups(2, 5);
            this.colliderBody.setFriction(20.0);
            this.colliderBody.setFrictionCombineRule(0);
            this.colliderBody.setRestitution(0.0);
            this.colliderBody.setRestitutionCombineRule(1);

            this.siblingColliderBody = new ColliderBody(ColliderBody.Type.CUBOID,
                    new Vector3d(0.0, this.halfHeight, 0.0), new Quaterniond(), 0.02,
                    this.halfWidth, this.halfHeight, this.halfWidth);
            physicalWorld.addColliderBody(this.siblingColliderBody, this.siblingBody);
            this.siblingColliderBody.setCollisionGroups(5, 5);
            this.siblingColliderBody.setFriction(20.0);
            this.siblingColliderBody.setFrictionCombineRule(0);
            this.siblingColliderBody.setRestitution(0.0);
            this.siblingColliderBody.setRestitutionCombineRule(1);
        }

        if (physicalWorld instanceof ClientPhysicalWorld client) {
            client.registerPlayerEntity(this);
        }
    }

    /**
     * 记下"玩家本 tick 自己的位移"（原版移动学算出来的、碰撞前的那个位移）。
     *
     * <p>顺带每步把主刚体姿态复位成单位四元数、角速度清零 —— 与锁旋转等效，
     * 但即使锁被别的路径解开，姿态也不会慢慢漂。</p>
     */
    public void move(double x, double y, double z) {
        if (this.rigidBody != null && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)) {
            this.rigidBody.setRotation(new Quaterniond());
            this.rigidBody.setAngvel(new Vector3d());
            this.ownVelocity = new Vector3d(x, y, z).mul(20.0);
        }
    }

    /**
     * 每个物理子步之后跑：速度继承链（<b>不要改这个形态</b>，理由见类注释第 6 条）。
     */
    public void afterStep() {
        RigidBody main = this.rigidBody;
        RigidBody sibling = this.siblingBody;
        if (main == null || sibling == null || !sibling.isAttached()) {
            return;
        }
        this.rapierVelocity.set(sibling.getLinvel());
        Vector3d siblingLinvel = sibling.getLinvel();
        if (siblingLinvel == null) {
            return;
        }
        Vector3d target = new Vector3d(siblingLinvel).add(this.ownVelocity);
        Vector3d current = main.getLinvel();
        if (current != null && current.isFinite()) {
            Vector3d delta = new Vector3d(target).sub(current);
            if (delta.lengthSquared() > 1.0E-8) {
                main.setLinvel(target);
            }
        }
        sibling.setLinvel(this.ownVelocity);
        Vector3d pos = main.getPos();
        if (pos != null && pos.isFinite()) {
            sibling.setPos(pos);
        }
    }

    public Vector3d getPos() {
        RigidBody body = this.rigidBody;
        return body != null && body.isAttached() ? body.getPos() : null;
    }

    public Quaterniond getRotation() {
        RigidBody body = this.rigidBody;
        return body != null && body.isAttached() ? body.getRotation() : null;
    }

    public Vector3d getSiblingPos() {
        RigidBody body = this.siblingBody;
        return body != null && body.isAttached() ? body.getPos() : null;
    }

    public Quaterniond getSiblingRotation() {
        RigidBody body = this.siblingBody;
        return body != null && body.isAttached() ? body.getRotation() : null;
    }

    public Vector3d getLinvel() {
        RigidBody body = this.rigidBody;
        return body != null && body.isAttached() ? body.getLinvel() : null;
    }

    /** 碰撞箱半长（x/y/z）。 */
    public Vector3d halfExtents() {
        return new Vector3d(this.halfWidth, this.halfHeight, this.halfWidth);
    }

    public void setFriction(double friction) {
        if (this.colliderBody != null && Double.isFinite(friction) && !(friction < 0.0)) {
            if (Double.compare(this.friction, friction) != 0) {
                this.friction = friction;
                this.colliderBody.setFriction(friction);
                this.siblingColliderBody.setFriction(friction);
            }
        }
    }

    public float getFriction(float fallback) {
        return Double.isFinite(this.friction) ? (float) this.friction : fallback;
    }

    /** 拆掉双刚体并把本对象从世界注销。 */
    public void destroy() {
        PhysicalWorld world = this.physicalWorld;
        if (world instanceof ClientPhysicalWorld client) {
            client.unregisterPlayerEntity(this);
        }
        if (world != null) {
            if (this.colliderBody != null) {
                world.removeColliderBody(this.colliderBody);
            }
            if (this.siblingColliderBody != null) {
                world.removeColliderBody(this.siblingColliderBody);
            }
            if (this.rigidBody != null) {
                world.removeRigidBody(this.rigidBody);
            }
            if (this.siblingBody != null) {
                world.removeRigidBody(this.siblingBody);
            }
        }
        this.colliderBody = null;
        this.siblingColliderBody = null;
        this.rigidBody = null;
        this.siblingBody = null;
        this.physicalWorld = null;
        this.halfWidth = 0.0;
        this.halfHeight = 0.0;
    }
}
