package com.mss.polymech.physics;

/**
 * 玩家碰撞器/载速探针的访问接口 —— <b>与
 * {@code org.polaris2023.mps.collision.physical.entity.PhysicalEntityColliderAccess} 同形</b>，
 * 比 0.5.0 多一个 {@link #space$setPhysicalEntityProbe(PhysicalEntityProbe)} setter：
 * 供 P3 的探针接线经此写入、而不改写 P2 的 mixin（见 PLAN「probe/collider 字段持有者」）。
 *
 * <p>实现方 = {@code EntityPhysicsCollideMixin}（collider 惰性建 + probe 字段唯一持有者）。</p>
 */
public interface PhysicalEntityColliderAccess {
   PhysicalEntityCollider space$getPhysicalEntityCollider();

   PhysicalEntityProbe space$getPhysicalEntityProbe();

   void space$setPhysicalEntityProbe(PhysicalEntityProbe probe);
}
