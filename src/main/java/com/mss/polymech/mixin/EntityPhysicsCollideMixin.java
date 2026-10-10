package com.mss.polymech.mixin;

import com.mss.polymech.physics.PhysicalEntityCollider;
import com.mss.polymech.physics.PhysicalEntityColliderAccess;
import com.mss.polymech.physics.PhysicalEntityCollision;
import com.mss.polymech.physics.PhysicalEntityProbe;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家碰撞的物理体夹取入口 —— <b>与
 * {@code org.polaris2023.mps.collision.mixin.MixinEntity} 同形</b>（clean-room；照抄 0.5.0）。
 *
 * <p><b>结构翻转（0.1.3 → 0.5.0）</b>：0.1.3 在 {@code Entity.move} 的 HEAD cancel 原版移动、
 * 由 Rapier 双刚体驱动玩家位置；0.5.0 撤销 HEAD cancel，玩家移动交回原版 {@code Entity.move+collide}，
 * 只在 {@code collide} 的 <b>RETURN</b> 追加一次 Java 侧 OBB 扫掠解析（{@link PhysicalEntityCollision#resolve}），
 * 对「原版看不到的物理体方块」夹玩家位移 —— 原版天然防穿模，扫掠补物理体这一块。</p>
 *
 * <p><b>碰撞箱姿态（第一步修复：防止旋转视角穿模）</b>：6DOF 激活（太空）时，
 * 碰撞箱<b>跟随身体朝向旋转</b>（{@link SpacePlayerData#orientation}，即 0.5.0 的
 * {@code SpaceViewRotation.orientation} 等价物）—— 与 {@code SpaceTurnMixin} 的
 * 「物理碰撞箱也跟着身体转」一致。此前传 {@code null}（直立）会让可见身体与碰撞箱不一致，
 * 身体/头可以伸进物理体。非太空/未初始化时仍直立（{@code null}）。</p>
 *
 * <p><b>探针字段归属（PLAN「probe/collider 字段持有者」）</b>：本 mixin 是
 * {@code space$physicalEntityCollider}/{@code space$physicalEntityProbe} 两个字段的<b>唯一持有者</b>；
 * P3 的 {@code EntityPhysicsProbeMixin} 经 {@link PhysicalEntityColliderAccess#space$setPhysicalEntityProbe}
 * 写入探针，<b>只读不持有</b>，不改写本 mixin。</p>
 */
@Mixin(Entity.class)
public abstract class EntityPhysicsCollideMixin implements PhysicalEntityColliderAccess {

    @Unique
    private PhysicalEntityCollider space$physicalEntityCollider;
    @Unique
    private PhysicalEntityProbe space$physicalEntityProbe;

    @Override
    public PhysicalEntityCollider space$getPhysicalEntityCollider() {
        Entity entity = (Entity) (Object) this;
        PhysicalEntityCollider collider = this.space$physicalEntityCollider;
        if (collider == null || !collider.fits(entity)) {
            collider = PhysicalEntityCollider.create(entity);
            this.space$physicalEntityCollider = collider;
        }
        return collider;
    }

    @Override
    public PhysicalEntityProbe space$getPhysicalEntityProbe() {
        return this.space$physicalEntityProbe;
    }

    @Override
    public void space$setPhysicalEntityProbe(PhysicalEntityProbe probe) {
        this.space$physicalEntityProbe = probe;
    }

    /**
     * {@code Entity.collide} RETURN 注入：原版已夹过原版方块，这里追加对「物理体方块」的扫掠解析。
     * 生效条件照 0.5.0：本地玩家、非旁观、无 noPhysics。
     */
    @Inject(method = "collide", at = @At("RETURN"), cancellable = true)
    private void space$collideWithPhysicalBodies(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        if ((Entity) (Object) this instanceof LocalPlayer player) {
            if (!player.isSpectator() && !player.noPhysics) {
                Vec3 resolved = cir.getReturnValue();
                cir.setReturnValue(PhysicalEntityCollision.resolve(player, resolved, space$physicalRotation(player)));
            }
        }
    }

    /**
     * 碰撞箱姿态：6DOF 激活（{@link SpacePlayerData} 已初始化，即太空）时跟随身体朝向
     * （{@link SpacePlayerData#orientation}），否则直立（{@code null} = 单位四元数）。
     *
     * <p>照 space 0.5.0 的 {@code SpaceViewRotation.isActive(player) ? orientation() : null}；
     * 我方等价物是 {@link SpacePlayerData} —— 它的 {@code orientation()} 注释本就写着
     * 「物理碰撞体要跟着玩家朝向走」。</p>
     */
    @Unique
    private static Quaterniondc space$physicalRotation(LocalPlayer player) {
        SpacePlayerData data = SpacePlayerData.get(player);
        if (data == null || !data.isInitialized()) {
            return null;
        }
        Quaternionf q = data.orientation(new Quaternionf());
        return new Quaterniond(q.x, q.y, q.z, q.w);
    }

    /** 实体移除时释放探针与碰撞器（照 0.5.0 的 {@code space$releasePhysicalCollider}）。 */
    @Inject(method = "remove", at = @At("HEAD"))
    private void space$releasePhysicalCollider(Entity.RemovalReason reason, CallbackInfo ci) {
        if (this.space$physicalEntityProbe != null) {
            this.space$physicalEntityProbe.close();
        }
        this.space$physicalEntityProbe = null;
        this.space$physicalEntityCollider = null;
    }
}
