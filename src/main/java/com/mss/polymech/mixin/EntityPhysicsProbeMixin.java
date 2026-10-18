package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.mps.physical.physical_world.ClientPhysicalWorld;
import com.mss.polymech.physics.PhysicalEntityCollider;
import com.mss.polymech.physics.PhysicalEntityColliderAccess;
import com.mss.polymech.physics.PhysicalEntityProbe;
import com.mss.polymech.space.SpaceMovementGate;
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
 *
 * <h2>★ 门（用户 2026-10-12：主世界移动必须与原版一致，太空那套只在太空生效）</h2>
 * <ol>
 *   <li><b>原版维度（主世界 / 下界 / 末地）：载速探针一律不跑</b>（第一个判据，一个字节都不碰）；</li>
 *   <li><b>本维度没有物理体 ⇒ 也不跑</b>：探针的立足点就是"有物理体才建"的客户端地形
 *       （{@code ClientPhysicalWorld.terrainNeeded()}）。没有地面时它会每 5 个子步攒出一整段
 *       自由落体，而 {@code PhysicalEntityProbe.onPhysicsStep} 的"已着地就不扣重力"分支
 *       会把它原样当成载速回灌进 {@code travel} —— 那就是主世界走路"顿一下"的来源。</li>
 * </ol>
 * <p>两条门都在 {@link SpaceMovementGate} 里有计数器与 PASS/FAIL 自检。</p>
 */
@Mixin(Player.class)
public abstract class EntityPhysicsProbeMixin {

    /** 载速叠加：travelVector ← travelVector + probe.velocity()/20（照 space$addProbeVelocity）。 */
    @ModifyVariable(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), argsOnly = true)
    private Vec3 space$injectProbeVelocity(Vec3 travelVector) {
        Player self = (Player) (Object) this;
        // ★ 门 1：原版维度（主世界/下界/末地）—— 载速探针是纯太空功能，这里一个字节都不碰。
        //   （放在 instanceof LocalPlayer 之前，顺带让专用服务端不会去解析客户端类。）
        if (PlanetDimensions.isVanillaDimension(self.level())) {
            SpaceMovementGate.noteProbeBlocked(self.level());
            return travelVector;
        }
        if (!(self instanceof LocalPlayer player)) {
            return travelVector;
        }
        if (player.getAbilities().flying || player.isPassenger()) {
            return travelVector; // 飞行走 SpaceFlightMixin；骑乘不叠载速
        }
        PhysicalEntityProbe probe = space$syncProbe(player);
        if (probe == null) {
            SpaceMovementGate.noteProbeBlocked(self.level()); // 门 2：本维度没有物理体（或没有对应物理世界）
            return travelVector;
        }
        SpaceMovementGate.noteProbeActive(self.level());
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
        // ★ 门 2：本维度没有任何物理体 ⇒ 客户端地形不会体素化（同一判据），探针脚下是空的。
        //   载速只对"被移动的物理体带着走"有意义，一个体都没有时测出来的必然是自由落体。
        if (!physicalWorld.hasPhysicalBodies()) {
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
