package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.network.PhysgunActionPacket;
import com.mss.polymech.network.PhysgunBeamPacket;
import com.mss.polymech.network.PhysgunDragPacket;
import com.mss.polymech.network.PhysicsBodySyncPacket;
import com.mss.polymech.physics.BodyRaycast;
import com.mss.polymech.physics.PhysicsBodyTracker;
import com.mss.polymech.physics.PhysicsNatives;
import com.mss.polymech.physics.PhysgunLocks;
import com.mss.polymech.physics.PhysgunRotation;
import com.mss.polymech.physics.PhysicsStepThread;
import com.mss.polymech.space.SpaceAimOrigin;
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
import java.util.Set;
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
 *   <tr><td>左键按下</td><td>{@code LOCK}（钉住 / 解锁）—— 给体加一个**原生固定约束**（把"世界"那个锚
 *       显式建出来，照参考的 {@code addConstraint(null, ...)}），并持久化到 NBT。
 *       见 {@link PhysgunLocks} 与 {@code docs/physgun-rotation.md} 第 11 节</td></tr>
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
 * <h2>冲量 = 力 × 持续时间，且**按物理子步摊**（2026-10-04 起）</h2>
 * 原来 MPS 那边是 {@code body.addForce(new Force(f, 0.05))}（施加 0.05 秒的力），
 * 旧实现等效成"每 MC tick 打一次 {@code J = f·0.05}" —— 那是 20Hz 的方波激励：
 * 物体每 tick 位移快慢交替，2026-10-04 的四组实机读数里它是终点抖动的**地板（≈2.3×）**。
 * 现在冲量由<b>步进线程</b>的每个 100Hz 子步施加（{@link #physicsStep}，
 * {@code J = f·0.01}）：频率 ×5、单次量 ÷5，<b>平均力逐位不变</b>。
 * 弹簧数学仍只在 {@link com.mss.polymech.physics.PhysgunSpring} 一处定义，
 * 离线探针（{@code PhysgunDragProbe}）调的也是那一份。
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

    /** 已注册过"伺服子步回调"的物理世界（幂等：一个世界只挂一次）。 */
    private static final Set<Long> SERVO_WORLDS = ConcurrentHashMap.newKeySet();

    /**
     * 抓取记录。
     *
     * <p>曾是 record —— 但伺服的<b>目标快照</b>需要"主线程每 tick 写、步进线程每 100Hz 子步读"，
     * record 不允许实例字段，整体替换（withDrag）也做不到单写单读，所以是一个普通的不可变引用 +
     * 几个 volatile 快照字段的类。访问器名保持 {@code bodyId()}/{@code holdDistance()}/{@code orientation()}
     * 不变，调用点与契约不受影响。</p>
     *
     * @param bodyId      {@link PhysicsBodyTracker} 的 long id（**不是** MPS 的 UUID —— 见类注释）
     * @param anchorX/Y/Z 抓点在<b>体局部</b>空间的坐标。服务端每
     *                    {@link PhysgunBeamPacket#BROADCAST_INTERVAL_TICKS} tick 用它重算世界端点，
     *                    所以船转起来时光束跟着转，而不是钉在世界某点。
     * @param orientation <b>目标朝向</b>（旋转控制的指令）；{@code null} = 玩家还没转过
     *                    ⇒ 不做角伺服，物体保持原来的自由摆动。由 {@link PhysgunDragPacket} 更新。
     */
    private static final class Grab {
        final long bodyId;
        final double anchorX, anchorY, anchorZ;
        volatile double holdDistance;
        volatile Quaterniond orientation;

        /**
         * 伺服快照：目标点与质量由<b>主线程</b>每 MC tick 写一次，
         * <b>步进线程</b>每 100Hz 子步读（见 {@link #physicsStep}）。
         * {@code servoReady} 首次刷上才允许施力 —— 否则建抓后的第一个子步会朝 (0,0,0) 拉一把。
         */
        volatile double goalX, goalY, goalZ;
        volatile double mass;
        volatile boolean servoReady;

        Grab(long bodyId, double holdDistance, double anchorX, double anchorY, double anchorZ,
             Quaterniond orientation) {
            this.bodyId = bodyId;
            this.holdDistance = holdDistance;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.anchorZ = anchorZ;
            this.orientation = orientation;
        }

        long bodyId() {
            return bodyId;
        }

        double holdDistance() {
            return holdDistance;
        }

        Quaterniond orientation() {
            return orientation;
        }

        double anchorX() {
            return anchorX;
        }

        double anchorY() {
            return anchorY;
        }

        double anchorZ() {
            return anchorZ;
        }

        /** 换朝向/距离（原 withDrag 的语义：record 不可变时是替换，现在是原地 volatile 写）。 */
        void setDrag(double hold, Quaterniond q) {
            this.holdDistance = hold;
            this.orientation = q;
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
            case LOCK -> toggleLock(sp);
        }
    }

    /**
     * 左键 = 钉住 / 解锁 —— 照参考的 {@code onItemPunched()} → {@code LOCK} → {@code toggleLock(uuid)}。
     *
     * <p>钉的是<b>当前抓着的那个体</b>（参考的 {@code LOCK} 分支就是从拖拽会话里取的子级），
     * 所以没抓着时左键什么也不做。</p>
     */
    private static void toggleLock(ServerPlayer sp) {
        Grab grab = GRABS.get(sp.getUUID());
        if (grab == null) {
            tell(sp, "左键 = 钉住/解锁，但你现在没抓着东西", ChatFormatting.GRAY);
            return;
        }
        if (!PhysicsNatives.hasFixedConstraints()) {
            tell(sp, "[牵引枪·钉住] 原生库缺少约束 API（ABI < "
                    + PhysicsNatives.MIN_ABI_CONSTRAINT + "）⇒ 不可用", ChatFormatting.RED);
            return;
        }
        PhysgunLocks locks = PhysgunLocks.get(sp.server);
        boolean nowLocked = locks.toggle(grab.bodyId());
        if (nowLocked) {
            play(sp, SOUND_GRAB, 1.0F, 0.7F);
            tell(sp, String.format(java.util.Locale.ROOT,
                    "已钉住 物理体 #%d—— 再按左键解锁；拖它也会自动解锁",
                    grab.bodyId()), ChatFormatting.GOLD);
            // 2 秒后自动打一条程序判定的读数（约束到底生效没、漂移多少）
            locks.armLockVerdict(sp, grab.bodyId());
        } else {
            play(sp, SOUND_RELEASE, 1.0F, 0.7F);
            tell(sp, String.format(java.util.Locale.ROOT,
                    "已解锁 物理体 #%d", grab.bodyId()), ChatFormatting.YELLOW);
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
        // 起点走 SpaceAimOrigin（太空=相机锚点）：服务端原先用 getEyePosition，
        // 太空里与准星平行错开 1.31~1.43 格 ⇒ "抓错点/抓不住"（2026-10-06）。
        Vec3 eye = SpaceAimOrigin.of(sp);
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

    /** 单个抓取的每 tick 事务：解锁 → 有效性 → 刷伺服快照 → 光束广播节奏。 */
    private static void tickGrab(ServerPlayer sp, Grab grab) {
        // ★ 照参考 drag(...) 里的 `this.removeLock(subLevel)`：**拖着的东西必须是自由的**。
        //   参考的拖拽包每 tick 都发，所以它那条 removeLock 也每 tick 都跑 —— 效果就是
        //   "拖着的体不可能保持被钉住"（哪怕你刚按左键锁了它，下一个 tick 就被解掉）。
        //   我们把这句话放在每 tick 的施力里，语义与它一致：约束和弹簧力不能同时作用，
        //   否则两者互相顶，物体会抖。
        PhysgunLocks.get(sp.server).removeLock(grab.bodyId());

        double[] pos = PhysicsBodyTracker.positionOf(grab.bodyId());
        double mass = PhysicsBodyTracker.massOf(grab.bodyId());
        if (pos == null || !(mass > 0.0)) {
            GRABS.remove(sp.getUUID());     // 体没了或读不到（被拆/换维度）⇒ 自动放手
            broadcastReleased(sp);
            return;
        }

        Vec3 eye = SpaceAimOrigin.of(sp);
        Vec3 look = sp.getLookAngle();
        // 伺服快照：目标点（**视线起点** + 视线 × 持握距离）与质量，一 tick 刷一次给步进线程读。
        // 目标跟着视线走，所以可以一边走一边拖；快照本身就是 20Hz 更新的（玩家视角也就这个节奏），
        // 真正要求 100Hz 连续的是**施力**，见 physicsStep。
        grab.goalX = eye.x + look.x * grab.holdDistance();
        grab.goalY = eye.y + look.y * grab.holdDistance();
        grab.goalZ = eye.z + look.z * grab.holdDistance();
        grab.mass = mass;
        grab.servoReady = true;
        ensureServo(grab.bodyId());

        // 光束端点按 2 tick 的节奏重算（抓点跟着刚体姿态走）
        if (sp.tickCount % PhysgunBeamPacket.BROADCAST_INTERVAL_TICKS == 0) {
            broadcast(sp, grab);
        }
    }

    /**
     * 把伺服挂到<b>物理步进线程</b>上（每个 100Hz 子步跑一次 {@link #physicsStep}）——按世界幂等注册。
     *
     * <p>为什么在步进线程：弹簧原来是"每 MC tick 打一次冲量"（20Hz 方波激励），
     * 2026-10-04 的四组实机读数显示它就是终点抖动的<b>地板（≈2.3×）</b>——
     * 物体每 tick 位移快慢交替，采样后速度忽高忽低，物理上物体也在微抽。
     * 把同一份平均力摊到 100Hz 子步上（{@code F×0.01×100/s = F×0.05×20/s}，平均力逐位不变），
     * 激励频率挪到求解器自己的节奏 —— 这与参考侧 CA 的"求解器内连续 motor"是同一件事，
     * 只是我们原生层没有持续力 API（只有冲量），所以按子步摊。</p>
     *
     * <p>⚠️ 这就是"写了必须接线"的那类改动：注册只在首个子步回调真正挂上之前有效，
     * 契约（check-artifact）钉住 {@code PhysicsStepThread.addPostStep} 这一句。</p>
     */
    private static void ensureServo(long bodyId) {
        long world = PhysicsBodyTracker.worldOf(bodyId);
        if (world > 0L && SERVO_WORLDS.add(world)) {
            PhysicsStepThread.addPostStep(world, () -> physicsStep(world));
        }
    }

    /**
     * 每个 100Hz 物理子步（<b>步进线程</b>）：线性弹簧 + 角伺服的连续施力。
     *
     * <p>数学与原来逐 tick 版本逐字同源（{@link com.mss.polymech.physics.PhysgunSpring} /
     * {@link com.mss.polymech.physics.PhysgunRotation}，离线探针调的就是这两份），
     * 唯一差别是冲量的<b>持续时间</b>从 {@code FORCE_DURATION=0.05}（一个 MC tick）
     * 换成 {@link PhysicsStepThread#STEP_SECONDS}（=0.01，一个物理子步）——
     * 频率 ×5、单次量 ÷5，平均力与手感约束（限幅 MAX_A / MAX_ANG_ACCEL）完全不变。</p>
     *
     * <p>线程与越界：本回调由每个物理世界各挂一份，靠 {@code worldOf == worldHandle} 只处理
     * 属于本世界的抓取（applyImpulse 内部也是按体自己的世界解析句柄，这里对表是为了不让
     * A 世界的回调以 100Hz×N 份重复对 B 世界的体施力）。原生读写在步进刚结束的同线程上做，
     * 与主线程的并发由原生层 {@code with_world} 互斥保证（与既有 {@code PlayerPhysicsBody.afterStep} 同一纪律）。</p>
     */
    public static void physicsStep(long worldHandle) {
        if (GRABS.isEmpty()) {
            return;
        }
        double dt = PhysicsStepThread.STEP_SECONDS;
        for (Map.Entry<UUID, Grab> entry : GRABS.entrySet()) {
            Grab grab = entry.getValue();
            if (!grab.servoReady || PhysicsBodyTracker.worldOf(grab.bodyId()) != worldHandle) {
                continue;
            }
            double[] pos = PhysicsBodyTracker.positionOf(grab.bodyId());
            if (pos == null || !(grab.mass > 0.0)) {
                continue;   // 有效性由主线程 tickGrab 把关；这里只是本子步读不到，跳过一拍
            }
            double[] motion = PhysicsBodyTracker.motionOf(grab.bodyId());
            Vector3d vel = motion == null ? new Vector3d() : new Vector3d(motion[0], motion[1], motion[2]);
            Vector3d err = new Vector3d(grab.goalX, grab.goalY, grab.goalZ).sub(pos[0], pos[1], pos[2]);
            Vector3d acc = com.mss.polymech.physics.PhysgunSpring.accel(err, vel);
            Vector3d force = com.mss.polymech.physics.PhysgunSpring.forceFor(grab.mass, acc);
            PhysicsBodyTracker.applyImpulse(grab.bodyId(), force.x * dt, force.y * dt, force.z * dt);

            // ---- 角伺服（旋转控制）：与上面那半逐项对称，只是量纲从"加速度"换成"角加速度" ----
            // 参考在 PhysicsStaffServerHandler.physicsTick 里给约束的 3 个角轴设马达
            // （目标角 0 + 刚度/阻尼），由引擎驱动 —— 不是 setRotation。我们用力矩冲量做同一件事，
            // 数学全在 PhysgunRotation（纯函数），离线判据见 native/jni-smoketest/PhysgunRotationProbe。
            Quaterniond orientation = grab.orientation();
            if (orientation != null) {
                double[] rot = PhysicsBodyTracker.rotationOf(grab.bodyId());
                double inertia = PhysicsBodyTracker.inertiaOf(grab.bodyId());
                if (rot != null && inertia > 0.0) {
                    Quaterniond current = new Quaterniond(rot[0], rot[1], rot[2], rot[3]);
                    double[] axisAngle = PhysgunRotation.errorAxisAngle(current, orientation);
                    Vector3d angVel = motion == null
                            ? new Vector3d()
                            : new Vector3d(motion[3], motion[4], motion[5]);
                    Vector3d alpha = PhysgunRotation.angularAccel(axisAngle, angVel);
                    Vector3d torque = PhysgunRotation.torqueFor(inertia, alpha);
                    Vector3d spin = PhysgunRotation.torqueImpulse(torque, dt);
                    PhysicsBodyTracker.applyTorqueImpulse(grab.bodyId(), spin.x, spin.y, spin.z);
                }
            }
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
        grab.setDrag(hold, q);
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

    private static void broadcast(ServerPlayer sp, Grab grab) {
        if (!(sp.level() instanceof ServerLevel level)) {
            return;
        }
        // ★ 只发**抓点这个概念**（体 id + 体局部坐标），不发世界坐标 —— 见 BodyLocalAnchor 的类注释。
        //   发世界坐标就必须在每个 tick 采样一次体位姿，而物体被拖过地面时接触抖动是 100Hz 级
        //   ⇒ 20Hz 采样必然混叠（"玩家一动线就抽"的根因）。抓点在整次抓取里是常量，
        //   世界点交给客户端用**它自己的**连续位姿每帧现算。
        Vec3 eye = SpaceAimOrigin.of(sp);
        Vec3 look = sp.getLookAngle();
        PacketDistributor.sendToPlayersNear(level, null,
                sp.getX(), sp.getY(), sp.getZ(), PhysgunBeamPacket.BROADCAST_RANGE,
                new PhysgunBeamPacket(sp.getUUID(),
                        eye.x + look.x * MUZZLE_FORWARD,
                        eye.y + look.y * MUZZLE_FORWARD,
                        eye.z + look.z * MUZZLE_FORWARD,
                        grab.bodyId(),
                        grab.anchorX(), grab.anchorY(), grab.anchorZ(),
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
