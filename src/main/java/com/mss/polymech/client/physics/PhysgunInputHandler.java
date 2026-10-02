package com.mss.polymech.client.physics;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.PhysgunItem;
import com.mss.polymech.network.PhysgunDragPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.lwjgl.glfw.GLFW;

/**
 * 牵引枪的<b>旋转控制输入</b>（客户端）—— 照参考 {@code PhysicsStaffClientHandler}。
 *
 * <h2>参考怎么做的（逐字取证）</h2>
 * <pre>
 *   private boolean isRotating() {
 *       return this.holdingStaff &amp;&amp; this.dragSession != null &amp;&amp; SimKeys.ROTATE_MODE.isPressed();
 *   }
 *
 *   onMouseMove(yaw, pitch):
 *       Vec3 axis = mc.player.calculateViewVector(0.0f, mc.player.getYRot() - 90.0f);   // 世界侧向轴
 *       orientation.rotateLocalY(toRadians(yaw) * rotateSensitivity);                   // 偏航绕**物体自身** Y
 *       orientation.premul(new Quaterniond(new AxisAngle4d(
 *               toRadians(-pitch) * rotateSensitivity, axis.x, axis.y, axis.z)));       // 俯仰绕**世界侧向轴**
 *
 *   onScroll(deltaY):
 *       double sensMultiplier = clamp(pow(currentDistance / 10.0, 0.5), 1.0, 5) * (sprint ? 4 : 1);
 *       dragSession.setDistance(clampDistance(currentDistance + deltaY * scrollSensitivity * sensMultiplier));
 * </pre>
 * 两条容易记错的：
 * <ol>
 *   <li><b>滚轮不是旋转，是调持握距离</b>（钳在 {@code [2.0, RANGE]}）。旋转是"按住旋转键 + 移动鼠标"。</li>
 *   <li>鼠标移动被<b>吃掉</b>（参考里 {@code Result(true)} ⇒ {@code ci.cancel()}）——
 *       也就是旋转时<b>相机被冻住</b>，鼠标只转物体。我们在
 *       {@code MouseHandlerMixin} 里用 {@code @WrapWithCondition} 包住
 *       {@code LocalPlayer.turn} 实现同样效果（返回 false = 不转视角）。</li>
 * </ol>
 *
 * <h2>为什么按 tick 发包，而不是每次鼠标事件发</h2>
 * 鼠标事件频率跟着帧率（可能几百 Hz），而服务端的角伺服本来就只有 20Hz（每 MC tick 一次冲量）。
 * 所以朝向在客户端按事件实时更新（手感跟手），包按 tick 发（20Hz，与伺服的节奏一致）。
 */
@EventBusSubscriber(modid = Polymech.MOD_ID, value = Dist.CLIENT)
public final class PhysgunInputHandler {

    /**
     * 旋转模式键（按住 + 移动鼠标 = 转物体）。对应参考的 {@code SimKeys.ROTATE_MODE}。
     *
     * <p>默认左 Alt：原版与本项目其它按键都没占它，且它天然是"按住型"修饰键。
     * 玩家可在"按键绑定 → 杂项"里改。</p>
     */
    public static final KeyMapping ROTATE_MODE_KEY = new KeyMapping(
            "key.poly_mech.physgun_rotate",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.misc"
    );

    /** 鼠标旋转灵敏度 —— 照参考的 {@code physicsStaffRotateSensitivity} 默认值。 */
    public static final double ROTATE_SENSITIVITY = 0.35;
    /** 滚轮灵敏度 —— 照参考的 {@code physicsStaffScrollSensitivity} 默认值。 */
    public static final double SCROLL_SENSITIVITY = 0.6;
    /** 持握距离下限（格）—— 照参考的 {@code clampDistance} 下限。 */
    public static final double MIN_DISTANCE = 2.0;

    private PhysgunInputHandler() {
    }

    /** 拿着牵引枪（任一只手）并且确实抓着东西。 */
    private static boolean isHoldingGrabbing(LocalPlayer player) {
        if (!(player.getMainHandItem().getItem() instanceof PhysgunItem)
                && !(player.getOffhandItem().getItem() instanceof PhysgunItem)) {
            return false;
        }
        return PhysgunClientState.isGrabbed(player.getUUID());
    }

    /** 参考的 {@code isRotating()}。 */
    public static boolean isRotating() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        return player != null && isHoldingGrabbing(player) && ROTATE_MODE_KEY.isDown();
    }

    /**
     * 鼠标移动（角度增量，单位度；pitch 已含"反转 Y 轴"的符号）。
     *
     * @return true = 已消费 ⇒ 调用方必须<b>不要</b>转视角（相机冻结）
     */
    public static boolean onMouseMove(double yawDeg, double pitchDeg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !isRotating()) {
            return false;
        }
        PhysgunClientState.Beam beam = PhysgunClientState.beam(player.getUUID());
        if (beam == null || beam.released()) {
            return false;
        }
        if (!ensureOrientation(beam)) {
            // 拿不到体的当前朝向 ⇒ 这一帧不转（下一帧再试）。
            // 不能退化成"从单位朝向起步"：那会让物体第一帧啪地跳一下。
            return true;
        }

        Quaterniond q = beam.orientation();
        // 偏航：绕**物体自身**的 Y（rotateLocalY）
        q.rotateLocalY(Math.toRadians(yawDeg) * ROTATE_SENSITIVITY);
        // 俯仰：绕**世界侧向轴**（玩家右手边），用 premul 施加在世界系
        Vec3 axis = player.calculateViewVector(0.0F, player.getYRot() - 90.0F);
        q.premul(new Quaterniond(new AxisAngle4d(
                Math.toRadians(-pitchDeg) * ROTATE_SENSITIVITY, axis.x, axis.y, axis.z)));
        q.normalize();
        beam.markOrientationDirty();
        return true;
    }

    /**
     * 滚轮：改持握距离（照参考 {@code onScroll}）。
     *
     * <p>灵敏度随距离放大（{@code √(d/10)} 钳到 {@code [1,5]}，疾跑 ×4）——
     * 远距离一格一格挪太慢，近距离又要精细，参考就是这么折中的。</p>
     */
    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || !isHoldingGrabbing(player)) {
            return;
        }
        PhysgunClientState.Beam beam = PhysgunClientState.beam(player.getUUID());
        if (beam == null || beam.released()) {
            return;
        }
        // 吃掉这次滚动：否则会顺手切快捷栏（参考也是 cancel）
        event.setCanceled(true);

        double current = beam.holdDistance();
        boolean sprint = mc.options.keySprint.isDown();
        double sensMultiplier = Math.max(1.0, Math.min(5.0, Math.sqrt(current / 10.0))) * (sprint ? 4.0 : 1.0);
        double next = current + event.getScrollDeltaY() * SCROLL_SENSITIVITY * sensMultiplier;
        beam.setHoldDistance(Math.max(MIN_DISTANCE, Math.min(PhysgunItem.RANGE, next)));
        beam.markDragDirty();
    }

    /**
     * 每客户端 tick：把"目标朝向 + 持握距离"发给服务端（20Hz，与角伺服同节奏）。
     *
     * <p>只在<b>变化过</b>或<b>还没同步过</b>时发：抓着不动就不发包。</p>
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        PhysgunClientState.Beam beam = PhysgunClientState.beam(player.getUUID());
        if (beam == null || beam.released() || beam.bodyId() <= 0L) {
            return;
        }
        if (!beam.dragDirty()) {
            return;
        }
        if (!ensureOrientation(beam)) {
            return;
        }
        beam.clearDragDirty();
        Quaterniond q = beam.orientation();
        PacketDistributor.sendToServer(new PhysgunDragPacket(
                beam.bodyId(), beam.holdDistance(),
                (float) q.x, (float) q.y, (float) q.z, (float) q.w));
    }

    /**
     * 第一次需要朝向时，从<b>镜子体的当前朝向</b>起步。
     *
     * <p>取实时位姿（{@code ClientPhysics.liveTransform}）而不是同步快照：
     * 快照有 2 tick 的滞后，抓着动的体用快照会看到"起步时歪一下"。
     * 拿不到（体刚建、还没同步）就返回 false，调用方这一帧什么都不做。</p>
     */
    private static boolean ensureOrientation(PhysgunClientState.Beam beam) {
        if (beam.hasOrientation()) {
            return true;
        }
        double[] pos = new double[3];
        float[] rot = new float[4];
        if (!ClientPhysics.liveTransform(beam.bodyId(), pos, rot)) {
            return false;
        }
        Quaterniond q = new Quaterniond(rot[0], rot[1], rot[2], rot[3]);
        if (!q.isFinite() || q.lengthSquared() < 1.0e-12) {
            return false;
        }
        beam.setOrientation(q.normalize());
        return true;
    }
}
