package com.mss.polymech.physics;

import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import com.mss.polymech.mps.rapier.helper.ColliderBody;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 载速探针 —— <b>与
 * {@code org.polaris2023.mps.collision.physical.entity.PhysicalEntityProbe} 同形</b>
 * 的自有实现（clean-room；逐字照抄 0.5.0）。
 *
 * <p><b>⚠ 归属说明（P1 → P3 交接）</b>：本文件是 TASKS.json 里 P3 的 inScope 文件。P1 之所以提前
 * 移植它，是因为 {@link PhysicalEntityColliderAccess}（P1 的接口）的
 * {@code space$getPhysicalEntityProbe}/{@code space$setPhysicalEntityProbe} 方法签名硬依赖本类，
 * 而 TASKS 的依赖链把 P3 排在 P2 之后、P1 的 compileJava 无法通过 —— 构成编译级循环依赖。
 * 因此 P1 按 0.5.0 逐字移植本类以解死锁，<b>请 P3 认领并沿用</b>（P3 的 mixin 与离线测试仍是 P3 的工作）。</p>
 *
 * <p>职责：独立 DYNAMIC 探针刚体（质量 50、CCD 关、碰撞组 (2,5)），测「被移动物理体带着走」的载速，
 * <b>不参与玩家位置求解</b>。载速由 {@link #velocity()} 返回副本，接线方 ÷20 叠加到 movement。</p>
 */
public final class PhysicalEntityProbe implements AutoCloseable {
   private static final double PROBE_MASS = 50.0;
   private static final double PROBE_FRICTION = 100.0;
   private static final int STEPS_PER_WINDOW = 5;
   private final PhysicalEntityCollider shape;
   private final RigidBody rigidBody;
   private final ColliderBody collider;
   private final Runnable stepListener = this::onPhysicsStep;
   private volatile PhysicalWorld world;
   private volatile Vector3d targetPosition = new Vector3d();
   private volatile Vector3d velocity = new Vector3d();
   private volatile boolean lastGrounded;

   public PhysicalEntityProbe(PhysicalEntityCollider shape) {
      this.shape = shape;
      Vector3d half = shape.halfExtents();
      this.rigidBody = new RigidBody(
         RigidBody.Type.DYNAMIC, new Vector3d(), new Quaterniond(), new Vector3d(), 50.0, new Vector3d(), new Vector3d(), new Vector3d(), 0.0, 0.0, 1.0, true
      );
      this.collider = new ColliderBody(ColliderBody.Type.CUBOID, new Vector3d(), new Quaterniond(), 0.02, half.x, half.y, half.z);
   }

   public PhysicalEntityCollider shape() {
      return this.shape;
   }

   public RigidBody rigidBody() {
      return this.rigidBody;
   }

   public ColliderBody collider() {
      return this.collider;
   }

   public synchronized void bind(PhysicalWorld physicalWorld) {
      if (physicalWorld != null && physicalWorld != this.world) {
         this.release();
         this.world = physicalWorld;
         physicalWorld.addRigidBody(this.rigidBody);
         physicalWorld.addColliderBody(this.collider, this.rigidBody);
         this.collider.setCollisionGroups(2, 5);
         this.collider.setFriction(100.0);
         this.collider.setFrictionCombineRule(0);
         this.collider.setRestitution(0.0);
         this.collider.setRestitutionCombineRule(1);
         this.rigidBody.setCCD(false);
         this.lastGrounded = false;
         physicalWorld.addTickListener(this.stepListener);
      }
   }

   public void sync(Entity entity) {
      this.targetPosition = this.shape.center(entity);
      this.lastGrounded = entity.onGround();
   }

   /**
    * 离线测试缝：直接按「碰撞箱中心 + 着地状态」同步（绕过 {@code Entity}，供离线探针
    * {@code PhysicalEntityProbeTest} 用）。与 {@code PhysicalEntityCollider.create(double,double)}
    * 是同一类「照抄方法 + 抽离测试缝」。
    */
   public void sync(Vector3d targetPosition, boolean grounded) {
      this.targetPosition = new Vector3d(targetPosition);
      this.lastGrounded = grounded;
   }

   private void onPhysicsStep() {
      PhysicalWorld currentWorld = this.world;
      if (currentWorld != null && this.rigidBody.isAttached()) {
         if (currentWorld.tick % 5L == 0L) {
            Vector3d current = this.rigidBody.getLinvel();
            if (current == null || !current.isFinite()) {
               current = new Vector3d();
            }

            double elapsed = 5.0 * currentWorld.getTickTime();
            Vector3d gravity = currentWorld.getG();
            this.velocity = !this.lastGrounded && gravity != null ? new Vector3d(current).sub(new Vector3d(gravity).mul(elapsed)) : new Vector3d(current);
            this.rigidBody.setPos(new Vector3d(this.targetPosition));
            this.rigidBody.setRotation(new Quaterniond());
            this.rigidBody.setAngvel(new Vector3d());
            this.rigidBody.setLinvel(new Vector3d());
         }
      }
   }

   public Vector3d velocity() {
      return new Vector3d(this.velocity);
   }

   @Override
   public void close() {
      this.release();
   }

   public synchronized void release() {
      PhysicalWorld currentWorld = this.world;
      if (currentWorld != null) {
         currentWorld.removeTickListener(this.stepListener);
         if (currentWorld.hasColliderBody(this.collider)) {
            currentWorld.removeColliderBody(this.collider);
         }

         if (currentWorld.hasRigidBody(this.rigidBody)) {
            currentWorld.removeRigidBody(this.rigidBody);
         }
      }

      this.world = null;
      this.velocity = new Vector3d();
   }
}
