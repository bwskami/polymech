package com.mss.polymech.mixin;

import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.physics.PhysicalEntityCollider;
import com.mss.polymech.physics.PhysicalEntityColliderAccess;
import com.mss.polymech.physics.PhysicalEntityProbe;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 载速探针接线 —— 照 space 0.5.0 的 {@code MixinEntity.space$syncProbe}/{@code space$addProbeVelocity}
 * 语义（clean-room）。
 *
 * <p><b>接线点（用户拍板）</b>：{@code travel} HEAD，载速叠加到 move 输入（travelVector），
 * 不是 collide RETURN 后的已夹位移。载速作为「move 输入」叠加，不经过 collide 二次夹取，
 * 语义与参考 {@code space$addProbeVelocity}（{@code movement + velocity()/20}）一致。</p>
 *
 * <p><b>与 SpaceFlightMixin 的分工</b>：本 mixin 在<b>非飞行</b>时叠加载速；飞行时
 * {@code SpaceFlightMixin} 在 {@code Player.travel} HEAD cancel，本 mixin 因
 * {@code getAbilities().flying} 判据直接放行，两者互不干扰。</p>
 *
 * <p><b>探针字段归属（PLAN）</b>：探针字段的唯一持有者是 {@code EntityPhysicsCollideMixin}
 * （implements {@link PhysicalEntityColliderAccess}）；本 mixin 经接口 getter/setter 读写，<b>只读不持有</b>。</p>
 */
@Mixin(Player.class)
public abstract class EntityPhysicsProbeMixin {

    /** 载速叠加：travelVector ← travelVector + probe.velocity()/20（照 space$addProbeVelocity）。 */
    @ModifyVariable(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), argsOnly = true)
    private Vec3 space$injectProbeVelocity(Vec3 travelVector) {
        Player self = (Player) (Object) this;
        if (!(self instanceof LocalPlayer player)) {
            return travelVector;
        }
        if (player.getAbilities().flying || player.isPassenger()) {
            return travelVector; // 飞行走 SpaceFlightMixin；骑乘不叠载速
        }
        PhysicalEntityProbe probe = space$syncProbe(player);
        if (probe == null) {
            return travelVector;
        }
        Vector3d measured = probe.velocity();
        return new Vec3(travelVector.x + measured.x / 20.0,
                travelVector.y + measured.y / 20.0,
                travelVector.z + measured.z / 20.0);
    }

    /** 建/绑/同步探针（照 space$syncProbe）。 */
    @Unique
    private PhysicalEntityProbe space$syncProbe(LocalPlayer player) {
        ClientPhysicalWorld physicalWorld = ClientPhysicalWorld.getPhysicalWorld();
        if (physicalWorld == null) {
            return null;
        }
        ResourceLocation worldLevel = physicalWorld.getLevel();
        if (worldLevel == null || !worldLevel.equals(player.level().dimension().location())) {
            return null;
        }
        PhysicalEntityColliderAccess access = (PhysicalEntityColliderAccess) (Object) this;
        PhysicalEntityCollider collider = access.space$getPhysicalEntityCollider();
        PhysicalEntityProbe probe = access.space$getPhysicalEntityProbe();
        if (probe == null || probe.shape() != collider) {
            if (probe != null) {
                probe.close();
            }
            probe = new PhysicalEntityProbe(collider);
            access.space$setPhysicalEntityProbe(probe);
        }
        probe.bind(physicalWorld);
        probe.sync(player);
        return probe;
    }
}
