package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpaceFlight;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>太空创造飞行的竖直</b>：把 {@code Player.travel} 的飞行分支整条换成"视线基底"版本。
 *
 * <p>这是 {@code docs/space-input-basis.md} §三里那条"未修"的待办。原来那份文件把根因
 * 判在 {@code Player.travel} 的 {@code d2 * 0.6} 上、并猜竖直输入来自
 * {@code LivingEntity.yya} 或 {@code travel} 的 y 分量；<b>两个候选都是死路</b>
 * —— 1.21.1 的 {@code yya} 全仓库<b>只被读、从没被写</b>（永远是 0），
 * 所以 {@code travel} 入参的 y 分量恒为 0。竖直真正的来源是
 * {@code LocalPlayer.aiStep} 里那一句沿<b>世界 +Y</b> 的 {@code setDeltaMovement(delta.add(0, j·flySpeed·3, 0))}。
 * 证据与推导见 {@link SpaceFlight} 的类注释与那份文档的 §3.5。</p>
 *
 * <h2>为什么是"HEAD 取消整条分支"而不是"在 TAIL 上补一下"</h2>
 * 原版把阻尼做在<b>世界轴</b>上（{@code (x,z)×0.91}、{@code y×0.6}），而输入意图是
 * <b>视线轴</b>的；两者不同基 ⇒ 稳态速度方向必然偏。要让方向正确，阻尼必须与冲量同基，
 * 而"同基阻尼"在原版的结构里表达不出来（它把 {@code ×0.91} 和 {@code ×0.6} 分别绑在
 * world x/z 与 world y 上）。所以只能把这一段拿过来自己做 —— 这也正是参考
 * space 0.3.0 的 {@code MixinPlayerFlight}（{@code @Inject(travel, HEAD, cancellable)}）的做法。</p>
 *
 * <p>不是"第四条路"：本 mixin 一取消，飞行这一 tick 就<b>只有</b>这一条速度来源，
 * 而且它与走路那条（{@link SpaceInputRotationMixin} 的 {@code moveRelative}）读<b>同一个</b>
 * {@link SpacePlayerData#viewFrame}。分叉本身（飞行 vs 走路）被
 * {@link SpacePlayerData#markMovementBasis} 记进状态并出现在 {@code [姿态自检]} 里。</p>
 *
 * <h2>与物理层的分工</h2>
 * 飞行时 {@code ClientPhysics.drive()} 本来就返回 {@code false}（"创造飞行：位置交回原版"），
 * 所以这里调 {@code self.move(...)} 走的就是<b>原版</b> {@code Entity.move}（含碰撞夹取），
 * 与 space 0.3.0 的假设一致；刚体只是在 {@code afterPlayerTick} 里跟过来。
 * 换句话说：<b>本改动不碰物理接管那条链。</b></p>
 *
 * <h2>原版副作用的保全</h2>
 * 取消 {@code Player.travel} 会跳过原版飞行分支里的三件事，这里逐条补回，
 * 免得"修好了方向、丢了别的"：
 * <ul>
 *   <li>{@code resetFallDistance()}（原飞行分支）；</li>
 *   <li>清鞘翅滑翔标记 flag7（原飞行分支的 {@code setSharedFlag(7, false)}；这里走 public 的
 *       {@code stopFallFlying()} —— 原因见方法里那段注释。<b>不能省</b>：
 *       {@code LivingEntity.updateFallFlying()} 只在 {@code !level.isClientSide} 时才写 flag7，
 *       所以<b>客户端</b>的 flag7 平时就靠飞行分支这一行来清）；</li>
 *   <li>{@code calculateEntityAnimation(false)}（原在 {@code LivingEntity.travel} 末尾，
 *       不补的话飞行时手脚不再摆动 —— 原版飞行是摆的）。</li>
 * </ul>
 */
@Mixin(Player.class)
public abstract class SpaceFlightMixin {

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void polymech$cameraRelativeFlight(Vec3 travelVector, CallbackInfo ci) {
        Player self = (Player) (Object) this;
        if (!(self instanceof LocalPlayer player)) {
            return;
        }
        // 与原版 Player.travel 飞行分支同一个条件。
        if (!self.getAbilities().flying || self.isPassenger()) {
            return;
        }
        if (!self.level().dimension().equals(PlanetDimensions.SPACE)) {
            return;
        }
        SpacePlayerData data = SpacePlayerData.get(self);
        if (data == null || !data.isInitialized()) {
            // 还没初始化：退回原版（世界 +Y）。这时的表现与改动前一致，不会更坏。
            return;
        }

        Quaternionf frame = data.viewFrame(new Quaternionf());

        // 竖直输入与水平输入同源：都从 input 读，不再有"世界 Y 那一份"。
        // ascend 的符号与原版 aiStep 的 j 一致（跳跃 +1 / 潜行 -1）。
        float ascend = (player.input.jumping ? 1.0F : 0.0F) - (player.input.shiftKeyDown ? 1.0F : 0.0F);
        float baseFlySpeed = self.getAbilities().getFlyingSpeed();

        // ── 1) 撤掉原版 aiStep 已经加进来的"世界 +Y"竖直冲量 ──
        //    它只在"相机实体就是自己"时加（LocalPlayer.aiStep: isControlledCamera），
        //    所以这里用同一个判据，避免在旁观别人时凭空减掉一份不存在的量。
        Vec3 delta = self.getDeltaMovement();
        if (Minecraft.getInstance().getCameraEntity() == player) {
            delta = delta.add(0.0, -SpaceFlight.cancelVanillaVertical(ascend, baseFlySpeed), 0.0);
        }

        // ── 2) 加本 tick 的视线基底冲量（水平 + 竖直同一个基）──
        //    水平用 self.xxa/zza：它们在 LivingEntity.aiStep 里已经乘过 0.98，
        //    与 travel 入参的分量逐位相同（那个入参的 y 分量恒为 0，故不读它）。
        Vector3d impulse = SpaceFlight.impulse(frame, self.xxa, self.zza, ascend,
                baseFlySpeed, self.isSprinting(), new Vector3d());
        delta = delta.add(impulse.x, impulse.y, impulse.z);
        self.setDeltaMovement(delta);

        // ── 3) 移动（原版 Entity.move：碰撞夹取照旧；飞行时物理层不接管）──
        self.move(MoverType.SELF, delta);

        // ── 4) 沿视线基底逐轴阻尼（水平 0.91 / 竖直 0.6）──
        //    这一步替代原版"世界 x/z ×0.91 + y ×0.6（且 y 被 Player.travel 覆盖）"，
        //    也是"方向正确"的关键：阻尼与冲量同基 ⇒ 稳态速度方向 = 输入方向。
        Vec3 moved = self.getDeltaMovement();
        Vector3d damped = SpaceFlight.damp(frame, moved.x, moved.y, moved.z, new Vector3d());
        self.setDeltaMovement(damped.x, damped.y, damped.z);

        // ── 5) 补回被取消掉的原版副作用（见类注释）──
        self.resetFallDistance();
        // 原版飞行分支这里是 `this.setSharedFlag(7, false)`。但 setSharedFlag 是 Entity 的
        // protected 方法，本 mixin 的编译期类型是 Player（不是 Player 的子类）⇒ 直接调不过编译；
        // 而 @Shadow 只认"目标类自身声明"的成员（本项目在 MixinItemEntity 上踩过，
        // 见那个类的注释），声明在 Entity 上的方法 shadow 不到。
        // Player.stopFallFlying() 是 public 的，净效果就是 flag7 = false（true 紧接着被 false 覆盖，
        // 中间没有观察者），与原版那一行等价。
        self.stopFallFlying();
        self.calculateEntityAnimation(false);

        // 这一 tick 的移动基底 = 视线基底（进 [姿态自检]，别让"走了哪条路"只能靠读代码）。
        data.markMovementBasis(true);
        ci.cancel();
    }
}
