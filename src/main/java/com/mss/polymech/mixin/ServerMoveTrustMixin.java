package com.mss.polymech.mixin;

import com.mss.polymech.physics.ServerPlayerPhysics;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 物理驱动的玩家：服务端<b>采纳客户端上报位置</b>，而不是把它拉回去。
 *
 * <p><b>为什么需要它</b>：原版 {@code ServerGamePacketListenerImpl.handleMovePlayer} 会</p>
 * <pre>
 * double d3/d4/d5 = 玩家当前位置;            // 上一 tick 的权威位置
 * this.player.move(MoverType.PLAYER, 上报位移);   // ← 我们的物理接管点（Entity.move 被 redirect）
 * d10 = (上报位置 − 物理后位置)²;
 * if (d10 &gt; 0.0625 &amp;&amp; !creative &amp;&amp; !spectator) flag2 = true;        // "moved wrongly"
 * if (noPhysics || sleeping || ((!flag2 || 有碰撞) &amp;&amp; !isPlayerCollidingWithAnythingNew(...)))
 *     player.absMoveTo(上报位置);            // 采纳
 * else
 *     this.teleport(d3, d4, d5, ...);        // ← 拉回上一 tick = 玩家看到的"回弹"
 * </pre>
 *
 * <p>物理算出来的位置原版校验不了：位置可能和上报差 &gt; 0.25 格（惯性模型下两端各积分各的），
 * 碰撞由 Rapier 而不是原版体素判定，于是<b>一撞到东西就命中 else 分支被拉回</b> ——
 * 这就是"撞到物理体人物回弹"。MPS/space 自己也必须让两端物理同步才敢用原版校验，
 * 而我们的做法更直接：物理玩家在移动这一环上是客户端权威的
 * （{@code PhysicsEntityManager} 的注释里也早就写明"要接管玩家必须改造这一环"）。</p>
 *
 * <p>做法：只在<b>本 tick 确实被物理接管</b>（{@link ServerPlayerPhysics#isDriving}）时，
 * 把 {@code teleport} 的目标从"上一 tick 位置"换成"客户端上报位置"，
 * 并把服务端刚体对齐过去（{@link ServerPlayerPhysics#snapTo}）。
 * 不是物理接管的场合（创造/旁观飞行、物理未就绪、其它维度）完全保持原版行为。</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMoveTrustMixin {

    @Shadow
    public ServerPlayer player;

    @Unique
    private double polymech$reportedX;
    @Unique
    private double polymech$reportedY;
    @Unique
    private double polymech$reportedZ;
    @Unique
    private float polymech$reportedYRot;
    @Unique
    private float polymech$reportedXRot;
    @Unique
    private boolean polymech$hasReported;

    /** 记下本包上报的位置（原版在方法体里算完就丢了，我们需要它来"采纳"）。 */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void polymech$captureReported(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (this.player == null) {
            polymech$hasReported = false;
            return;
        }
        // clampHorizontal/clampVertical 已被 ServerPacketClampMixin 改成恒等，这里直接用上报值
        polymech$reportedX = packet.getX(this.player.getX());
        polymech$reportedY = packet.getY(this.player.getY());
        polymech$reportedZ = packet.getZ(this.player.getZ());
        polymech$reportedYRot = net.minecraft.util.Mth.wrapDegrees(packet.getYRot(this.player.getYRot()));
        polymech$reportedXRot = net.minecraft.util.Mth.wrapDegrees(packet.getXRot(this.player.getXRot()));
        // Rot-only 包没有位置分量：getX/Y/Z(default) 会返回实体当前位置，
        // 与 vanilla 回滚目标 (d3,d4,d5) 等价，因此这里不需要区分包类型。
        polymech$hasReported = true;
    }

    /**
     * 原版两处 rejection（"moved too quickly" 与 "moved wrongly"）最后都落到
     * {@code teleport(DDDFF)}。物理接管时改成传到上报位置（= 采纳客户端），否则原样调用。
     */
    @Redirect(
            method = "handleMovePlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;teleport(DDDFF)V")
    )
    private void polymech$trustClientPosition(ServerGamePacketListenerImpl self,
            double x, double y, double z, float yRot, float xRot) {
        if (polymech$hasReported && self.player != null
                && ServerPlayerPhysics.isDriving(self.player)) {
            self.teleport(polymech$reportedX, polymech$reportedY, polymech$reportedZ,
                    polymech$reportedYRot, polymech$reportedXRot);
            ServerPlayerPhysics.snapTo(self.player);
            return;
        }
        self.teleport(x, y, z, yRot, xRot);
    }
}
