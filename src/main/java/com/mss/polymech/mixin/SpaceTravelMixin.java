package com.mss.polymech.mixin;

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
 * <p>本类只做三件事：</p>
 * <ol>
 *   <li>Z/C 主动滚转（鼠标永不产生 roll）；</li>
 *   <li>tick 尾部保存旧朝向（渲染插值用）；</li>
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

    /** Z/C 滚转：绕 facing 旋转 left（facing 不动），只改 roll。 */
    @Inject(method = "tick", at = @At("HEAD"))
    private void polymech$handleRollKeys(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!player.level().dimension().equals(PlanetDimensions.SPACE)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // 聊天/GUI 打开时不滚转
        long window = mc.getWindow().getWindow();
        float roll = 0;
        if (InputConstants.isKeyDown(window, InputConstants.KEY_Z)) roll -= ROLL_DEG_PER_TICK;
        if (InputConstants.isKeyDown(window, InputConstants.KEY_C)) roll += ROLL_DEG_PER_TICK;
        if (roll == 0) return;

        SpacePlayerData data = SpacePlayerData.get(player);
        if (!data.isInitialized()) return;
        // 滚转作用在"身体"上（space 0.0.6 的 relRotation.rotateZ(roll)），
        // 头挂在身体坐标系里，重算后视线跟着一起滚。
        data.rollBody(roll * Math.PI / 180.0);
        data.rebuildHeadFromBody();
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
                                (float) data.headYaw(), (float) data.headPitch()));
            }
        }

        polymech$lastX = player.getX();
        polymech$lastY = player.getY();
        polymech$lastZ = player.getZ();
        polymech$lastDim = player.level().dimension();
    }
}
