package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.network.PhysgunActionPacket;
import com.mss.polymech.network.PhysgunBeamPacket;
import com.mss.polymech.network.PhysgunDragPacket;
import com.mss.polymech.network.PhysicsBodySyncPacket;
import com.mss.polymech.physics.BodyRaycast;
import com.mss.polymech.physics.PhysicsBodyTracker;
import com.mss.polymech.physics.PhysgunRotation;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 牵引枪（GMod 式"抓取 + 拖拽"）。
 *
 * <h2>操作方式（2026-10-01 起：完全照 Create Aeronautics）</h2>
 * 用户拍板"牵引枪的操作方式以机械动力航空学为准""一切都按照他的来"，参考是
 * {@code C:\Users\34573\Desktop\Simulated-Project-main}（{@code mod_name=Create Aeronautics}）。
 * 四条输入与参考逐字对齐：
 * <table border="1">
 *   <caption>输入表</caption>
 *   <tr><th>输入</th><th>行为</th></tr>
 *   <tr><td>右键按下</td><td><b>开关</b>：没抓着 ⇒ {@code START_DRAG}（服务端射线抓取）；
 *       正抓着 ⇒ {@code STOP_DRAG}（松手）</td></tr>
 *   <tr><td>左键按下</td><td>{@code LOCK}（钉住 / 解锁）—— <b>尚未实现</b>，见 {@code docs/physgun-rotation.md}
 *       第 9 节；需要先给原生层补约束 API（Rust 侧 {@code impulse_joints} 已有且已参与 step，
 *       只是没暴露 FFI）。{@link #canAttackBlock} 已经先把"左键顺手挖方块"挡掉了</td></tr>
 *   <tr><td>TAB 按住 + 移动鼠标</td><td>转动物体（相机冻结），灵敏度 0.35</td></tr>
 *   <tr><td>滚轮</td><td>改持握距离，钳在 {@code [2.0, 128.0]}</td></tr>
 * </table>
 * <b>关键后果</b>：原版"使用中"状态<b>不再被使用</b> —— 客户端在
 * {@code MouseHandler.onPress} 那一层就把点击 cancel 了（见 {@code MouseHandlerMixin}），
 * 所以 {@code use()} / {@code onUseTick} / {@code releaseUsing} 这些钩子<b>已经删掉</b>，
 * 施力改由 {@link #serverTick} 按 {@link #GRABS} 表推进。抓取的建立与结束分别由
 * {@code START_DRAG} / {@code STOP_DRAG} 两个动作包驱动（照参考的 {@code PhysicsStaffActionPacket}）。
 *
 * <h2>它作用在哪张表上（2026-09-29 修正；用户报"点一下只有一瞬间的射线、无法长按"的根因）</h2>
 * 原来这里查的是 MPS 层的 {@code ServerPhysicalWorld} / {@code PhysicalRaycast}，
 * 而玩家看得见、客户端也在拾取、太空放方块造出来的体都在 <b>{@link PhysicsBodyTracker}</b>。
 * 两张表不一致 ⇒「客户端解析求交命中、服务端两发全空」⇒ 服务端发 {@code released}
 * ⇒ 客户端那条本地预测的光束立刻被收回 ⇒ <b>只有一瞬间的射线</b>；{@code GRABS} 里没记录
 * ⇒ {@code onUseTick} 不施力 ⇒ <b>无法长按</b>。
 *
 * <p>而且那批 MPS 体的位姿是 <b>NaN</b>：诊断打出"最近 1.7976931348623157E308 格"
 * （= {@code Double.MAX_VALUE}，说明每个体的距离都算成 NaN），而 {@code ShipRaycast.pose()}
 * 里有 {@code position.isFinite()} 检查 ⇒ 它把所有体都跳过。现在服务端与客户端
 * <b>用同一张表 + 同一套数学</b>：{@link BodyRaycast} 与客户端 {@code PhysgunTarget}
 * 都是"把射线变到体局部做 slab 求交"（{@code RayBox}），两端判定不再可能分裂。</p>
 *
 * <h2>为什么用"弹簧力"而不是"设位置/设速度"</h2>
 * 物理体的位置权威在 Rapier 刚体上。任何"直接搬位置"的做法都会和求解器抢权威
 * （本项目在玩家物理上已经栽过：setPos 重定向 ⇒ 原版碰撞与 Rapier 互抢，见 §22）。
 * 所以这里**只施力**：目标点 = 准星前方 {@code holdDistance} 处，力按
 * {@code a = Kp·误差 − Kd·速度} 算，再乘质量变成力。
 *
 * <h2>冲量 = 力 × 持续时间（与原来 MPS 那条路同一份数学）</h2>
 * 原来 MPS 那边是 {@code body.addForce(new Force(f, 0.05))}（施加 0.05 秒的力）；
 * tracker 只暴露 {@code applyImpulse}，所以这里取 {@code J = f·FORCE_DURATION}：
 * 一个 MC tick（0.05 秒）施加一次 ⇒ 等效 Δv 完全一致。弹簧数学仍只在
 * {@link com.mss.polymech.physics.PhysgunSpring} 一处定义，离线探针（{@code PhysgunDragProbe}）调的也是那一份。
 */
public class PhysgunItem extends Item {

    /**
     * 射线能抓到多远（格），同时是滚轮持握距离的上限。
     *
     * <p><b>照抄参考</b>：{@code PhysicsStaffItem.RANGE = 128.0f}
     * （`simulated/common/.../content/physics_staff/PhysicsStaffItem.java:10`）。
     * 客户端与服务端共用这一个常量（{@code PhysgunTarget.find} / {@code PhysgunBeamRenderer} /
     * {@code BodyRaycast.nearest}），所以改一处两端一致。</p>
     */
    public static final double RANGE = 128.0;
    /** 枪口的近似前伸量（格）：服务端算光束起点用；第一人称的精确枪口在客户端渲染器里。 */
    public static final double MUZZLE_FORWARD = 0.35;

    private static final SoundEvent SOUND_GRAB = SoundEvents.CONDUIT_ACTIVATE;
    private static final SoundEvent SOUND_RELEASE = SoundEvents.CONDUIT_DEACTIVATE;

    /**
     * 旋转判据的残角阈值（度）：松手时"目标朝向 vs 实际朝向"小于它才算 PASS。
     *
     * <p>取 5°：角伺服在 2% 整定判据下 2 秒内收敛到约 1.5°（见
     * {@code PhysgunRotationProbe} 的 C 组），5° 留了足够余量给"刚转完就松手"的情况，
     * 又远小于"完全没转"（那会是几十度）。</p>
     */
    public static final double RESIDUAL_PASS_DEG = 5.0;

    /** 抓住状态：只在服务端记（物理体是服务端权威）。 */
    private static final Map<UUID, Grab> GRABS = new ConcurrentHashMap<>();

    /**
     * 抓取记录。
     *
     * @param bodyId      {@link PhysicsBodyTracker} 的 long id（**不是** MPS 的 UUID —— 见类注释）
     * @param anchorX/Y/Z 抓点在<b>体局部</b>空间的坐标。服务端每
     *                    {@link PhysgunBeamPacket#BROADCAST_INTERVAL_TICKS} tick 用它重算世界端点，
     *                    所以船转起来时光束跟着转，而不是钉在世界某点。
     * @param orientation <b>目标朝向</b>（旋转控制的指令）；{@code null} = 玩家还没转过
     *                    ⇒ 不做角伺服，物体保持原来的自由摆动。由 {@link PhysgunDragPacket} 更新。
     */
    private record Grab(long bodyId, double holdDistance,
                        double anchorX, double anchorY, double anchorZ,
                        Quaterniond orientation) {

        /** 换成"带新朝向/新距离"的一份（record 不可变，所以是替换而不是改字段）。 */
        Grab withDrag(double hold, Quaterniond q) {
            return new Grab(bodyId, hold, anchorX, anchorY, anchorZ, q);
        }
    }

    public PhysgunItem(Properties properties) {
        super(properties);
    }

    /**
     * <b>照抄参考</b>：拿着牵引枪时左键<b>不破坏方块</b>
     * （{@code PhysicsStaffItem.canAttackBlock(...) { return false; }}）。
     *
     * <p>理由：左键在参考里是 <b>LOCK</b>（钉住/解锁抓着的体）。若不拦，玩家每次锁一下都会顺手
     * 把准星前的方块挖掉。客户端那一层已经会把左键吃掉（不让原版收到），这里是<b>服务端</b>的第二道
     * —— 客户端可被绕过，所以权威侧也必须拒绝。</p>
     */
    @Override
    public boolean canAttackBlock(BlockState state, Level level, BlockPos pos, Player player) {
        return false;
    }

    // ==================== 输入 → 动作（照参考：右键是开关，不是长按） ====================

    /**
     * 拿着牵引枪（任一只手）—— 照参考的 {@code PhysicsStaffItem.isHolding}。
     *
     * <p>服务端校验用：{@link PhysgunActionPacket} 是客户端来的，不能信任。</p>
     */
    public static boolean isHolding(Player player) {
        return player.getMainHandItem().getItem() instanceof PhysgunItem
                || player.getOffhandItem().getItem() instanceof PhysgunItem;
    }

    /**
     * 服务端动作入口（来自 {@link PhysgunActionPacket}）—— 照参考的
     * {@code PhysicsStaffActionPacket.handle}。
     *
     * <p><b>校验</b>：照参考的 {@code validateWorthyness}，没拿着牵引枪就不认。区别是参考会
     * {@code disconnect("Invalid packet")} 直接踢人，我们只静默拒绝 —— 存在<b>合法竞态</b>
     * （按下右键后立刻切走快捷栏，包到达时手里已经不是枪了），踢人会误伤。见包注释。</p>
     */
    public static void applyAction(ServerPlayer sp, PhysgunActionPacket packet) {
        if (!isHolding(sp)) {
            return;
        }
        switch (packet.action()) {
            case START_DRAG -> startDrag(sp);
            case STOP_DRAG -> {
                Grab grab = GRABS.get(sp.getUUID());
                if (grab != null) {
                    stopDrag(sp, grab);
                }
            }
        }
    }

    /**
     * 开始拖 —— <b>服务端自己射线</b>决定抓谁（不信任包里的 {@code bodyId}）。
     *
     * <p>参考的 {@code startDraggingSubLevel} 是客户端选目标、服务端照着建会话（拖拽包带
     * 目标 UUID，{@code drag(...)} 首次收到就建）；我们反过来，因为服务端射线才是权威 ——
     * 见 {@link PhysgunAction} 的类注释。</p>
     */
    private static void startDrag(ServerPlayer sp) {
        if (GRABS.containsKey(sp.getUUID())) {
            return;                         // 已经抓着 ⇒ 重复的 START（丢包重发 / 改包），忽略
        }
        ServerLevel serverLevel = sp.serverLevel();
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        BodyRaycast.Hit hit = raycast(serverLevel, eye, look);
        if (hit == null) {
            tell(sp, "准星没指到物理体（" + (int) RANGE + " 格内）"
                    + nearestBodyInfo(serverLevel, eye), ChatFormatting.YELLOW);
            broadcastReleased(sp);          // 客户端已经起了本地预测，得让它收回去
            return;
        }

        double hold = Math.max(2.0, Math.min(RANGE, hit.distance()));
        // 目标朝向给 null：玩家还没转过 ⇒ 不做角伺服（物体照旧自由摆动）。
        // 第一次转动时客户端会从镜子体的当前朝向起步再发上来，所以不会跳。
        Grab grab = new Grab(hit.id(), hold, hit.localX(), hit.localY(), hit.localZ(), null);
        GRABS.put(sp.getUUID(), grab);
        broadcast(sp, grab);                // 立刻发一次：客户端不用等 2 tick 才有光束
        play(sp, SOUND_GRAB, 0.7F, 1.35F);
        tell(sp, String.format(java.util.Locale.ROOT,
                "已抓住 物理体 #%d（质量 %.0f kg，保持 %.1f 格）—— 再按一次右键松手",
                hit.id(), PhysicsBodyTracker.massOf(hit.id()), hold), ChatFormatting.AQUA);
    }

    /** 松手 —— 原来挂在 {@code releaseUsing} 上，现在由 {@code STOP_DRAG} 动作驱动。 */
    private static void stopDrag(ServerPlayer sp, Grab grab) {
        GRABS.remove(sp.getUUID());
        broadcastReleased(sp);
        play(sp, SOUND_RELEASE, 0.55F, 1.2F);
        tell(sp, "已松开", ChatFormatting.GRAY);
        reportRotationVerdict(sp, grab);
    }

    /**
     * 每个服务端 tick：把每个"抓着东西"的玩家的刚体往"准星前方 holdDistance"处拉。
     *
     * <p>原来这活儿挂在 {@code onUseTick}（原版"使用中"状态）上 —— 参考<b>不用</b>原版使用状态
     * （它在客户端鼠标层就把右键 cancel 掉了），所以施力改成服务端自己按 {@link #GRABS} 推进。
     * 见 {@link PhysgunActionPacket} 的类注释。</p>
     *
     * <p>只施力、不写位置 —— 见类注释。目标点每 tick 跟着视线走，所以可以一边走一边拖。</p>
     */
    public static void serverTick(MinecraftServer server) {
        if (GRABS.isEmpty()) {
            return;
        }
        // GRABS 是 ConcurrentHashMap：tickGrab 里可能 remove（体没了），边遍历边删是安全的
        for (Map.Entry<UUID, Grab> entry : GRABS.entrySet()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(entry.getKey());
            if (sp == null) {
                GRABS.remove(entry.getKey());   // 下线了 ⇒ 放手（否则冲量一直施在没人管的体上）
                continue;
            }
            tickGrab(sp, entry.getValue());
        }
    }

    /** 单个抓取的每 tick 施力：线性弹簧 + 角伺服。 */
    private static void tickGrab(ServerPlayer sp, Grab grab) {
        double[] pos = PhysicsBodyTracker.positionOf(grab.bodyId());
        double mass = PhysicsBodyTracker.massOf(grab.bodyId());
        if (pos == null || !(mass > 0.0)) {
            GRABS.remove(sp.getUUID());     // 体没了或读不到（被拆/换维度）⇒ 自动放手
            broadcastReleased(sp);
            return;
        }

        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        Vector3d target = new Vector3d(eye.x + look.x * grab.holdDistance(),
                eye.y + look.y * grab.holdDistance(),
                eye.z + look.z * grab.holdDistance());

        Vector3d err = new Vector3d(target).sub(pos[0], pos[1], pos[2]);
        double[] motion = PhysicsBodyTracker.motionOf(grab.bodyId());
        Vector3d vel = motion == null ? new Vector3d() : new Vector3d(motion[0], motion[1], motion[2]);
        // 弹簧数学抽在 PhysgunSpring（纯函数、无 MC 依赖）⇒ 离线探针能直接调它验证收敛性，
        // 而不是"在探针里抄一遍公式"。限幅与稳定性理由见那个类。
        Vector3d acc = com.mss.polymech.physics.PhysgunSpring.accel(err, vel);
        Vector3d force = com.mss.polymech.physics.PhysgunSpring.forceFor(mass, acc);
        // 冲量 = 力 × 持续时间（照原来 addForce(Force(f, 0.05)) 的等效量）
        double dt = com.mss.polymech.physics.PhysgunSpring.FORCE_DURATION;
        PhysicsBodyTracker.applyImpulse(grab.bodyId(), force.x * dt, force.y * dt, force.z * dt);

        // ---- 角伺服（旋转控制）：与上面那半逐项对称，只是量纲从"加速度"换成"角加速度" ----
        // 参考在 PhysicsStaffServerHandler.physicsTick 里给约束的 3 个角轴设马达
        // （目标角 0 + 刚度/阻尼），由引擎驱动 —— 不是 setRotation。我们用力矩冲量做同一件事，
        // 数学全在 PhysgunRotation（纯函数），离线判据见 native/jni-smoketest/PhysgunRotationProbe。
        if (grab.orientation() != null) {
            double[] rot = PhysicsBodyTracker.rotationOf(grab.bodyId());
            double inertia = PhysicsBodyTracker.inertiaOf(grab.bodyId());
            if (rot != null && inertia > 0.0) {
                Quaterniond current = new Quaterniond(rot[0], rot[1], rot[2], rot[3]);
                double[] axisAngle = PhysgunRotation.errorAxisAngle(current, grab.orientation());
                Vector3d angVel = motion == null
                        ? new Vector3d()
                        : new Vector3d(motion[3], motion[4], motion[5]);
                Vector3d alpha = PhysgunRotation.angularAccel(axisAngle, angVel);
                Vector3d torque = PhysgunRotation.torqueFor(inertia, alpha);
                Vector3d spin = PhysgunRotation.torqueImpulse(torque);
                PhysicsBodyTracker.applyTorqueImpulse(grab.bodyId(), spin.x, spin.y, spin.z);
            }
        }

        // 光束端点按 2 tick 的节奏重算（抓点跟着刚体姿态走）
        if (sp.tickCount % PhysgunBeamPacket.BROADCAST_INTERVAL_TICKS == 0) {
            broadcast(sp, grab);
        }
    }

    /** 服务端退出时清理（避免 UUID 泄漏）。 */
    public static void forget(UUID playerId) {
        GRABS.remove(playerId);
    }

    /**
     * 拖拽指令（服务端，来自 {@link PhysgunDragPacket}）：更新目标朝向与持握距离。
     *
     * <p><b>这里是旋转控制的唯一入口</b>，而且必须校验：客户端可以改包，所以
     * "这个玩家确实抓着这个体"这一条要在这里再查一遍（服务端抓取状态才是权威）。
     * 距离钳在 {@code [2, RANGE]}（与客户端同一个区间），四元数归一化并拒绝非有限值
     * —— 一个 NaN 朝向喂给角伺服会让刚体的角速度变成 NaN，整个体就废了。</p>
     */
    public static void applyDrag(ServerPlayer sp, PhysgunDragPacket packet) {
        Grab grab = GRABS.get(sp.getUUID());
        if (grab == null || grab.bodyId() != packet.bodyId()) {
            // 没抓着 / 抓的不是这个体 ⇒ 静默忽略（改包 / 竞态下的旧包）
            return;
        }
        Quaterniond q = new Quaterniond(packet.qx(), packet.qy(), packet.qz(), packet.qw());
        if (!q.isFinite() || q.lengthSquared() < 1.0e-12) {
            return;
        }
        q.normalize();
        double hold = Math.max(2.0, Math.min(RANGE, packet.holdDistance()));
        GRABS.put(sp.getUUID(), grab.withDrag(hold, q));
    }

    /**
     * 松手时的<b>旋转判据</b>（程序判定 PASS/FAIL，实机验收用）。
     *
     * <p>判据本身是<b>绝对量</b>：目标朝向与实际朝向之间的最短弧残角（度），与相机朝向无关
     * —— 这是本项目"判据不许相机相对"那条规矩的直接落实。</p>
     *
     * <p>为什么松手时判：角伺服只在抓着的时候施力，所以松手那一刻的残角就是
     * "伺服到底有没有把体转到指令朝向"的最终答案。玩家没转过（{@code orientation == null}）
     * 就不打这一行 —— 否则每次抓一下都刷一条无意义的 PASS。</p>
     */
    private static void reportRotationVerdict(ServerPlayer sp, Grab grab) {
        if (grab.orientation() == null) {
            return;
        }
        double[] rot = PhysicsBodyTracker.rotationOf(grab.bodyId());
        if (rot == null) {
            tell(sp, "[牵引枪·旋转判据] 读不到体朝向 ⇒ 无法判定", ChatFormatting.YELLOW);
            return;
        }
        Quaterniond current = new Quaterniond(rot[0], rot[1], rot[2], rot[3]);
        double residualDeg = Math.toDegrees(
                PhysgunRotation.errorAxisAngle(current, grab.orientation())[3]);
        boolean pass = residualDeg <= RESIDUAL_PASS_DEG;
        tell(sp, String.format(java.util.Locale.ROOT,
                        "[牵引枪·旋转判据] 松手残角 %.2f°（阈值 %.0f°）⇒ %s；持握 %.2f 格",
                        residualDeg, RESIDUAL_PASS_DEG, pass ? "PASS" : "FAIL", grab.holdDistance()),
                pass ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    // ==================== 求交 / 广播（都在 tracker 这张表上） ====================

    /**
     * 在 {@link PhysicsBodyTracker} 的体上求最近命中 —— 与客户端 {@code PhysgunTarget}
     * 同一套数学（{@link BodyRaycast} + {@code RayBox}），所以两端判定一致。
     */
    private static BodyRaycast.Hit raycast(ServerLevel level, Vec3 eye, Vec3 look) {
        List<BodyRaycast.Body> candidates = new ArrayList<>();
        for (long id : PhysicsBodyTracker.ids()) {
            if (PhysicsBodyTracker.levelOf(id) != level) {
                continue;
            }
            double[] pos = PhysicsBodyTracker.positionOf(id);
            double[] rot = PhysicsBodyTracker.rotationOf(id);
            if (pos == null || rot == null) {
                continue;
            }
            List<PhysicsBodySyncPacket.BlockEntry> blocks = PhysicsBodyTracker.blocksOf(id);
            if (blocks.isEmpty()) {
                continue;
            }
            int n = blocks.size();
            int[] xs = new int[n];
            int[] ys = new int[n];
            int[] zs = new int[n];
            for (int i = 0; i < n; i++) {
                PhysicsBodySyncPacket.BlockEntry e = blocks.get(i);
                xs[i] = e.dx();
                ys[i] = e.dy();
                zs[i] = e.dz();
            }
            candidates.add(new BodyRaycast.Body(id, pos[0], pos[1], pos[2],
                    rot[0], rot[1], rot[2], rot[3], xs, ys, zs));
        }
        return BodyRaycast.nearest(candidates, eye.x, eye.y, eye.z, look.x, look.y, look.z, RANGE);
    }

    /**
     * 没打中时，把"最近的那个物理体离你多远"一起报出来。
     *
     * <p>2026-09-29 的教训：一句"准星没指到物理体"区分不了"没瞄准 / 太远 / 求交坏了"。
     * 当时加上这行诊断后打出来的是 {@code 1.7976931348623157E308}（= {@code Double.MAX_VALUE}），
     * 一眼就看出"每个体的距离都算成 NaN" ⇒ 直接指向"查错了注册表"。</p>
     */
    private static String nearestBodyInfo(ServerLevel level, Vec3 eye) {
        double best = Double.MAX_VALUE;
        int count = 0;
        for (long id : PhysicsBodyTracker.ids()) {
            if (PhysicsBodyTracker.levelOf(id) != level) {
                continue;
            }
            double[] pos = PhysicsBodyTracker.positionOf(id);
            if (pos == null) {
                continue;
            }
            count++;
            double d = Math.sqrt((eye.x - pos[0]) * (eye.x - pos[0])
                    + (eye.y - pos[1]) * (eye.y - pos[1])
                    + (eye.z - pos[2]) * (eye.z - pos[2]));
            if (d < best) {
                best = d;
            }
        }
        if (count == 0) {
            return "，这个维度一个物理体都没有";
        }
        return String.format(java.util.Locale.ROOT, "，最近体原点 %.1f 格（共 %d 个体）", best, count);
    }

    /** 体局部抓点 → 世界坐标（跟着刚体姿态走：船转，光束的抓点也跟着转）。 */
    private static Vector3d worldAnchor(Grab grab) {
        double[] pos = PhysicsBodyTracker.positionOf(grab.bodyId());
        double[] rot = PhysicsBodyTracker.rotationOf(grab.bodyId());
        if (pos == null || rot == null) {
            return null;
        }
        Quaterniond q = new Quaterniond(rot[0], rot[1], rot[2], rot[3]);
        if (!q.isFinite() || q.lengthSquared() < 1.0e-12) {
            return null;
        }
        q.normalize();
        return q.transform(new Vector3d(grab.anchorX(), grab.anchorY(), grab.anchorZ()))
                .add(pos[0], pos[1], pos[2]);
    }

    private static void broadcast(ServerPlayer sp, Grab grab) {
        if (!(sp.level() instanceof ServerLevel level)) {
            return;
        }
        Vector3d anchor = worldAnchor(grab);
        if (anchor == null) {
            return;
        }
        Vec3 eye = sp.getEyePosition();
        Vec3 look = sp.getLookAngle();
        PacketDistributor.sendToPlayersNear(level, null,
                sp.getX(), sp.getY(), sp.getZ(), PhysgunBeamPacket.BROADCAST_RANGE,
                new PhysgunBeamPacket(sp.getUUID(),
                        eye.x + look.x * MUZZLE_FORWARD,
                        eye.y + look.y * MUZZLE_FORWARD,
                        eye.z + look.z * MUZZLE_FORWARD,
                        anchor.x, anchor.y, anchor.z,
                        grab.holdDistance(), false));
    }

    /** 告诉附近的人（含自己）：这条光束该收起来了。 */
    private static void broadcastReleased(ServerPlayer sp) {
        if (!(sp.level() instanceof ServerLevel level)) {
            return;
        }
        PacketDistributor.sendToPlayersNear(level, null,
                sp.getX(), sp.getY(), sp.getZ(), PhysgunBeamPacket.BROADCAST_RANGE,
                PhysgunBeamPacket.released(sp.getUUID()));
    }

    private static void play(ServerPlayer sp, SoundEvent event, float volume, float pitch) {
        // 参考的牵引枪有 ignite/idle/lock/extinguish 一整套自造音效；自造 .ogg 资产是另一件事，
        // 这里先用原版导管音顶上，至少有听觉反馈。
        sp.level().playSound(null, sp.getX(), sp.getY(), sp.getZ(), event, SoundSource.PLAYERS, volume, pitch);
    }

    private static void tell(ServerPlayer player, String msg, ChatFormatting color) {
        player.displayClientMessage(Component.literal(msg).withStyle(color), true);
        Polymech.LOGGER.info("[牵引枪] {} ：{}", player.getGameProfile().getName(), msg);
    }
}
