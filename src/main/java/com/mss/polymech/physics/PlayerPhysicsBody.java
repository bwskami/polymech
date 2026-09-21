package com.mss.polymech.physics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家的"双刚体"载体 —— 逐条照抄 space 0.1.3 的 {@code PhysicalEntity}。
 *
 * <p><b>为什么必须两个刚体</b>（space 的设计，不是我们的发明）：</p>
 * <ul>
 *   <li><b>主刚体（main）</b>：真正承载玩家位置的那个。它的速度每步被<b>强制写成</b>
 *       {@code sibling.linvel + ownVelocity}，所以它永远不会被接触"越推越快"，
 *       更不会像"累积冲量"那样在零重力下无限加速。</li>
 *   <li><b>兄弟刚体（sibling）</b>：同位置同尺寸的第二个盒子，速度每步被写成"玩家自己的输入"。
 *       它的作用是在接触里<b>量出"被携带的速度"</b> —— 站在移动的船上时，
 *       船的表面速度会把它推着走，于是下一步 {@code main = sibling + own} 就把玩家带上了船。
 *       这就是"站在船上被带着走"的正解，不需要任何"猜玩家在推什么"的启发式。</li>
 *   <li>两个盒子同位置同尺寸，靠碰撞组 {@code (2,5)} / {@code (5,5)} 互不作用
 *       （{@code 2 & 5 == 0}），否则求解器会把它们互相弹开。</li>
 * </ul>
 *
 * <p><b>碰撞箱永不旋转</b>：两个刚体都 {@code lockRotations(true)}（space 是每步
 * {@code setRotation(单位四元数) + setAngvel(0)}，锁自由度等效且更省）。旋转盒子的"角"
 * 扎进地形/船体后，求解器把角推出来的冲量方向很诡异 —— 那一整类问题由此消失。</p>
 *
 * <p><b>速度必须在 100Hz 的步进循环里重设</b>（{@link #afterStep}，由
 * {@link PhysicsStepThread} 的步进后回调驱动），不能等 20Hz 的 tick：
 * 一个 MC tick 里物理走 5 个子步，只在 tick 边界重设的话，子步之间速度会漂。</p>
 *
 * <p>参数取值全部照 space：每体 {@code 25 kg}、摩擦 {@code 20}、
 * contactSkin {@code 0.02}、弹性组合规则 {@code Min}、CCD 关、
 * 重力缩放 {@code 0}（<b>Rapier 不对玩家施加重力</b>，重力只由原版移动学提供）。</p>
 */
public final class PlayerPhysicsBody {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/PlayerBody");

    /** 每个刚体的质量：space 用 25 + 25（合计 50 kg）。 */
    public static final double BODY_MASS = 25.0;

    /** 格/tick → m/s：space 的 {@code PhysicalEntity.move} 用的就是 {@code mul(20.0)}。 */
    private static final double TICKS_PER_SECOND = 20.0;

    /** 主碰撞组（space 的 {@code colliderBody.setCollisionGroups(2, 5)}）。 */
    public static final int MAIN_MEMBERSHIP = 2;
    public static final int MAIN_FILTER = 5;
    /** 兄弟碰撞组（space 的 {@code siblingColliderBody.setCollisionGroups(5, 5)}）。 */
    public static final int SIBLING_MEMBERSHIP = 5;
    /**
     * <b>已回滚到照抄值 {@code 5}</b>（实验结论见下）。
     *
     * <p>实验（9/18 把 filter 改成 {@code -1}）的结果：<b>玩家开始自己向上飞</b> ——
     * 原因正是"左脚踩右脚"：</p>
     * <pre>
     *   原值 5： main(2,5) × sibling(5,5) → (2 & 5)=0 ✗  → 两体<b>不</b>互撞 ✓
     *   实验 -1： main(2,5) × sibling(5,-1) → (5 & 5)≠0 ✓ 且 (2 & -1)≠0 ✓ → 两体<b>互撞</b>
     * </pre>
     * 两个质量各 25kg、碰撞体完全重合的动态体一旦互撞，每子步把对方推开，
     * 而 ④ 又把它瞬移回重合位置 → 排斥速度被 ② 加进主刚体 → 自举升空。
     * 所以 {@code (5,5)} 这个"与任何东西都不互撞的自碰撞组"是 space <b>刻意</b>的设计，
     * 不是笔误。<b>别再动这个值。</b></p>
     *
     * <p><b>同时纠正我之前的一个算术错误</b>：我曾写"兄弟 {@code (5,5)} 与地形 {@code (1,-1)}
     * 因 {@code 1 & 5 == 0} 而不交互" —— <b>这是错的</b>：{@code 5 = 0b101}，
     * {@code 1 & 5 = 1 ≠ 0}，所以兄弟刚体<b>本来就会</b>和地形、物理体接触
     * （{@code 5} 恰好就是"地形 1 | 物理体 4"）。它并非"撞不到任何东西"。</p>
     */
    public static final int SIBLING_FILTER = 5;

    /** 世界句柄 → 该世界里的玩家刚体集合（步进线程会遍历它）。 */
    private static final Map<Long, Set<PlayerPhysicsBody>> BY_WORLD = new ConcurrentHashMap<>();

    private final UUID owner;
    private final long world;
    private long main = -1L;
    private long sibling = -1L;
    private double halfWidth;
    private double halfHeight;
    /** 实体原点（脚底）→ 刚体中心/碰撞箱中心的偏移；超人姿态下它是眼高（1.62），不等于半高。 */
    private double centerOffset;

    /** space 的 {@code ownVelocity}：**整体替换**而不是原地改，跨线程读才安全。 */
    private volatile double[] ownVelocity = new double[3];

    // 步进线程专用暂存（只有 afterStep 会碰）
    private final double[] scratchVel = new double[3];
    private final double[] scratchPos = new double[3];
    private final double[] scratchMainVel = new double[3];

    private PlayerPhysicsBody(UUID owner, long world) {
        this.owner = owner;
        this.world = world;
    }

    // ==================== 生命周期（主线程） ====================

    /**
     * 取（或创建）该玩家的双刚体；尺寸/中心偏移变了就重建两个碰撞体（保留刚体，不丢速度）。
     *
     * @return 不可用时返回 null
     */
    public static PlayerPhysicsBody getOrCreate(UUID owner, long world,
                                                double x, double y, double z,
                                                double halfWidth, double halfHeight, double centerOffset) {
        if (world <= 0 || !PhysicsNatives.isAvailable()) {
            return null;
        }
        Set<PlayerPhysicsBody> set = BY_WORLD.computeIfAbsent(world, k -> ConcurrentHashMap.newKeySet());
        PlayerPhysicsBody found = null;
        for (PlayerPhysicsBody candidate : set) {
            if (candidate.owner.equals(owner)) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            PlayerPhysicsBody created = new PlayerPhysicsBody(owner, world);
            if (!created.create(x, y, z, halfWidth, halfHeight, centerOffset)) {
                return null;
            }
            set.add(created);
            LOGGER.info("[PolyMech] 玩家双刚体已创建（主 {} / 兄弟 {}，质量 {} kg ×2，碰撞组 ({},{})/({},{})）",
                    created.main, created.sibling, BODY_MASS,
                    MAIN_MEMBERSHIP, MAIN_FILTER, SIBLING_MEMBERSHIP, SIBLING_FILTER);
            return created;
        }
        // 姿态切换（普通 0.6×1.8 ↔ 超人 0.6³）会改尺寸与中心偏移 → 必须重建碰撞体
        if (Math.abs(found.halfWidth - halfWidth) > 1.0E-6
                || Math.abs(found.halfHeight - halfHeight) > 1.0E-6
                || Math.abs(found.centerOffset - centerOffset) > 1.0E-6) {
            found.halfWidth = halfWidth;
            found.halfHeight = halfHeight;
            found.centerOffset = centerOffset;
            found.attachColliders();
        }
        return found;
    }

    /** 按 UUID 取（同一时刻一个玩家只在一个世界里）。 */
    public static PlayerPhysicsBody find(UUID owner) {
        for (Set<PlayerPhysicsBody> set : BY_WORLD.values()) {
            for (PlayerPhysicsBody body : set) {
                if (body.owner.equals(owner)) {
                    return body;
                }
            }
        }
        return null;
    }

    /** 取某世界里的（诊断用）。 */
    public static int countIn(long world) {
        Set<PlayerPhysicsBody> set = BY_WORLD.get(world);
        return set == null ? 0 : set.size();
    }

    private boolean create(double x, double y, double z,
                           double halfWidth, double halfHeight, double centerOffset) {
        this.halfWidth = halfWidth;
        this.halfHeight = halfHeight;
        this.centerOffset = centerOffset;
        // space: RigidBody(Type.DYNAMIC, pos, 单位四元数, mass=25, ..., lockRotations=true)
        // 注意：位置传的是"碰撞箱中心"（实体脚底 + centerOffset），与我们的既有约定一致。
        main = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC, x, y, z, 0, 0, 0, 1, BODY_MASS);
        sibling = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC, x, y, z, 0, 0, 0, 1, BODY_MASS);
        if (main <= 0 || sibling <= 0) {
            destroy();
            return false;
        }
        NativePhysics.bodyLockRotations(world, main, true);
        NativePhysics.bodyLockRotations(world, sibling, true);
        // space: rigidBody.setCCD(false)
        NativePhysics.bodyEnableCcd(world, main, false);
        NativePhysics.bodyEnableCcd(world, sibling, false);
        // space 的 PhysicalEntity 建体时 gravity_scale = 0.0：**Rapier 不对玩家施加重力**。
        // 玩家的垂直运动完全来自原版移动学（own = 位移 × 20，原版重力按维度倍率缩放），
        // 这样重力只有一个来源；否则会和 Rapier 的重力叠成双份（下沉 / 贴地抖动 / 发飘）。
        if (PhysicsNatives.hasTier1()) {
            NativePhysics.bodySetGravityScale(world, main, 0.0);
            NativePhysics.bodySetGravityScale(world, sibling, 0.0);
        }
        attachColliders();
        return true;
    }

    /** 重建两个碰撞体（尺寸变了才需要）；材质与碰撞组一并重套。 */
    private void attachColliders() {
        NativePhysics.bodyClearColliders(world, main);
        NativePhysics.bodyClearColliders(world, sibling);
        long mainCollider;
        long siblingCollider;
        if (PhysicsNatives.hasCollisionGroups()) {
            mainCollider = NativePhysics.colliderAttachCuboidGrouped(world, main,
                    halfWidth, halfHeight, halfWidth, PhysicsMaterials.PLAYER_FRICTION, 0.0,
                    MAIN_MEMBERSHIP, MAIN_FILTER);
            siblingCollider = NativePhysics.colliderAttachCuboidGrouped(world, sibling,
                    halfWidth, halfHeight, halfWidth, PhysicsMaterials.PLAYER_FRICTION, 0.0,
                    SIBLING_MEMBERSHIP, SIBLING_FILTER);
        } else {
            // 原生层太旧：退化成"两个同组盒子"（会互相弹开），至少不会让物理层不可用
            mainCollider = NativePhysics.colliderAttachCuboid(world, main,
                    halfWidth, halfHeight, halfWidth, PhysicsMaterials.PLAYER_FRICTION, 0.0);
            siblingCollider = NativePhysics.colliderAttachCuboid(world, sibling,
                    halfWidth, halfHeight, halfWidth, PhysicsMaterials.PLAYER_FRICTION, 0.0);
        }
        PhysicsMaterials.applyPlayer(world, mainCollider);
        PhysicsMaterials.applyPlayer(world, siblingCollider);
    }

    /** 销毁（下线/切维度/停用物理时）。 */
    public void destroy() {
        Set<PlayerPhysicsBody> set = BY_WORLD.get(world);
        if (set != null) {
            set.remove(this);
            if (set.isEmpty()) {
                BY_WORLD.remove(world);
            }
        }
        if (main > 0) {
            NativePhysics.bodyDestroy(world, main);
        }
        if (sibling > 0) {
            NativePhysics.bodyDestroy(world, sibling);
        }
        main = -1L;
        sibling = -1L;
    }

    /** 服务端停止：清空全部。 */
    public static void clear() {
        for (Set<PlayerPhysicsBody> set : BY_WORLD.values()) {
            for (PlayerPhysicsBody body : set) {
                if (body.main > 0) {
                    NativePhysics.bodyDestroy(body.world, body.main);
                }
                if (body.sibling > 0) {
                    NativePhysics.bodyDestroy(body.world, body.sibling);
                }
                body.main = -1L;
                body.sibling = -1L;
            }
        }
        BY_WORLD.clear();
    }

    // ==================== 每 tick（主线程） ====================

    /**
     * 记下玩家本 tick 的位移（照 space 的 {@code PhysicalEntity.move}）。
     *
     * <p>space 在这里还会 {@code setRotation(单位四元数) + setAngvel(0)}；我们在建体时
     * 就 {@code lockRotations(true)} 了，角自由度已锁死，那两行是等价的冗余操作，故省掉。</p>
     *
     * @param dx/dy/dz 本 tick 的原版位移（格）
     */
    public void move(double dx, double dy, double dz) {
        if (main <= 0 || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return;
        }
        // 整体替换：步进线程读的是引用，原地改会有撕裂读
        ownVelocity = new double[]{
                dx * TICKS_PER_SECOND, dy * TICKS_PER_SECOND, dz * TICKS_PER_SECOND};
        // space 的 move() 每步还把主刚体姿态复位成单位四元数、角速度清零。
        // 我们在建体时已 lockRotations(true)，理论上这两句冗余；照抄成本极低，
        // 且万一旋转自由度被别的路径解开（改刚体类型 / 存档恢复），姿态也不会慢慢漂。
        NativePhysics.bodySetRotation(world, main, 0.0, 0.0, 0.0, 1.0, true);
        NativePhysics.bodySetAngvel(world, main, 0.0, 0.0, 0.0);
    }

    /** 读主刚体位置（碰撞箱中心）到 out。 */
    public boolean readPosition(double[] out) {
        return main > 0 && NativePhysics.bodyReadTranslation(world, main, out);
    }

    /**
     * 读**兄弟**刚体位置（碰撞箱中心）到 out —— 供 {@code PlayerColliderRender} 画出"第二个盒子"。
     *
     * <p>正常时它与主刚体完全重合（两者互不相撞、每子步 ④ 被搬回主刚体位置）；
     * 只有"被环境带着走"的那一子步才会错开一点，那正是它能测出载速的原因。</p>
     */
    public boolean readSiblingPosition(double[] out) {
        return sibling > 0 && NativePhysics.bodyReadTranslation(world, sibling, out);
    }

    /** 主刚体线速度。 */
    public boolean readVelocity(double[] out) {
        return main > 0 && NativePhysics.bodyReadVelocity(world, main, out);
    }

    /** 实体原点（脚底）→ 刚体中心的偏移。 */
    public double centerOffset() {
        return centerOffset;
    }

    public double halfWidth() {
        return halfWidth;
    }

    public double halfHeight() {
        return halfHeight;
    }

    public long mainHandle() {
        return main;
    }

    public long siblingHandle() {
        return sibling;
    }

    public UUID owner() {
        return owner;
    }

    /**
     * 位置交回原版时（创造飞行 / 服务端采纳上报位置）：把两个刚体搬到指定位置。
     *
     * @param vx/vy/vz 一并写入的速度（0 表示清零）
     */
    public void teleport(double x, double y, double z, double vx, double vy, double vz) {
        if (main <= 0) {
            return;
        }
        NativePhysics.bodySetTranslation(world, main, x, y, z);
        NativePhysics.bodySetTranslation(world, sibling, x, y, z);
        NativePhysics.bodySetMotion(world, main, vx, vy, vz, 0.0, 0.0, 0.0, true);
        NativePhysics.bodySetMotion(world, sibling, vx, vy, vz, 0.0, 0.0, 0.0, true);
    }

    // ==================== 每步（步进线程，100Hz） ====================

    /**
     * 某个世界步进一次之后调用：对所有玩家跑 space 的 {@code afterStep()} 速度继承链。
     *
     * <p><b>只碰原生刚体</b>，不访问任何 Minecraft 对象 —— 它在物理步进线程上跑。</p>
     */
    public static void afterStep(long world) {
        Set<PlayerPhysicsBody> set = BY_WORLD.get(world);
        if (set == null || set.isEmpty()) {
            return;
        }
        for (PlayerPhysicsBody body : set) {
            body.velocityChain();
        }
    }

    private void velocityChain() {
        if (main <= 0 || sibling <= 0) {
            return;
        }
        diag();          // 轻量诊断：只在"看起来不对劲"时打印一行（可长期保留）
        twitchDiag();    // 抽搐检测：接触里法向速度反复换向时打印幅度与频率
        // ① 兄弟刚体经过上一步的接触之后的速度 = "被环境带着走"的速度
        if (!NativePhysics.bodyReadVelocity(world, sibling, scratchVel)) {
            return;
        }
        double[] own = ownVelocity;
        // ② main.linvel = sibling.linvel + own —— **原样相加，不做任何缩放 / 夹持**。
        //
        // 这是 space 不弹人的关键：**兄弟体不是"被挡住检测器"，而是"对消伙伴"**。
        // 顶着接触时它量到的是**被解算器顶回来的速度**（≈ −own），于是 `sibling + own ≈ 0`
        // —— 稳态由这一对**对消**得到，不是靠"少注入"。
        //
        // 2026-09-19 实测（用户拍板"偏离一下"）：把 `own` 按"环境放行比例"缩放
        // （`k = clamp(sibling/own, 0, 1)`，想模仿原版 `Entity.move:677` 的"被挡轴清零"）
        // **结果反而翻倍**：K 节位置偏离 7.6/26.5/72 → 16.3/54.5/158 mm，
        // 顶墙末态速度 4.317（1×own）→ **8.634（2×own）**。
        // 原因就是上面那句：把 own 缩放掉 = **把对消项拆了** ⇒ 2×own 直接砸进接触。
        // ⇒ 这条链"原样相加"不是随意，是被这个对消结构定死的，**别再动它**。
        double tx = scratchVel[0] + own[0];
        double ty = scratchVel[1] + own[1];
        double tz = scratchVel[2] + own[2];
        // space 的写入条件（`delta.lengthSquared() > 1.0E-8` 才写）：
        // 数值没变就不唤醒 —— 静置的刚体才能真正睡下去，求解器不会每子步都去解它。
        // （读不到当前速度时 space 也不写，原样保留旧速度。）
        if (NativePhysics.bodyReadVelocity(world, main, scratchMainVel)) {
            double dx = tx - scratchMainVel[0];
            double dy = ty - scratchMainVel[1];
            double dz = tz - scratchMainVel[2];
            if (dx * dx + dy * dy + dz * dz > WRITE_EPSILON_SQ) {
                NativePhysics.bodySetMotion(world, main, tx, ty, tz, 0.0, 0.0, 0.0, true);
            }
        }
        // ③ sibling.linvel = 玩家自己的输入（space 原样保留这句）。
        //
        // **这一句是必须的**：兄弟刚体每子步都会被④搬回主刚体的位置，搬动/接触会产生
        // 穿透冲量，不清零的话这些噪声会留在兄弟速度里，被 ② 加到主刚体上 ——
        // 表现就是"玩家移动发飘、被莫名加速"。清零 = 每子步只保留"这一子步量到的载速"。
        //
        // 代价（实测）：载速因此被限制在单个子步的摩擦预算 μ·g·dt 内
        //（5 m/s 平台上玩家约 1.0–1.7 m/s，慢于平台会被落下）。这是 space 的设计取舍，
        // 先照抄；真要"完全同步的载速"需要另想办法（不能靠不清零，那会重新引入噪声）。
        NativePhysics.bodySetMotion(world, sibling, own[0], own[1], own[2], 0.0, 0.0, 0.0, true);
        // ④ sibling 贴住 main 的位置（space: siblingBody.setPos(rigidBody.getPos())）
        if (NativePhysics.bodyReadTranslation(world, main, scratchPos)) {
            NativePhysics.bodySetTranslation(world, sibling,
                    scratchPos[0], scratchPos[1], scratchPos[2]);
        }
    }

    /**
     * 写回 main 速度阈值 —— space 的 {@code delta.lengthSquared() > 1.0E-8}。
     * <p>目标速度与当前速度差得比它小就不写：既省掉无意义的原生调用，
     * 也让静置的刚体不再被每子步唤醒（唤醒 = 求解器继续解它 = 接触噪声被反复喂回去）。</p>
     */
    private static final double WRITE_EPSILON_SQ = 1.0e-8;

    // ==================== 轻量诊断（可长期保留，不是"临时插桩"） ====================

    /**
     * 只在"看起来不对劲"时打印一行；正常情况**完全静默**。
     *
     * <p>判据（任一命中）：</p>
     * <ul>
     *   <li>{@code |链前v| > }{@value #DIAG_SPEED} m/s —— 步行 ~4.3、疾跑 ~5.6，超过它基本就是被弹/被拽；</li>
     *   <li>单个 10ms 子步的位移 > {@value #DIAG_STEP_DP} 格（= 20 m/s）—— 位置被瞬移。</li>
     * </ul>
     *
     * <p>节流 {@value #DIAG_INTERVAL_MS} ms，避免弹飞瞬间刷屏。
     * 之所以值得长期留着：这三行字段（{@code v前 / 载速 / own}）是区分
     * "链子写进去的" vs "求解器给的" vs "原版输入"的唯一现场证据 ——
     * 第 22 节那个根因就是靠它们（加上 {@code own} 向量）从用户日志里定出来的。</p>
     */
    private static final double DIAG_SPEED = 15.0;
    private static final double DIAG_STEP_DP = 0.2;
    private static final long DIAG_INTERVAL_MS = 500L;
    private static long diagLastMs;
    private static boolean diagHasLast = false;
    private static double diagLastX;
    private static double diagLastY;
    private static double diagLastZ;

    /** 在步进线程上跑：只碰原生刚体，不访问任何 Minecraft 对象。 */
    private void diag() {
        boolean haveV = NativePhysics.bodyReadVelocity(world, main, scratchMainVel);
        boolean haveP = NativePhysics.bodyReadTranslation(world, main, scratchPos);
        if (!haveV && !haveP) {
            return;
        }
        double speed = haveV ? Math.sqrt(sq(scratchMainVel[0]) + sq(scratchMainVel[1]) + sq(scratchMainVel[2])) : 0.0;
        double step = (haveP && diagHasLast)
                ? Math.sqrt(sq(scratchPos[0] - diagLastX) + sq(scratchPos[1] - diagLastY) + sq(scratchPos[2] - diagLastZ))
                : Double.NaN;
        if (haveP) {
            diagLastX = scratchPos[0];
            diagLastY = scratchPos[1];
            diagLastZ = scratchPos[2];
            diagHasLast = true;
        }
        if (!(haveV && speed > DIAG_SPEED) && !(Double.isFinite(step) && step > DIAG_STEP_DP)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - diagLastMs < DIAG_INTERVAL_MS) {
            return;
        }
        diagLastMs = now;
        double siblingSpeed = NativePhysics.bodyReadVelocity(world, sibling, scratchVel)
                ? Math.sqrt(sq(scratchVel[0]) + sq(scratchVel[1]) + sq(scratchVel[2])) : 0.0;
        double[] own = ownVelocity;
        LOGGER.info("[玩家物理诊断] world={} 玩家={} |v|={} 子步Δpos={} v=({},{},{}) 载速=({},{},{}) own=({},{},{}) 载速|v|={}",
                world, owner, fmt(speed), Double.isFinite(step) ? fmt(step) : "n/a",
                fmt(scratchMainVel[0]), fmt(scratchMainVel[1]), fmt(scratchMainVel[2]),
                fmt(scratchVel[0]), fmt(scratchVel[1]), fmt(scratchVel[2]),
                fmt(own[0]), fmt(own[1]), fmt(own[2]), fmt(siblingSpeed));
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ---------- 抽搐检测：主刚体的**速度**是否在窗口内反复换向（锯齿/来回甩） ----------

    /**
     * "抽搐"的正确判据 —— 第一版量的是**位置差**，结果被亚毫米级解算噪声骗了
     * （某次会话打出 73 条全是 {@code |v|=0.00}、幅度还累加成 7 格，因为忘了每个窗口重置 min/max）。
     *
     * <p>真正的症状在**速度**上：日志里那条失控是 {@code own}（= 原版 movement）在零重力里
     * 以 {@code D ← 0.98·D + c} 爬到 4.88 格/tick（≈ −97 m/s）再被清零，形成 ~1Hz 的锯齿 ——
     * 玩家身上就是被反复甩的"抽搐"。所以这里按子步统计主刚体线速度：</p>
     * <ul>
     *   <li>每 {@value #TWITCH_WINDOW} 个子步（0.5 秒）复位一次三轴 min/max；</li>
     *   <li>沿极差最大的那一轴统计**速度换向**次数；</li>
     *   <li>只有"极差 &gt; {@value #TWITCH_MIN_RANGE} m/s **且** 换向 ≥ {@value #TWITCH_MIN_FLIPS} 次"
     *       才打印 —— 数值噪声（≈0）与匀速运动都不会触发。</li>
     * </ul>
     */
    private static final int TWITCH_WINDOW = 50;
    private static final int TWITCH_MIN_FLIPS = 4;
    private static final double TWITCH_MIN_RANGE = 2.0;
    /**
     * 位置极差下限（格）：低于它就是"看不出来"的接触微抖，不打印。
     *
     * <p>标定来自 `PlayerWallProbeTest` K 节：顶住接触时位置极差 ≈ `own × dt` 的两倍，
     * own=1 → ~15mm、own=4.317（走路）→ ~53mm、own=12 → ~145mm。取 10mm 作门槛 ⇒
     * 走路及以上会报，极慢速顶靠不报（那一档本来也看不见）。</p>
     */
    private static final double TWITCH_MIN_POS_RANGE = 0.010;
    /** 日志节流：这类抖动会连续发生，1 秒一条足够看趋势。 */
    private static final long TWITCH_LOG_INTERVAL_MS = 1000L;

    private static int twitchCount;
    private static int twitchFlips;
    private static long twitchLastMs;
    private static int twitchPrevSign;
    private static double twitchPrevValue;
    private static final double[] twitchVMin = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
    private static final double[] twitchVMax = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
    private static final double[] twitchPMin = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
    private static final double[] twitchPMax = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};

    private void twitchDiag() {
        double[] v = new double[3];
        double[] p = new double[3];
        if (!NativePhysics.bodyReadVelocity(world, main, v)) {
            return;
        }
        boolean haveP = NativePhysics.bodyReadTranslation(world, main, p);
        for (int i = 0; i < 3; i++) {
            twitchVMin[i] = Math.min(twitchVMin[i], v[i]);
            twitchVMax[i] = Math.max(twitchVMax[i], v[i]);
            if (haveP) {
                twitchPMin[i] = Math.min(twitchPMin[i], p[i]);
                twitchPMax[i] = Math.max(twitchPMax[i], p[i]);
            }
        }
        int axis = 0;
        double range = -1.0;
        for (int i = 0; i < 3; i++) {
            double r = twitchVMax[i] - twitchVMin[i];
            if (r > range) {
                range = r;
                axis = i;
            }
        }
        int sign = Double.compare(v[axis], 0.0);
        if (sign != 0 && twitchPrevSign != 0 && sign != twitchPrevSign
                && Math.abs(v[axis] - twitchPrevValue) > 0.05) {
            twitchFlips++;
        }
        twitchPrevSign = sign;
        twitchPrevValue = v[axis];
        twitchCount++;
        if (twitchCount < TWITCH_WINDOW) {
            return;
        }
        int flips = twitchFlips;
        twitchCount = 0;
        twitchFlips = 0;
        for (int i = 0; i < 3; i++) {
            twitchVMin[i] = Double.MAX_VALUE;
            twitchVMax[i] = -Double.MAX_VALUE;
            twitchPMin[i] = Double.MAX_VALUE;
            twitchPMax[i] = -Double.MAX_VALUE;
        }
        twitchPrevSign = 0;
        double posRange = Math.max(Math.max(twitchPMax[0] - twitchPMin[0], twitchPMax[1] - twitchPMin[1]),
                twitchPMax[2] - twitchPMin[2]);
        if (flips < TWITCH_MIN_FLIPS || range < TWITCH_MIN_RANGE || posRange < TWITCH_MIN_POS_RANGE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - twitchLastMs < TWITCH_LOG_INTERVAL_MS) {
            return;
        }
        twitchLastMs = now;
        double[] sib = new double[3];
        NativePhysics.bodyReadVelocity(world, sibling, sib);
        double[] own = ownVelocity;
        LOGGER.info("[玩家物理抽搐] world={} 玩家={} 0.5s 内速度换向 {}/{} 次 速度极差={} m/s（轴={}）"
                        + " 位置极差={} 格({} mm) 当前v=({},{},{}) 载速=({},{},{}) own=({},{},{})",
                world, owner, flips, TWITCH_WINDOW, fmt(range), "xyz".charAt(axis),
                fmt(posRange), fmt(posRange * 1000.0),
                fmt(v[0]), fmt(v[1]), fmt(v[2]),
                fmt(sib[0]), fmt(sib[1]), fmt(sib[2]),
                fmt(own[0]), fmt(own[1]), fmt(own[2]));
    }
}
