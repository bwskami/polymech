package com.mss.polymech.mixin;

import com.mss.polymech.client.space.LevelAssist;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空 6DOF：<b>只负责朝向</b>，不参与移动。
 *
 * <p>移动的"往哪走"由 {@link SpaceInputRotationMixin} 在 {@code Entity.moveRelative} 里
 * 把原版输入旋转到 6DOF 基底；"走多少 / 怎么停"由原版 {@code travel} 负责；
 * "最终位置"由物理刚体积分后回写（{@code EntityPhysicsDriveMixin}）。</p>
 *
 * <p>本类做四件事：</p>
 * <ol>
 *   <li>Z/C 主动滚转、<b>X 手动回正</b>（鼠标永不产生 roll）、<b>V 切换深空飞行辅助</b>；</li>
 *   <li>舒适层：调用 {@link LevelAssist} 做<b>滚转回正</b>（自动只在有重力/邻近物理体时生效，
 *       深空默认开柔和辅助、可 V 关掉；手动按一下 X 才用原版上下回正，且就近侧别）；</li>
 *   <li>tick 尾部保存旧朝向 + 推进第一人称手的滞后平滑（渲染插值用）；</li>
 *   <li>传送/切维度检测（用服务器下发的 vanilla 角度重建朝向）+ 朝向同步广播。</li>
 * </ol>
 *
 * <p><b>历史坑（别再走回去）</b>：早先这里在 {@code aiStep} 里自建 6DOF 速度再调
 * {@code player.move(MoverType.SELF, velocity)}。那条路上原版 {@code travel} 仍然会跑一遍，
 * 于是同一 tick 出现两次位移来源；再叠上物理层的速度伺服，惯性被整条抹掉 —— 表现为
 * "移动一眼就看出来不对"。现在方向只在一个地方（moveRelative）被改写，不存在二次来源。</p>
 */
@Mixin(LocalPlayer.class)
public abstract class SpaceTravelMixin {

    /** 每 tick 滚转角速度（度/tick，60°/s）。 */
    private static final float ROLL_DEG_PER_TICK = 3.0f;

    /** 本 tick 玩家是否按着 Z/C 主动滚 —— 自动回正（LevelAssist）据此让路，不跟玩家抢。 */
    @Unique
    private boolean polymech$rollKeyActive;

    /** 手动回正键（X）上一帧是否按下 —— 用来做"按一下"的边沿检测（不是长按）。 */
    @Unique
    private boolean polymech$manualKeyWasDown;

    /** 深空飞行辅助切换键（V）上一帧是否按下 —— 边沿检测用。 */
    @Unique
    private boolean polymech$faKeyWasDown;

    /** Z/C 滚转（按住生效）+ X 手动回正（按一下触发一次自动跑完）+ V 切换深空飞行辅助。 */
    @Inject(method = "tick", at = @At("HEAD"))
    private void polymech$handleRollKeys(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        polymech$rollKeyActive = false;
        if (!player.level().dimension().equals(PlanetDimensions.SPACE)) {
            polymech$manualKeyWasDown = false;
            polymech$faKeyWasDown = false;
            LevelAssist.setPlayerRollInput(0.0);
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            polymech$manualKeyWasDown = false; // GUI 打开时不响应，也不留边沿状态
            polymech$faKeyWasDown = false;
            LevelAssist.setPlayerRollInput(0.0);
            return;
        }
        long window = mc.getWindow().getWindow();

        // X：**按一下**触发一次手动回正（边沿检测），之后由 LevelAssist 自己每 tick 推进到收敛
        boolean manualDown = InputConstants.isKeyDown(window, InputConstants.KEY_X);
        if (manualDown && !polymech$manualKeyWasDown) {
            LevelAssist.requestManualLevel();
        }
        polymech$manualKeyWasDown = manualDown;

        // V：切换深空飞行辅助（边沿检测；默认开，见 LevelAssist#toggleFlightAssist）
        boolean faDown = InputConstants.isKeyDown(window, InputConstants.KEY_V);
        if (faDown && !polymech$faKeyWasDown) {
            LevelAssist.toggleFlightAssist();
        }
        polymech$faKeyWasDown = faDown;

        float roll = 0;
        if (InputConstants.isKeyDown(window, InputConstants.KEY_Z)) roll -= ROLL_DEG_PER_TICK;
        if (InputConstants.isKeyDown(window, InputConstants.KEY_C)) roll += ROLL_DEG_PER_TICK;

        // Z/C：这里**只记录**本 tick 的滚转输入，不在这里应用。
        // 应用点在同 tick 的 LevelAssist.tick()（在 saveOld() 之后）—— 与自动回正、手动回正
        // 共用同一条后处理路径，这样相机的 partialTick 插值才能把这一 tick 的滚转量摊到每一帧上。
        // 原先在这里直接 rollBody()，发生在 saveOld() 之前：O 存的已经是滚转后的值，插值拿不到
        // 变化，于是每 tick 硬跳一个 3° 台阶（20Hz）—— 这就是 Z/C 不如 X 回正丝滑的根因。
        LevelAssist.setPlayerRollInput(roll);
        if (roll == 0) return;
        polymech$rollKeyActive = true;
    }

    /**
     * "挤过去"：正在用力推、而且被挡住（原版报水平碰撞）时，把颈部领先量收回来 ——
     * 身体立刻对齐视线，正对洞口。
     *
     * <p>为什么需要：身体领先视线时是斜的，而原版 AABB 是"旋转身体盒的外包盒"，
     * 斜着时横截面会超过一格，于是玩家想钻一格大小的洞怎么推都进不去。
     * 身体对齐视线后横截面回到 0.6×0.6，就能挤进去。视线本身不动。</p>
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void polymech$squeezeAssist(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!player.level().dimension().equals(PlanetDimensions.SPACE)) return;
        if (!player.horizontalCollision && !player.verticalCollision) return;
        if (Math.abs(player.xxa) < 0.01f && Math.abs(player.zza) < 0.01f
                && Math.abs(player.yya) < 0.01f) {
            return; // 没在推就不管，保持颈部自由
        }
        SpacePlayerData data = SpacePlayerData.get(player);
        if (!data.isInitialized()) return;
        data.relaxLead(0.5); // 每 tick 减半，约 0.25s 内身体完全对齐视线
    }

    // ── 传送/切维度检测状态（客户端实体；respawn 后新实例自然归零）──
    @Unique
    private double polymech$lastX = Double.NaN;
    @Unique
    private double polymech$lastY = Double.NaN;
    @Unique
    private double polymech$lastZ = Double.NaN;
    @Unique
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> polymech$lastDim;

    /**
     * tick 尾部：保存旧朝向；并检测传送/切维度，用服务器下发的 vanilla 角度重建 6DOF 朝向。
     *
     * <p>attachment 跨维度复制的是"传送前"的向量状态，且服务端的 initFromVanilla
     * 只作用于服务端实体 —— 不重建的话客户端相机在传送后仍指向传送前方向（视角跳变），
     * 且 facingO/facing 可能近反向，插值退化后相机架翻转（鼠标反向）。</p>
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void polymech$spaceTickTail(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        boolean inSpace = player.level().dimension().equals(PlanetDimensions.SPACE);
        if (inSpace) {
            SpacePlayerData data = SpacePlayerData.get(player);
            data.saveOld();
            // 舒适层：滚转回正。
            //   · 自动：只在"有重力 / 邻近物理体"时生效；深空不回正（太空的"上"由玩家自己定）。
            //   · 手动：**按一下 X** 触发一次，之后自动跑完（深空以原版上下为准，就近侧别）。
            // **必须在 saveOld() 之后**：这样 O 是回正前、current 是回正后，
            // 相机/模型的 partialTick 插值才能把本 tick 的回正量摊到每一帧 ——
            // 放在之前的话 O 存的就是回正后的值，插值拿不到变化，表现就是 20Hz 一顿一顿。
            LevelAssist.tick(player, data, polymech$rollKeyActive);
            // 第一人称手的滞后平滑（等价 vanilla xBob/yBob 的 50%/tick，
            // 但走 SpacePlayerData 的**连续角**分支，过极点不跳）。
            // 见 SpaceFirstPersonHandMixin 与 SpacePlayerData#advanceHandAngles。
            data.advanceHandAngles();
            // 身体姿态每 tick 都可能变，而原版 AABB 只在 setPos 时重算 ——
            // 站着不动/悬停飞行时它就会停在旧姿态上（"碰撞箱要动一下才刷新"）。
            // 这里坐标不变，只是逼它重算一次盒子。
            player.setPos(player.getX(), player.getY(), player.getZ());
        }

        // 传送检测：维度切换，或单 tick 位移 > 1000 格
        boolean dimChanged = polymech$lastDim == null
                || !player.level().dimension().equals(polymech$lastDim);
        boolean jumped = !Double.isNaN(polymech$lastX);
        if (jumped) {
            double dx = player.getX() - polymech$lastX;
            double dy = player.getY() - polymech$lastY;
            double dz = player.getZ() - polymech$lastZ;
            jumped = (dx * dx + dy * dy + dz * dz) > 1_000_000.0;
        }
        if (inSpace && (dimChanged || jumped)) {
            SpacePlayerData data = SpacePlayerData.get(player);
            data.initFromVanilla(player.getYRot(), player.getXRot());
            data.saveOld(); // facingO = facing，插值不扫过
        }

        // 朝向同步：太空自由旋转必须发给服务端，否则别的玩家看到的你永远是 vanilla 朝向
        // （服务端会转播给其他客户端，见 SpaceRotationPayload.handle）
        if (inSpace && player.tickCount % 2 == 0) {
            SpacePlayerData data = SpacePlayerData.get(player);
            if (data.isInitialized()) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        com.mss.polymech.network.SpaceRotationPayload.clientToServer(
                                data.bodyFacing(), data.bodyLeft(),
                                data.facing(), data.left()));
            }
        }

        polymech$lastX = player.getX();
        polymech$lastY = player.getY();
        polymech$lastZ = player.getZ();
        polymech$lastDim = player.level().dimension();
    }
}
