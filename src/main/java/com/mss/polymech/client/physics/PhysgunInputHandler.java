package com.mss.polymech.client.physics;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.PhysgunItem;
import com.mss.polymech.network.PhysgunAction;
import com.mss.polymech.network.PhysgunActionPacket;
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

import java.util.UUID;

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
     * <p><b>默认 TAB —— 照抄参考</b>：{@code SimKeys.ROTATE_MODE("rotate_mode", GLFW.GLFW_KEY_TAB, ...)}
     * （`simulated/common/.../index/SimKeys.java:14`）。用户拍板"牵引枪的操作方式以机械动力航空学为准"，
     * 所以这里不自行挑选更"顺手"的键。</p>
     *
     * <p>⚠️ <b>已知冲突（参考同样存在，不是我们的 bug）</b>：原版 {@code Options.keyPlayerList}
     * 默认也是 TAB（玩家列表），而参考用的是普通 {@code KeyMapping}、没做冲突处理 ——
     * 所以按住 TAB 会<b>同时</b>显示玩家列表并进入旋转模式。玩家可在"按键绑定"里改任一个。
     * （参考那个 {@code KeyboardHandlerMixin} 只拦鼠标键（{@code filterInteract} 只路由
     * attack/middle/use），<b>不</b>拦键盘键，所以它也没有压制玩家列表。）</p>
     */
    public static final KeyMapping ROTATE_MODE_KEY = new KeyMapping(
            "key.poly_mech.physgun_rotate",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_TAB,
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

    /** 拿着牵引枪（任一只手）。 */
    private static boolean isHolding(LocalPlayer player) {
        return player.getMainHandItem().getItem() instanceof PhysgunItem
                || player.getOffhandItem().getItem() instanceof PhysgunItem;
    }

    /** 拿着牵引枪（任一只手）并且确实抓着东西。 */
    private static boolean isHoldingGrabbing(LocalPlayer player) {
        return isHolding(player) && PhysgunClientState.isGrabbed(player.getUUID());
    }

    /**
     * 鼠标按下（由 {@code MouseHandlerMixin} 注入 {@code MouseHandler.onPress} 调过来）。
     *
     * <p><b>照参考的 {@code PhysicsStaffMouseHandler.onUse/onAttack}</b>：拿枪时把点击
     * <b>吃掉</b>（调用方 cancel 掉原版的 {@code onPress}），于是原版<b>从不进入</b>"使用中"状态。
     * 这正是"右键从长按改成开关"的前提 —— 见 {@link PhysgunActionPacket} 的类注释。</p>
     *
     * <p>右键是<b>开关</b>（照参考 {@code onItemUsed}：{@code START_DRAG && state == DRAGGING}
     * ⇒ {@code stopDragging()}）：按一次开始拖，再按一次松手。</p>
     *
     * @return true = 已消费 ⇒ 调用方必须 cancel 掉原版处理
     */
    public static boolean onMousePress(int button, int action) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        // 开着界面 / 有覆盖层时不抢点击（参考的注入点也在 getOverlay() 检查附近）
        if (player == null || mc.screen != null || mc.getOverlay() != null) {
            return false;
        }
        if (!isHolding(player)) {
            return false;
        }
        // 只抢"按下"：松开不抢。原版没收到按下，单独收到松开是无害的（参考也只 cancel PRESS）。
        if (action != GLFW.GLFW_PRESS) {
            return false;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return false;                   // 左键（LOCK）是下一步的事
        }

        UUID id = player.getUUID();
        // ★ 开关：**正在**拖着（而不是"正在淡出"）⇒ 再按一次松手。
        //   判据必须用 released 而不是 isGrabbed：后者在淡出期间仍为 true
        //   （要等强度衰减到 0 才回收，见 PhysgunClientState.finished），
        //   拿它当判据会让"松手后立刻想再抓"被当成又一次松手。
        PhysgunClientState.Beam beam = PhysgunClientState.beam(id);
        if (beam != null && !beam.released()) {
            PacketDistributor.sendToServer(new PhysgunActionPacket(
                    PhysgunAction.STOP_DRAG, beam.bodyId()));
            // 不直接删：标记 released，让强度曲线把它收掉（照参考的 intensity 衰减）
            PhysgunClientState.release(id);
            return true;
        }

        // 本地先算一次（解析求交，不依赖客户端碰撞体），让光束**立刻**出现；
        // 服务端权威包到达后接管（PhysgunClientState.serverDriven）。
        PhysgunTarget.Hit hit = PhysgunTarget.find(PhysgunItem.RANGE);
        if (hit != null) {
            Vec3 eye = player.getEyePosition(1.0F);
            Vec3 look = player.getViewVector(1.0F);
            Vec3 hitPoint = eye.add(look.scale(hit.distance()));
            PhysgunClientState.grab(id, hit.bodyId(),
                    Math.max(MIN_DISTANCE, Math.min(PhysgunItem.RANGE, hit.distance())),
                    eye.x + look.x * PhysgunItem.MUZZLE_FORWARD,
                    eye.y + look.y * PhysgunItem.MUZZLE_FORWARD,
                    eye.z + look.z * PhysgunItem.MUZZLE_FORWARD,
                    hitPoint.x, hitPoint.y, hitPoint.z);
        }
        // ★ 打不中也要发：客户端镜像没同步上时就是打不中，而服务端可能抓得住 ——
        //   这条教训原来写在 use() 里，现在搬到这里。服务端自己射线，不信这个 bodyId。
        PacketDistributor.sendToServer(new PhysgunActionPacket(
                PhysgunAction.START_DRAG, hit == null ? 0L : hit.bodyId()));
        return true;
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
        // ★ 照参考：不再拿着枪（切走了 / 丢掉了 / 换成别的）⇒ 松手。
        //   参考在 PhysicsStaffClientHandler.tick 里做同一件事
        //   （holdingStaff == false ⇒ state = PASSIVE, dragSession = null）。
        //   没有这一步，改成"开关"之后切快捷栏会永远拖着 —— 原版以前靠 releaseUsing 兜底。
        UUID self = player.getUUID();
        PhysgunClientState.Beam held = PhysgunClientState.beam(self);
        if (held != null && !held.released() && !isHolding(player)) {
            PacketDistributor.sendToServer(new PhysgunActionPacket(
                    PhysgunAction.STOP_DRAG, held.bodyId()));
            PhysgunClientState.release(self);
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
