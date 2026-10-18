package com.mss.polymech.physics;

import com.mss.polymech.mps.physical.helper.DoubleAABB;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import com.mss.polymech.space.SpaceMovementGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 玩家 ↔ 物理体 OBB 扫掠解析器 —— <b>与
 * {@code org.polaris2023.mps.collision.physical.entity.PhysicalEntityCollision} 同形</b>
 * 的自有实现（clean-room；核心算法逐字照抄 0.5.0，见 {@code decompiled-space/0.5.0/.../PhysicalEntityCollision.java}）。
 *
 * <p><b>唯一的 Minecraft 耦合点</b>（{@link #hasCollision} 里的
 * {@code Block.stateById(...).getCollisionShape(...)}）被抽成可注入的 {@link BlockCollision} 谓词
 * （{@link #setBlockCollision}），使核心扫掠离线可单测 —— 这是照抄算法上的「测试缝」，不是另一套实现。</p>
 *
 * <p>数据源 = mps {@link PhysicalBody}（照抄 0.5.0 的 {@code snapshotBodies}，迭代
 * {@link PhysicalWorld#getAllPhysicalBody()} 返回的 mps PhysicalBody，不再是 ClientBody 适配层）；
 * 唯一的数据源适配是 {@code worldAABB} 用项目的 {@link DoubleAABB}（MC 1.21.1 未随 joml
 * {@code primitives.AABBd}），字段名/语义逐一对齐。</p>
 */
public final class PhysicalEntityCollision {
   private static final int MIN_BLOCK = -64;
   private static final int MAX_BLOCK = 63;
   private static final int MAX_SWEEP_SEGMENTS = 128;
   private static final int MAX_SLIDE_ITERATIONS = 4;
   private static final double MAX_SEGMENT_LENGTH = 2.0;
   private static final double AXIS_EPSILON = 1.0E-10;
   private static final double TIME_EPSILON = 1.0E-9;
   private static final double CONTACT_OFFSET = 1.0E-5;
   private static final Map<Integer, Boolean> COLLISION_STATE_CACHE = new ConcurrentHashMap<>();

   /** 唯一的 Minecraft 耦合点（可注入，供离线单测替换）。默认 = {@link #mcBlockCollision}。 */
   private static volatile BlockCollision blockCollision = PhysicalEntityCollision::mcBlockCollision;

   /** 可注入的「方块 state id → 是否有碰撞形状」谓词（离线测试缝）。 */
   @FunctionalInterface
   public interface BlockCollision {
      boolean hasCollision(int blockState);
   }

   /** 注入离线谓词；{@code null} 复位为 Minecraft 默认实现并清缓存。 */
   public static void setBlockCollision(BlockCollision predicate) {
      blockCollision = predicate == null ? PhysicalEntityCollision::mcBlockCollision : predicate;
      COLLISION_STATE_CACHE.clear();
   }

   /** Minecraft 默认实现：方块有非空碰撞形状即有碰撞。 */
   private static boolean mcBlockCollision(int blockState) {
      return !Block.stateById(blockState).getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
   }

   public static Vec3 resolve(Entity entity, Vec3 requestedMovement, Quaterniondc rotation) {
      if (entity == null || requestedMovement == null) {
         return requestedMovement;
      } else if (!entity.level().isClientSide) {
         return requestedMovement;
      } else if (entity instanceof PhysicalEntityColliderAccess access) {
         PhysicalWorld physicalWorld = clientPhysicalWorld(entity.level());
         if (physicalWorld == null) {
            return requestedMovement;
         } else {
            PhysicalEntityCollider collider = access.space$getPhysicalEntityCollider();
            if (collider != null && collider.fits(entity)) {
               // ★ 门：本维度一个物理体都没有 ⇒ 连快照都不必建（原来会建一个空 ArrayList 再早退）。
               //   这条门保证"主世界没有物理体时，碰撞与位移=原版"（用户 2026-10-12 指令）。
               if (!physicalWorld.hasPhysicalBodies()) {
                  SpaceMovementGate.noteSweepBlocked(entity.level());
                  return requestedMovement;
               }
               SpaceMovementGate.noteSweepActive(entity.level());
               List<BodySnapshot> bodies = snapshotBodies(physicalWorld);
               if (bodies.isEmpty()) {
                  return requestedMovement;
               } else {
                  Vector3d resolved = new Vector3d();
                  PhysicalEntityCollider.EntityShape currentShape = collider.at(entity, rotation);

                  // ★ 第一步修复：先推出「旋转视角导致的静止重叠」。
                  // 必须跑在 movement 早退之前 —— 纯旋转时 movement≈0，若照 0.5.0 早退，
                  // 头就会留在物理体内部。
                  Vector3d depenetration = depenetrate(currentShape, bodies);
                  resolved.add(depenetration);
                  currentShape = currentShape.move(depenetration);

                  // 平移扫掠：照抄 0.5.0；movement 过小时跳过（没有平移要夹）。
                  Vector3d requested = new Vector3d(requestedMovement.x, requestedMovement.y, requestedMovement.z);
                  if (requested.lengthSquared() >= 1.0E-10) {
                     int segmentCount = segmentCount(requested);
                     Vector3d segment = new Vector3d(requested).div((double) segmentCount);
                     for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
                        Vector3d segmentMovement = sweepAndSlide(currentShape, segment, bodies);
                        resolved.add(segmentMovement);
                        currentShape = currentShape.move(segmentMovement);
                     }
                  }

                  return !resolved.isFinite() ? Vec3.ZERO : new Vec3(resolved.x, resolved.y, resolved.z);
               }
            } else {
               return requestedMovement;
            }
         }
      } else {
         return requestedMovement;
      }
   }

   /**
    * 离线测试缝：纯 Java 核心（不含 Minecraft {@link Entity}）。与 {@link #resolve(Entity, Vec3, Quaterniondc)}
    * 的「分段 + sweepAndSlide」循环逐字一致，只是把「Entity → EntityShape」那一层交给调用方构造好的
    * {@code startShape}，数据源交给调用方注入的 mps {@link PhysicalBody} 列表。核心算法零改动。
    */
   public static Vector3d resolve(
      PhysicalEntityCollider.EntityShape startShape, Vector3d requestedMovement, List<PhysicalBody> bodies
   ) {
      List<BodySnapshot> snapshots = snapshotBodies(bodies);
      if (snapshots.isEmpty()) {
         return new Vector3d(requestedMovement);
      }
      Vector3d resolved = new Vector3d();
      PhysicalEntityCollider.EntityShape currentShape = startShape;

      // 与 MC 入口一致：先推出静止重叠（不依赖 movement）。
      Vector3d depenetration = depenetrate(currentShape, snapshots);
      resolved.add(depenetration);
      currentShape = currentShape.move(depenetration);

      if (requestedMovement.lengthSquared() >= 1.0E-10) {
         int segmentCount = segmentCount(requestedMovement);
         Vector3d segment = new Vector3d(requestedMovement).div((double) segmentCount);
         for (int segmentIndex = 0; segmentIndex < segmentCount; segmentIndex++) {
            Vector3d segmentMovement = sweepAndSlide(currentShape, segment, snapshots);
            resolved.add(segmentMovement);
            currentShape = currentShape.move(segmentMovement);
         }
      }

      return resolved;
   }

   private static PhysicalWorld clientPhysicalWorld(Level level) {
      if (!level.isClientSide) {
         return null;
      } else {
         ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
         if (physicalWorld == null) {
            return null;
         } else {
            ResourceLocation worldLevel = physicalWorld.getLevel();
            if (worldLevel == null) {
               return null;
            } else {
               return worldLevel.equals(level.dimension().location()) ? physicalWorld : null;
            }
         }
      }
   }

   private static List<BodySnapshot> snapshotBodies(PhysicalWorld physicalWorld) {
      List<BodySnapshot> snapshots = new ArrayList<>();

      for (PhysicalBody physicalBody : physicalWorld.getAllPhysicalBody()) {
         BodySnapshot snapshot = BodySnapshot.create(physicalBody);
         if (snapshot != null) {
            snapshots.add(snapshot);
         }
      }

      return snapshots;
   }

   private static List<BodySnapshot> snapshotBodies(List<PhysicalBody> physicalBodies) {
      List<BodySnapshot> snapshots = new ArrayList<>();

      for (PhysicalBody physicalBody : physicalBodies) {
         BodySnapshot snapshot = BodySnapshot.create(physicalBody);
         if (snapshot != null) {
            snapshots.add(snapshot);
         }
      }

      return snapshots;
   }

   private static int segmentCount(Vector3dc movement) {
      double maxComponent = Math.max(Math.abs(movement.x()), Math.max(Math.abs(movement.y()), Math.abs(movement.z())));
      return Math.max(1, Math.min(128, (int) Math.ceil(maxComponent / 2.0)));
   }

   private static Vector3d sweepAndSlide(
      PhysicalEntityCollider.EntityShape startShape, Vector3dc requestedMovement, List<BodySnapshot> bodies
   ) {
      Vector3d remaining = new Vector3d(requestedMovement);
      Vector3d resolved = new Vector3d();
      PhysicalEntityCollider.EntityShape currentShape = startShape;

      for (int iteration = 0; iteration < 4 && !(remaining.lengthSquared() < 1.0E-10); iteration++) {
         SweepHit hit = findEarliestHit(currentShape, remaining, bodies);
         if (hit == null) {
            resolved.add(remaining);
            break;
         }

         Vector3d advance = new Vector3d(remaining).mul(hit.time());
         double approachDistance = Math.max(0.0, -advance.dot(hit.normal()));
         if (hit.time() > 1.0E-9 && approachDistance > 0.0) {
            advance.fma(Math.min(1.0E-5, approachDistance), hit.normal());
         }

         resolved.add(advance);
         currentShape = currentShape.move(advance);
         remaining.mul(Math.max(0.0, 1.0 - hit.time()));
         double intoSurface = remaining.dot(hit.normal());
         if (intoSurface < 0.0) {
            remaining.fma(-intoSurface, hit.normal());
         }
      }

      return resolved;
   }

   private static SweepHit findEarliestHit(
      PhysicalEntityCollider.EntityShape shape, Vector3dc movement, List<BodySnapshot> bodies
   ) {
      PhysicalEntityCollider.Bounds sweptBounds = shape.sweptBounds(new Vector3d(movement), 1.0E-5);
      SweepHit earliest = null;

      for (BodySnapshot body : bodies) {
         if (sweptBounds.intersects(body.worldBounds)) {
            LocalBounds localBounds = body.toLocalBounds(sweptBounds);
            if (localBounds.intersectsBody()) {
               int minX = clampBlock(localBounds.minX());
               int minY = clampBlock(localBounds.minY());
               int minZ = clampBlock(localBounds.minZ());
               int maxX = clampBlock(localBounds.maxX());
               int maxY = clampBlock(localBounds.maxY());
               int maxZ = clampBlock(localBounds.maxZ());

               for (int x = minX; x <= maxX; x++) {
                  for (int y = minY; y <= maxY; y++) {
                     for (int z = minZ; z <= maxZ; z++) {
                        if (hasCollision(body.physicalBody, x, y, z)) {
                           SweepHit hit = sweepBoxAgainstBlock(shape, movement, body, x, y, z);
                           if (hit != null
                              && (
                                 earliest == null
                                    || hit.time() < earliest.time() - 1.0E-9
                                    || Math.abs(hit.time() - earliest.time()) <= 1.0E-9 && movement.dot(hit.normal()) < movement.dot(earliest.normal())
                              )) {
                              earliest = hit;
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return earliest;
   }

   private static SweepHit sweepBoxAgainstBlock(
      PhysicalEntityCollider.EntityShape shape, Vector3dc movement, BodySnapshot body, int blockX, int blockY, int blockZ
   ) {
      Vector3d entityCenter = new Vector3d(shape.center());
      Vector3d entityAxisX = shape.axisX();
      Vector3d entityAxisY = shape.axisY();
      Vector3d entityAxisZ = shape.axisZ();
      Vector3d blockCenter = body.blockCenter(blockX, blockY, blockZ);
      Vector3d relativeCenter = entityCenter.sub(blockCenter);
      double entryTime = Double.NEGATIVE_INFINITY;
      double exitTime = Double.POSITIVE_INFINITY;
      double smallestPenetration = Double.POSITIVE_INFINITY;
      Vector3d entryNormal = null;
      Vector3d overlapNormal = null;

      for (Vector3dc axis : body.separatingAxes) {
         double centerDistance = relativeCenter.dot(axis);
         double entityRadius = Math.abs(entityAxisX.dot(axis)) + Math.abs(entityAxisY.dot(axis)) + Math.abs(entityAxisZ.dot(axis));
         double blockRadius = 0.5 * (Math.abs(body.axisX.dot(axis)) + Math.abs(body.axisY.dot(axis)) + Math.abs(body.axisZ.dot(axis)));
         double combinedRadius = entityRadius + blockRadius;
         double penetration = combinedRadius - Math.abs(centerDistance);
         if (penetration < smallestPenetration) {
            smallestPenetration = penetration;
            overlapNormal = new Vector3d(axis).mul(centerDistance >= 0.0 ? 1.0 : -1.0);
         }

         double axisSpeed = movement.dot(axis);
         if (Math.abs(axisSpeed) < 1.0E-10) {
            if (Math.abs(centerDistance) > combinedRadius + 1.0E-5) {
               return null;
            }
         } else {
            double first = (-combinedRadius - centerDistance) / axisSpeed;
            double second = (combinedRadius - centerDistance) / axisSpeed;
            double axisEntry = Math.min(first, second);
            double axisExit = Math.max(first, second);
            if (axisEntry > entryTime) {
               entryTime = axisEntry;
               double contactDistance = centerDistance + axisSpeed * axisEntry;
               entryNormal = new Vector3d(axis).mul(contactDistance >= 0.0 ? 1.0 : -1.0);
            }

            exitTime = Math.min(exitTime, axisExit);
            if (entryTime > exitTime + 1.0E-9) {
               return null;
            }
         }
      }

      if (exitTime < -1.0E-9 || entryTime > 1.000000001) {
         return null;
      } else if (entryTime < 0.0) {
         return overlapNormal != null && !(movement.dot(overlapNormal) >= -1.0E-10) ? new SweepHit(0.0, overlapNormal) : null;
      } else {
         return entryNormal != null && !(movement.dot(entryNormal) >= -1.0E-10)
            ? new SweepHit(Math.max(0.0, Math.min(1.0, entryTime)), entryNormal)
            : null;
      }
   }

   private static boolean hasCollision(PhysicalBody physicalBody, int x, int y, int z) {
      int blockState = physicalBody.getBlockStateInt(x, y, z);
      return COLLISION_STATE_CACHE.computeIfAbsent(blockState, s -> blockCollision.hasCollision(s));
   }

   private static int clampBlock(double coordinate) {
      return Math.max(-64, Math.min(63, (int) Math.floor(coordinate)));
   }

   /**
    * 静止重叠推出（防止旋转视角把头/身体伸进物理体）。
    *
    * <p>0.5.0 的 {@code sweepBoxAgainstBlock} 只在 {@code movement.dot(overlapNormal) < -1e-10}
    * （朝重叠法向移动）时返回 overlap hit；而"原地旋转视角"导致的穿透 movement≈0，
    * 会被 resolve 早退跳过。这里不依赖 movement，把当前碰撞箱从静态重叠里沿最小穿透轴
    * 推出去（迭代 {@value #MAX_SLIDE_ITERATIONS} 次以处理墙角这类多面同时重叠）。</p>
    */
   private static Vector3d depenetrate(PhysicalEntityCollider.EntityShape startShape, List<BodySnapshot> bodies) {
      Vector3d total = new Vector3d();
      PhysicalEntityCollider.EntityShape current = startShape;
      for (int iteration = 0; iteration < MAX_SLIDE_ITERATIONS; iteration++) {
         Depenetration deepest = findDeepestOverlap(current, bodies);
         if (deepest == null) {
            break;
         }
         Vector3d push = new Vector3d(deepest.normal()).mul(deepest.depth() + CONTACT_OFFSET);
         total.add(push);
         current = current.move(push);
      }
      return total;
   }

   /** 找当前形状与任一物理体方块的<b>最深</b>静态重叠（SAT）；无重叠返回 {@code null}。 */
   private static Depenetration findDeepestOverlap(
      PhysicalEntityCollider.EntityShape shape, List<BodySnapshot> bodies
   ) {
      PhysicalEntityCollider.Bounds bounds = shape.bounds().inflate(1.0E-5);
      Depenetration deepest = null;
      for (BodySnapshot body : bodies) {
         if (!bounds.intersects(body.worldBounds)) {
            continue;
         }
         LocalBounds local = body.toLocalBounds(bounds);
         if (!local.intersectsBody()) {
            continue;
         }
         int minX = clampBlock(local.minX());
         int minY = clampBlock(local.minY());
         int minZ = clampBlock(local.minZ());
         int maxX = clampBlock(local.maxX());
         int maxY = clampBlock(local.maxY());
         int maxZ = clampBlock(local.maxZ());
         for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
               for (int z = minZ; z <= maxZ; z++) {
                  if (hasCollision(body.physicalBody, x, y, z)) {
                     Depenetration d = overlapOf(shape, body, x, y, z);
                     if (d != null && (deepest == null || d.depth() > deepest.depth())) {
                        deepest = d;
                     }
                  }
               }
            }
         }
      }
      return deepest;
   }

   /**
    * 玩家 OBB × 物理体方块 OBB 的静态重叠（SAT 15 轴）：返回最小穿透轴与深度；
    * 任一轴分离即无重叠（返回 {@code null}）。与 {@code sweepBoxAgainstBlock} 用同一套分离轴。
    */
   private static Depenetration overlapOf(
      PhysicalEntityCollider.EntityShape shape, BodySnapshot body, int blockX, int blockY, int blockZ
   ) {
      Vector3d entityCenter = new Vector3d(shape.center());
      Vector3d entityAxisX = shape.axisX();
      Vector3d entityAxisY = shape.axisY();
      Vector3d entityAxisZ = shape.axisZ();
      Vector3d blockCenter = body.blockCenter(blockX, blockY, blockZ);
      Vector3d relativeCenter = entityCenter.sub(blockCenter);
      double smallestPenetration = Double.POSITIVE_INFINITY;
      Vector3d overlapNormal = null;

      for (Vector3dc axis : body.separatingAxes) {
         double centerDistance = relativeCenter.dot(axis);
         double entityRadius = Math.abs(entityAxisX.dot(axis)) + Math.abs(entityAxisY.dot(axis)) + Math.abs(entityAxisZ.dot(axis));
         double blockRadius = 0.5 * (Math.abs(body.axisX.dot(axis)) + Math.abs(body.axisY.dot(axis)) + Math.abs(body.axisZ.dot(axis)));
         double penetration = entityRadius + blockRadius - Math.abs(centerDistance);
         if (penetration <= 0.0) {
            return null; // 该轴分离 ⇒ 不相交
         }
         if (penetration < smallestPenetration) {
            smallestPenetration = penetration;
            overlapNormal = new Vector3d(axis).mul(centerDistance >= 0.0 ? 1.0 : -1.0);
         }
      }

      return overlapNormal == null ? null : new Depenetration(overlapNormal, smallestPenetration);
   }

   private static record Depenetration(Vector3d normal, double depth) {
   }

   private static final class BodySnapshot {
      private static final Vector3dc WORLD_X = new Vector3d(1.0, 0.0, 0.0);
      private static final Vector3dc WORLD_Y = new Vector3d(0.0, 1.0, 0.0);
      private static final Vector3dc WORLD_Z = new Vector3d(0.0, 0.0, 1.0);
      private final PhysicalBody physicalBody;
      private final Vector3d position;
      private final Quaterniond inverseRotation;
      private final Vector3d axisX;
      private final Vector3d axisY;
      private final Vector3d axisZ;
      private final Vector3dc[] separatingAxes;
      private final PhysicalEntityCollider.Bounds worldBounds;

      private BodySnapshot(PhysicalBody physicalBody, Vector3d position, Quaterniond rotation) {
         this.physicalBody = physicalBody;
         this.position = position;
         this.inverseRotation = new Quaterniond(rotation).conjugate();
         this.axisX = rotation.transform(new Vector3d(WORLD_X)).normalize();
         this.axisY = rotation.transform(new Vector3d(WORLD_Y)).normalize();
         this.axisZ = rotation.transform(new Vector3d(WORLD_Z)).normalize();
         this.separatingAxes = createSeparatingAxes(this.axisX, this.axisY, this.axisZ);
         DoubleAABB tight = physicalBody.worldAABB;
         if (PhysicalBody.isValidAABB(tight)) {
            this.worldBounds = new PhysicalEntityCollider.Bounds(tight.minX, tight.minY, tight.minZ, tight.maxX, tight.maxY, tight.maxZ);
         } else {
            double extent = 64.0;
            double extentX = extent * (Math.abs(this.axisX.x) + Math.abs(this.axisY.x) + Math.abs(this.axisZ.x));
            double extentY = extent * (Math.abs(this.axisX.y) + Math.abs(this.axisY.y) + Math.abs(this.axisZ.y));
            double extentZ = extent * (Math.abs(this.axisX.z) + Math.abs(this.axisY.z) + Math.abs(this.axisZ.z));
            this.worldBounds = new PhysicalEntityCollider.Bounds(
               position.x - extentX, position.y - extentY, position.z - extentZ, position.x + extentX, position.y + extentY, position.z + extentZ
            );
         }
      }

      private static BodySnapshot create(PhysicalBody physicalBody) {
         Vector3d position = physicalBody.getPos();
         Quaterniond rotation = physicalBody.getRotation();
         if (position != null && rotation != null) {
            return position.isFinite() && rotation.isFinite() && !(rotation.lengthSquared() < 1.0E-10)
               ? new BodySnapshot(physicalBody, position, new Quaterniond(rotation).normalize())
               : null;
         } else {
            return null;
         }
      }

      private Vector3d blockCenter(int x, int y, int z) {
         return new Vector3d(this.position).fma((double) x + 0.5, this.axisX).fma((double) y + 0.5, this.axisY).fma((double) z + 0.5, this.axisZ);
      }

      private LocalBounds toLocalBounds(PhysicalEntityCollider.Bounds worldBox) {
         Vector3d min = new Vector3d(Double.POSITIVE_INFINITY);
         Vector3d max = new Vector3d(Double.NEGATIVE_INFINITY);
         double[] xs = new double[]{worldBox.minX(), worldBox.maxX()};
         double[] ys = new double[]{worldBox.minY(), worldBox.maxY()};
         double[] zs = new double[]{worldBox.minZ(), worldBox.maxZ()};

         for (double x : xs) {
            for (double y : ys) {
               for (double z : zs) {
                  Vector3d local = new Vector3d(x, y, z).sub(this.position);
                  this.inverseRotation.transform(local);
                  min.min(local);
                  max.max(local);
               }
            }
         }

         return new LocalBounds(min.x - 1.0E-5, min.y - 1.0E-5, min.z - 1.0E-5, max.x + 1.0E-5, max.y + 1.0E-5, max.z + 1.0E-5);
      }

      private static Vector3dc[] createSeparatingAxes(Vector3dc axisX, Vector3dc axisY, Vector3dc axisZ) {
         List<Vector3dc> axes = new ArrayList<>(15);
         axes.add(WORLD_X);
         axes.add(WORLD_Y);
         axes.add(WORLD_Z);
         axes.add(new Vector3d(axisX));
         axes.add(new Vector3d(axisY));
         axes.add(new Vector3d(axisZ));
         Vector3dc[] worldAxes = new Vector3dc[]{WORLD_X, WORLD_Y, WORLD_Z};
         Vector3dc[] bodyAxes = new Vector3dc[]{axisX, axisY, axisZ};

         for (Vector3dc worldAxis : worldAxes) {
            for (Vector3dc bodyAxis : bodyAxes) {
               Vector3d cross = worldAxis.cross(bodyAxis, new Vector3d());
               if (cross.lengthSquared() > 1.0E-10) {
                  axes.add(cross.normalize());
               }
            }
         }

         return axes.toArray(Vector3dc[]::new);
      }
   }

   private static record LocalBounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
      private boolean intersectsBody() {
         return this.maxX >= -64.0 && this.minX <= 64.0 && this.maxY >= -64.0 && this.minY <= 64.0 && this.maxZ >= -64.0 && this.minZ <= 64.0;
      }
   }

   private static record SweepHit(double time, Vector3d normal) {
   }
}
