package com.mss.polymech.physics;

import com.mss.polymech.mps.rapier.helper.ColliderBody;
import net.minecraft.world.entity.Entity;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/**
 * 玩家碰撞形状工厂 —— <b>与
 * {@code org.polaris2023.mps.collision.physical.entity.PhysicalEntityCollider} 同形</b>
 * 的自有实现（clean-room；逐字照抄 0.5.0）。
 *
 * <p>{@link EntityShape} 的眼高 pivot + 盒底 offset + 旋转；默认直立（{@code rotation == null} → 单位四元数）。
 * 决策①：玩家碰撞箱直立 ⇒ collide RETURN 注入的 rotation 恒 null。</p>
 */
public final class PhysicalEntityCollider {
   private static final double DIMENSION_EPSILON = 1.0E-6;
   private final ColliderBody collider;
   private final double halfWidth;
   private final double halfHeight;

   private PhysicalEntityCollider(double halfWidth, double halfHeight) {
      this.halfWidth = halfWidth;
      this.halfHeight = halfHeight;
      this.collider = new ColliderBody(ColliderBody.Type.CUBOID, new Vector3d(), new Quaterniond(), 0.02, halfWidth, halfHeight, halfWidth);
   }

   public static PhysicalEntityCollider create(Entity entity) {
      double halfWidth = Math.max(1.0E-4, (double) entity.getBbWidth() * 0.5);
      double halfHeight = Math.max(1.0E-4, (double) entity.getBbHeight() * 0.5);
      return new PhysicalEntityCollider(halfWidth, halfHeight);
   }

   /**
    * 离线测试缝：直接按半宽/半高构造（绕过 {@code Entity} 的 {@code getBbWidth/getBbHeight}，
    * 供离线探针 {@code PhysicalEntityProbeTest} 用）。与 {@code PhysicalEntityCollision.setBlockCollision}
    * 是同一类「照抄方法 + 抽离测试缝」，不是另一套实现。
    */
   public static PhysicalEntityCollider create(double halfWidth, double halfHeight) {
      return new PhysicalEntityCollider(Math.max(1.0E-4, halfWidth), Math.max(1.0E-4, halfHeight));
   }

   public boolean fits(Entity entity) {
      double halfWidth = Math.max(1.0E-4, (double) entity.getBbWidth() * 0.5);
      double halfHeight = Math.max(1.0E-4, (double) entity.getBbHeight() * 0.5);
      return Math.abs(this.halfWidth - halfWidth) < 1.0E-6 && Math.abs(this.halfHeight - halfHeight) < 1.0E-6;
   }

   public ColliderBody collider() {
      return this.collider;
   }

   public Vector3d halfExtents() {
      return new Vector3d(this.halfWidth, this.halfHeight, this.halfWidth);
   }

   public Vector3d center(Entity entity) {
      return new Vector3d(entity.getX(), entity.getY() + this.halfHeight, entity.getZ());
   }

   public EntityShape at(Entity entity) {
      return this.at(entity, null);
   }

   public EntityShape at(Entity entity, Quaterniondc rotation) {
      Quaterniond q = rotation == null ? new Quaterniond() : new Quaterniond(rotation);
      // ★ 旋转支点 = 碰撞箱中心（entity.getY() + halfHeight），与 SpaceBodyTiltMixin 的渲染支点
      //   SpacePlayerData.bodyCenterOffset（普通姿态 = getBbHeight()*0.5，与本处 halfHeight 同值）同源。
      //   0.5.0 参考用的是眼高 pivot（那是 space「身体绕相机转」的设计）；我方模型绕盒中心转，
      //   若仍用眼高 pivot，旋转 90° 后碰撞箱与可见身体会错开约 0.72 格 —— 头又会伸进物理体。
      Vector3d pivot = new Vector3d(entity.getX(), entity.getY() + this.halfHeight, entity.getZ());
      Vector3d offset = new Vector3d(0.0, 0.0, 0.0);
      return new EntityShape(this.collider, pivot, offset, this.halfExtents(), q);
   }

   public static record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
      public Bounds expandTowards(Vector3d displacement) {
         return new Bounds(
            Math.min(this.minX, this.minX + displacement.x),
            Math.min(this.minY, this.minY + displacement.y),
            Math.min(this.minZ, this.minZ + displacement.z),
            Math.max(this.maxX, this.maxX + displacement.x),
            Math.max(this.maxY, this.maxY + displacement.y),
            Math.max(this.maxZ, this.maxZ + displacement.z)
         );
      }

      public Bounds inflate(double amount) {
         return new Bounds(
            this.minX - amount, this.minY - amount, this.minZ - amount, this.maxX + amount, this.maxY + amount, this.maxZ + amount
         );
      }

      public boolean intersects(Bounds other) {
         return this.maxX >= other.minX
            && this.minX <= other.maxX
            && this.maxY >= other.minY
            && this.minY <= other.maxY
            && this.maxZ >= other.minZ
            && this.minZ <= other.maxZ;
      }
   }

   public static record EntityShape(ColliderBody collider, Vector3d pivot, Vector3d offset, Vector3d halfExtents, Quaterniond rotation) {
      public Vector3d center() {
         return this.rotation.transform(new Vector3d(this.offset)).add(this.pivot);
      }

      public EntityShape move(Vector3d displacement) {
         return new EntityShape(
            this.collider,
            new Vector3d(this.pivot).add(displacement),
            new Vector3d(this.offset),
            new Vector3d(this.halfExtents),
            new Quaterniond(this.rotation)
         );
      }

      public Vector3d axisX() {
         return this.rotation.transform(new Vector3d(this.halfExtents.x, 0.0, 0.0));
      }

      public Vector3d axisY() {
         return this.rotation.transform(new Vector3d(0.0, this.halfExtents.y, 0.0));
      }

      public Vector3d axisZ() {
         return this.rotation.transform(new Vector3d(0.0, 0.0, this.halfExtents.z));
      }

      public Bounds bounds() {
         Vector3d center = this.center();
         Vector3d x = this.axisX();
         Vector3d y = this.axisY();
         Vector3d z = this.axisZ();
         double extentX = Math.abs(x.x) + Math.abs(y.x) + Math.abs(z.x);
         double extentY = Math.abs(x.y) + Math.abs(y.y) + Math.abs(z.y);
         double extentZ = Math.abs(x.z) + Math.abs(y.z) + Math.abs(z.z);
         return new Bounds(
            center.x - extentX, center.y - extentY, center.z - extentZ, center.x + extentX, center.y + extentY, center.z + extentZ
         );
      }

      public Bounds sweptBounds(Vector3d displacement, double margin) {
         return this.bounds().expandTowards(displacement).inflate(margin);
      }
   }
}
