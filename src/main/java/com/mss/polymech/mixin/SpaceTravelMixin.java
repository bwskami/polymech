package com.mss.polymech.mixin;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 太空维度：6DOF 飞行 + tick 尾部保存旧旋转。
 *
 * <p>aiStep() HEAD → 施加6DOF速度。
 * tick() TAIL → rotationO 跟随 rotation（和 space mod 的 setOldPosAndRot 一致）。</p>
 */
@Mixin(LocalPlayer.class)
public abstract class SpaceTravelMixin {

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void polymech$spaceFlight(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        if (!player.level().dimension().equals(PlanetDimensions.SPACE)) return;

        // ── Z/C 主动滚转（6DOF 的 roll 输入；鼠标永不改变 roll）──
        polymech$handleRollKeys(player);

        Input input = player.input;
        double forwardInput = input.up ? 1 : input.down ? -1 : 0;
        double strafeInput  = input.left ? 1 : input.right ? -1 : 0;
        // 6DOF：Space 沿当前屏幕 up，Shift 沿反方向；W/S 完全沿视线。
        double verticalInput = input.jumping ? 1 : input.shiftKeyDown ? -1 : 0;

        double baseSpeed = 0.2;
        if (player.isSprinting()) baseSpeed *= 1.3;

        SpacePlayerData data = SpacePlayerData.get(player);
        if (!data.isInitialized()) {
            data.initFromVanilla(player.getYRot(), player.getXRot());
        }
        Vector3d facing = data.facing();
        // SpacePlayerData 里的 left 是按 MC 镜像约定存的：实际屏幕左方向是 -left。
        Vector3d screenLeft = new Vector3d(data.left()).negate();
        // 屏幕 up = facing × screenLeft；roll 后它会自动跟着视角倾斜。
        Vector3d screenUp = new Vector3d(facing).cross(screenLeft, new Vector3d()).normalize();

        // 把 W/S、A/D、Space/Shift 合成为当前 6DOF 基底里的一个方向，再归一化，
        // 避免同时按 W+A+Space 时速度变成 sqrt(3) 倍。
        double inputLen = Math.sqrt(forwardInput * forwardInput
                + strafeInput * strafeInput
                + verticalInput * verticalInput);
        if (inputLen > 1e-6) {
            forwardInput /= inputLen;
            strafeInput /= inputLen;
            verticalInput /= inputLen;
        }

        double vx = (facing.x * forwardInput
                + screenLeft.x * strafeInput
                + screenUp.x * verticalInput) * baseSpeed;
        double vy = (facing.y * forwardInput
                + screenLeft.y * strafeInput
                + screenUp.y * verticalInput) * baseSpeed;
        double vz = (facing.z * forwardInput
                + screenLeft.z * strafeInput
                + screenUp.z * verticalInput) * baseSpeed;

        Vec3 velocity = new Vec3(vx, vy, vz);
        double speed = velocity.length();
        if (speed > 0.5) velocity = velocity.scale(0.5 / speed);

        if (speed > 1e-6) {
            player.move(MoverType.SELF, velocity);
        }
    }

    /** 每tick滚转角速度（度/tick，60°/s） */
    private static final float ROLL_DEG_PER_TICK = 3.0f;

    /** Z/C 滚转：绕 facing 旋转 left（facing 不动），只改 roll。 */
    private void polymech$handleRollKeys(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // 聊天/GUI 打开时不滚转
        long window = mc.getWindow().getWindow();
        float roll = 0;
        if (InputConstants.isKeyDown(window, InputConstants.KEY_Z)) roll -= ROLL_DEG_PER_TICK;
        if (InputConstants.isKeyDown(window, InputConstants.KEY_C)) roll += ROLL_DEG_PER_TICK;
        if (roll == 0) return;

        SpacePlayerData data = SpacePlayerData.get(player);
        if (!data.isInitialized()) return;
        Vector3d facing = data.facing();
        Vector3d left = data.left();
        left.rotateAxis(roll * Math.PI / 180.0, facing.x, facing.y, facing.z);
        data.orthonormalize();
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
     * tick 尾部：rotationO = rotation（和 space mod 的 setOldPosAndRot 一致）；
     * 并检测传送/切维度，用服务器下发的 vanilla 角度重建 6DOF 朝向。
     *
     * <p>attachment 跨维度复制的是"传送前"的向量状态，且服务端的
     * initFromVanilla 只作用于服务端实体——不重建的话客户端相机在传送后
     * 仍指向传送前方向（视角跳变），且 facingO/facing 可能近反向，
     * 插值退化后相机架翻转（鼠标反向）。</p>
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void polymech$spaceSaveOldRotation(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        boolean inSpace = player.level().dimension().equals(PlanetDimensions.SPACE);
        if (inSpace) {
            com.mss.polymech.space.SpacePlayerData.get(player).saveOld();
        }

        // 传送检测：维度切换，或单 tick 位移 > 1000 格（太空飞行上限 0.5 格/tick）
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
            var data = com.mss.polymech.space.SpacePlayerData.get(player);
            data.initFromVanilla(player.getYRot(), player.getXRot());
            data.saveOld(); // facingO = facing，插值不扫过
        }

        polymech$lastX = player.getX();
        polymech$lastY = player.getY();
        polymech$lastZ = player.getZ();
        polymech$lastDim = player.level().dimension();
    }
}
