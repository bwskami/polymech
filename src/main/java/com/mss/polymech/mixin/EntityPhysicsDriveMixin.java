package com.mss.polymech.mixin;

import com.mss.polymech.physics.PhysicsClientHooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物理接管的移动入口 —— 逐字照 space 0.1.3 MPS 的
 * {@code MixinEntity.space$moveWithRapier}：在 {@code Entity.move} 的 <b>HEAD 直接取消</b>
 * 原版移动，把「<b>碰撞前</b>」的位移交给物理世界，位置从刚体读回、{@code onGround} 由射线判定。
 *
 * <p><b>为什么必须是 HEAD 取消，而不是在 setPos 处重定向</b>（我们之前的做法）：
 * 重定向只换掉最后那次 {@code setPos}，原版 {@code collide()} 仍然完整跑了一遍 ——
 * 于是<b>同一个 tick 里原版碰撞和 Rapier 各改一次玩家位置</b>：原版先把人夹到方块表面，
 * Rapier 再把人摆回刚体位置（那里可能正嵌在方块里），下一 tick 原版又把它挤出来……
 * 两套权威互相打架，解算器每次都按"深度穿透"发一次大修正 —— 表现就是"被撞飞"。
 *
 * <p>space 只有一套权威：HEAD 一取消，玩家在这个世界里的碰撞<b>只</b>由 Rapier 判定。
 * 这也是 {@code drive} 能拿到"碰撞前位移"（从而 {@code own} 带着真实推进意图）的前提。
 *
 * <p>客户端专属实现经 {@link PhysicsClientHooks} 转发（默认 no-op），专用服务端不加载客户端类；
 * 服务端<b>不</b>取消原版移动 —— space 的服务端玩家同样没有刚体，位置以客户端上报为准
 *（放行由 {@code ServerPacketClampMixin} 的恒等夹持保证，与 space 的
 * {@code MixinServerGamePacketListenerImpl} 一致）。
 */
@Mixin(Entity.class)
public abstract class EntityPhysicsDriveMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/DriveIn");

    /**
     * 进入 {@code Entity.move} 的位移超过这个长度（格/tick）就打一行 —— 0.5 格/tick = 10 m/s。
     *
     * <p><b>为什么要它</b>：{@code own = movement × 20}，所以"速度链被灌进一个巨大的 {@code own}"
     * 这类失控（第 22 节的 +0.08 递推、以及太空里那个 −4.88 格/tick 的锯齿）根子都在
     * <b>这一层的入参</b>上。这行日志回答三个此前只能靠猜的问题：</p>
     * <ol>
     *   <li>{@code type} —— <b>是谁</b>在调 {@code Entity.move}（{@code SELF} = 原版 travel；
     *       若是 {@code PISTON}/{@code SHULKER_BOX}/其它，那就是别的东西在推玩家）；</li>
     *   <li>{@code movement} vs {@code delta} —— 入参是否等于原版 {@code deltaMovement}
     *       （不等 ⇒ 有第三方在传自定义向量）；</li>
     *   <li>{@code gravity/onGround/fallFlying/flying} —— 当时这些开关的真实取值。</li>
     * </ol>
     */
    private static final double DRIVE_PROBE_LEN_SQR = 0.25;

    /**
     * 是否打"物理驱动入参"探针。
     *
     * <p><b>默认关</b>：这个探针是当年排查"被弹飞"时加的（记录 type / movement vs delta /
     * gravity / onGround），弹飞已在第 22 节定论修掉，它就成了纯噪音 ——
     * 实测 50 秒的会话刷了 <b>591 行</b>，而且只在<b>移动</b>时触发，
     * 客户端线程上每秒十几行字符串格式化 + 同步日志 I/O。
     * 需要重新取证时把它置 true 即可，不必重新写一遍。</p>
     */
    private static final boolean DRIVE_PROBE = false;

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void polymech$moveWithRapier(MoverType type, Vec3 movement, CallbackInfo ci) {
        if (DRIVE_PROBE && movement != null && movement.lengthSqr() > DRIVE_PROBE_LEN_SQR
                && (Object) this instanceof LocalPlayer player) {
            Vec3 delta = player.getDeltaMovement();
            LOGGER.info("[物理驱动入参] type={} movement=({},{},{}) delta=({},{},{}) gravity={} onGround={} "
                            + "fallFlying={} flying={} passenger={} tick={}",
                    type, f(movement.x), f(movement.y), f(movement.z),
                    f(delta.x), f(delta.y), f(delta.z),
                    f(player.getGravity()), player.onGround(), player.isFallFlying(),
                    player.getAbilities().flying, player.isPassenger(), player.tickCount);
        }
        if (PhysicsClientHooks.tryDrive((Entity) (Object) this, movement)) {
            ci.cancel();
        }
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }
}
