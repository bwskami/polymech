package com.mss.polymech.client.physics;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.physics.NativePhysics;
import com.mss.polymech.physics.PhysicsDrivenPlayers;
import com.mss.polymech.physics.PhysicsGroundProbe;
import com.mss.polymech.physics.PhysicsMaterials;
import com.mss.polymech.physics.PhysicsNatives;
import com.mss.polymech.physics.PhysicsShapes;
import com.mss.polymech.physics.PhysicsStepThread;
import com.mss.polymech.physics.PlayerPhysicsBody;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/**
 * 客户端物理世界：让玩家真的活在物理里（对应 space 0.1.0 的 MPS 客户端半边）。
 *
 * <p>与"先原版移动、再把人推出去"的做法不同，这里采用 space 的思路：
 * <b>原版算出的位移不再直接应用，而是转成力交给刚体，位置从刚体读回</b>。</p>
 *
 * <p>为此客户端必须自带一套碰撞世界，包含三部分：</p>
 * <ol>
 *   <li><b>地形</b>：把玩家周围区块体素化成固定碰撞体（否则位置来自物理的玩家会直接穿过地面）；</li>
 *   <li><b>飞船/建筑</b>：把服务端同步来的方块快照建成<b>运动学</b>刚体（位置由服务端权威，客户端只负责碰撞）；</li>
 *   <li><b>玩家</b>：动态刚体（质量 50、旋转自由），受"原版位移转成的力"驱动。</li>
 * </ol>
 *
 * <p><b>力模型（照抄 space 0.1.0 / MPS）</b>：{@code rigidBody.applyForce(new Force(delta × 70.0, 0.05))}，
 * 质量 50kg、<b>无阻尼</b>。原版每 tick 算出的位移被当成一个持续 0.05s 的力，
 * 刚体自己积分出速度与位置，位置再读回实体 —— 所以速度<b>只累加不衰减</b>：
 * 按住 W 持续加速，松开键会一直漂（这就是太空手感）。
 * 绝不能再叠加"速度伺服"（把速度强行拉到原版值），那会把惯性整条抹掉。</p>
 *
 * <p><b>安全阀</b>：只有当"玩家所在区块的地形碰撞体已就绪"时才接管移动，
 * 否则回退原版移动（返回 false），避免掉出世界。</p>
 */
public final class ClientPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/Client");

    /** 物理子步：每客户端 tick (1/20s) 走 5 个 1/100s 子步 → 100Hz。 */
    private static final int SUBSTEPS = 5;
    private static final double DT = 1.0 / 100.0;
    private static final double TICK_SECONDS = 1.0 / 20.0;
    private static final double PLAYER_MASS = 50.0;

    /**
     * MPS 力系数：{@code F = delta × 70.0}（牛顿），delta 为原版本 tick 位移（格）。
     * <p>对应 space 的 {@code PhysicalEntity.move()}：{@code applyForce(new Force(delta.mul(70.0), 0.05))}。</p>
     */
    private static final double MPS_FORCE = 70.0;
    /** 该力的作用时长（秒）：MPS 的 0.05s = 5 × 1/100s 子步，正好一个 MC tick）。 */
    private static final double MPS_FORCE_SECONDS = 0.05;
    /**
     * 每 tick 施加的等价冲量系数 = 70 × 0.05 = 3.5（N·s per 格位移）。
     * <p>Δv = 3.5·delta/50 = 0.07·delta，与"持续 0.05s"在无阻尼下完全等价，
     * 且不依赖"下一步何时步进"，不会像持续力那样在 move 少调一次时泄漏。</p>
     */
    private static final double MPS_IMPULSE = MPS_FORCE * MPS_FORCE_SECONDS;

    /** 地形体素化的垂直带宽（关注点上下），与服务器侧一致。 */
    private static final int VERTICAL_BAND = 64;
    /** 地形重建半径（区块）。 */
    private static final int TERRAIN_RADIUS = 1;

    /**
     * 物理体碰撞组：与 space 0.1.3 的 {@code PhysicalWorld.addPhysicalBodyCollider} 一致
     * （{@code setCollisionGroups(4, -1)}）。玩家主碰撞体是 {@code (2,5)}，
     * 与 4 相与非零 ⇒ 玩家与物理体正常碰撞。
     */
    private static final int BODY_MEMBERSHIP = 4;
    private static final int BODY_FILTER = -1;

    private static long world = 0;
    private static ResourceKey<Level> worldDimension = null;

    private static long playerBody = 0;
    private static float playerHalfWidth = 0;
    /** 碰撞体半高（普通 0.9 / 超人 0.3）：只决定碰撞盒<b>尺寸</b>。 */
    private static float playerHalfHeight = 0;
    /**
     * 实体原点 → 刚体中心的竖直偏移（普通 = 半高 0.9 / 超人 = 眼高 0.4）。
     * <p>建体 {@code +playerCenterOffset}、回写 {@code -playerCenterOffset} 必须成对使用；
     * 超人姿态下它与半高不同（头盒中心在头部，只包头脸），因此不能再用半高做回写。</p>
     */
    private static double playerCenterOffset = 0.9;
    /** 姿态伺服：每帧把角速度朝目标拉的比例 / 姿态误差→目标角速度的刚度（1/s）。 */
    private static final double ROT_SERVO_ALPHA = 0.4;
    private static final double ROT_STIFFNESS = 8.0;

    // ── 临时诊断状态（量姿态更新率用，定位完就删） ──
    private static long polymech$lastRenderNanos = 0L;
    private static long diagLastNanos = 0L;
    private static int diagFrames = 0;
    private static int diagMovedFrames = 0;
    private static double diagMaxPhysStep = 0.0;
    private static double diagMaxTargetStep = 0.0;
    private static double diagSumPhysStep = 0.0;
    private static final org.joml.Quaternionf diagPrevPhys = new org.joml.Quaternionf();
    private static final org.joml.Quaternionf diagPrevTarget = new org.joml.Quaternionf();
    private static boolean diagInit = false;

    private static void polymech$diag(org.joml.Quaternionf target) {
        long now = System.nanoTime();
        com.mss.polymech.space.SpacePlayerData diagData =
                com.mss.polymech.space.SpacePlayerData.get(Minecraft.getInstance().player);
        org.joml.Quaternionf rendered = new org.joml.Quaternionf();
        boolean hasRendered = diagData != null && diagData.bodyQuatForRender(
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false), rendered) != null;
        if (!diagInit) {
            diagInit = true;
            diagLastNanos = now;
            if (hasRendered) {
                diagPrevPhys.set(rendered);
            }
            diagPrevTarget.set(target);
            return;
        }
        diagFrames++;
        double renderStep = hasRendered
                ? polymech$angleDeg(diagPrevPhys, rendered.x, rendered.y, rendered.z, rendered.w) : 0.0;
        double targetStep = polymech$angleDeg(diagPrevTarget, target.x, target.y, target.z, target.w);
        if (renderStep > 0.02) {
            diagMovedFrames++;
        }
        diagMaxPhysStep = Math.max(diagMaxPhysStep, renderStep);
        diagMaxTargetStep = Math.max(diagMaxTargetStep, targetStep);
        diagSumPhysStep += renderStep;
        if (hasRendered) {
            diagPrevPhys.set(rendered);
        }
        diagPrevTarget.set(target);
        if (now - diagLastNanos >= 1_000_000_000L) {
            LOGGER.info("[PolyMech/Diag] {}/s 帧，其中 {} 帧渲染姿态有变化 | 渲染姿态每帧最大 {}° 平均 {}° | 目标每帧最大 {}° | 物理姿态{} 渲染姿态{}",
                    diagFrames, diagMovedFrames,
                    String.format("%.2f", diagMaxPhysStep),
                    String.format("%.3f", diagFrames == 0 ? 0.0 : diagSumPhysStep / diagFrames),
                    String.format("%.2f", diagMaxTargetStep),
                    diagData != null && diagData.hasPhysicalBodyQuat() ? "有" : "无",
                    hasRendered ? "有" : "无");
            diagFrames = 0;
            diagMovedFrames = 0;
            diagMaxPhysStep = 0.0;
            diagMaxTargetStep = 0.0;
            diagSumPhysStep = 0.0;
            diagLastNanos = now;
        }
    }

    private static double polymech$angleDeg(org.joml.Quaternionf a, float x, float y, float z, float w) {
        double dot = Math.abs(a.x * x + a.y * y + a.z * z + a.w * w);
        dot = Math.min(1.0, Math.max(-1.0, dot));
        return Math.toDegrees(2.0 * Math.acos(dot));
    }

    private static final Map<Long, Long> terrainBodies = new HashMap<>();
    private static final Set<Long> terrainDirty = new HashSet<>();
    private static long lastTerrainChunk = Long.MIN_VALUE;
    private static boolean takeoverLogged = false;
    /**
     * 本 tick {@link #drive} 是否真的接管了位置。
     *
     * <p>用于给"每 tick / 每帧回写"加一道门：原版在骑乘、睡觉等情况下根本不会走到
     * {@code Entity.move}，这时若还按刚体位置回写，就会把玩家从载具里硬拽出来。
     * 没接管过就不回写，位置完全交回原版。</p>
     */
    private static boolean droveThisTick = false;
    /** 最近一次看起来正常的位置（防止物理异常时掉出世界）。 */
    private static double safeX, safeY, safeZ;
    private static boolean hasSafe = false;
    private static int terrainCenterY = 64;
    /**
     * 上一批体素是按哪个 {@link #terrainCenterY} 裁的。
     *
     * <p>区块里的体素只覆盖"玩家当时的 Y ± {@link #VERTICAL_BAND}"这一条带，
     * 所以玩家竖直方向移动会让旧带留出空洞。原来的代码用<b>水平</b>跨区块
     * （{@code moved}）当"要不要重裁"的判据，竖直移动完全不判 —— 那是个漏洞；
     * 修法见 {@link #updateTerrain}。</p>
     */
    private static int lastTerrainBandY = Integer.MIN_VALUE;
    /** 竖直带移动这么多格才值得重裁（带半径 64，余量足够，不必每格重来）。 */
    private static final int BAND_REBUILD_STEP = 16;
    /** 单次地形体素化超过它就记 WARN（这是"卡死"的直接取证线）。 */
    private static final long TERRAIN_SLOW_MS = 50L;

    private static final Map<Long, ShipBody> ships = new java.util.concurrent.ConcurrentHashMap<>();

    // ── 已删除：reportPushIfBlocked / LAST_PUSH / PUSH_INTERVAL ──
    //
    // 原来的做法是"我想走、但实际水平速度不到期望的一半 ⇒ 我一定在推东西 ⇒
    // 把推力发给最近的物理体"。**这个判据是错的**：
    // 玩家站在正在移动的船上时，相对速度本来就接近 0，于是它每次按键都把推力
    // 发给脚下那条船、方向就是按键方向 —— 现象就是"站在船上的玩家把船往前推着走"。
    //
    // space 0.1.3 里**完全没有**这套启发式：船由服务端权威，客户端只是镜像，
    // 玩家的接触通过"兄弟刚体"的接触解算自然传递，没有"猜玩家在推什么"这一步。
    // 我们照它办：删掉启发式（`PhysicsBodyPushPacket` 保留，供将来做**显式**推拉键用）。

    /**
     * 客户端镜像刚体（逐字照 space 0.1.3 的 {@code ClientPhysicalBody}）。
     *
     * <p>它是 <b>{@code KINEMATIC_POSITION}</b> 刚体：无限质量，本地谁也推不动；
     * 位置只由服务端位姿驱动，姿态直接取同步值。space 的字段对应关系：
     * {@code syncFrom}/{@code syncTo}/{@code syncProgress} ↔ 这里的同名字段，
     * 推进逻辑在 {@link #stepShipSync}。</p>
     */
    private static final class ShipBody {
        final long body;
        final int blockCount;
        /** 插值起点（世界坐标）—— space 的 {@code syncFrom}。 */
        volatile double[] syncFrom;
        /** 插值终点 = 服务端最新位姿 —— space 的 {@code syncTo}。 */
        volatile double[] syncTo;
        /** 0→1，每物理子步 +{@link #SHIP_SYNC_STEP} —— space 的 {@code syncProgress}。 */
        volatile double progress = 1.0;

        ShipBody(long body, int blockCount, double x, double y, double z) {
            this.body = body;
            this.blockCount = blockCount;
            this.syncFrom = new double[]{x, y, z};
            this.syncTo = new double[]{x, y, z};
        }
    }

    /**
     * 运动学插值步长：space 的 {@code ClientPhysicalBody.MOVE_STEP = 0.2}。
     * <p>配合 100Hz 步进，一个 20Hz 服务端包会在 5 个子步内被平滑走完。</p>
     */
    private static final double SHIP_SYNC_STEP = 0.2;

    /**
     * 每个物理子步推进一次所有镜像体的运动学目标 —— space 的
     * {@code ClientPhysicalBody.tickMovePos}（注册在 {@code PhysicalWorld} 的 tick listener 上）。
     *
     * <p>在<b>步进线程</b>上跑：只碰原生刚体与 volatile 字段，不访问 Minecraft 对象。
     * 必须用 {@code setNextKinematicTranslation}（而不是 {@code setTranslation}）：
     * 前者求解器能读出运动速度，站在船上的玩家才会被带走。</p>
     */
    private static void stepShipSync(long worldHandle) {
        if (worldHandle <= 0) {
            return;
        }
        for (ShipBody ship : ships.values()) {
            double p = ship.progress;
            if (p >= 1.0) {
                continue;
            }
            p = Math.min(1.0, p + SHIP_SYNC_STEP);
            double[] a = ship.syncFrom;
            double[] b = ship.syncTo;
            NativePhysics.bodySetNextKinematicTranslation(worldHandle, ship.body,
                    a[0] + (b[0] - a[0]) * p,
                    a[1] + (b[1] - a[1]) * p,
                    a[2] + (b[2] - a[2]) * p);
            ship.progress = p;
        }
    }

    /**
     * 读取客户端刚体**当前**的变换（渲染用，照 MPS：直接取当前状态，不做插值）。
     *
     * <p>步进由 {@link PhysicsStepThread} 按 10ms 独立推进，状态是***100Hz 更新的，
     * 所以渲染直接取当前值就够平滑；在两个 20Hz 网络包之间插值反而只有 20Hz 的信息量。</p>
     *
     * @return true 表示读到了（否则调用方退回按包插值）
     */
    public static boolean liveTransform(long bodyId, double[] posOut, float[] rotOut) {
        long w = world;
        if (w <= 0) {
            return false;
        }
        ShipBody ship = ships.get(bodyId);
        if (ship == null) {
            return false;
        }
        double[] q = new double[4];
        if (!NativePhysics.bodyReadTranslation(w, ship.body, posOut)
                || !NativePhysics.bodyReadRotation(w, ship.body, q)) {
            return false;
        }
        rotOut[0] = (float) q[0];
        rotOut[1] = (float) q[1];
        rotOut[2] = (float) q[2];
        rotOut[3] = (float) q[3];
        return true;
    }

    /**
     * 已删除：{@code MOTION_DEADBAND} / {@code sameMotion} / {@code motionOf} /
     * {@code POSITION_CORRECTION}。
     *
     * <p>那些都是为了"客户端 DYNAMIC 镜像体自己积分、再按死区/阈值纠正"服务的。
     * 镜像体改成 {@code KINEMATIC_POSITION} 之后（space 的 {@code ClientPhysicalBody}），
     * 本地不再积分，位置完全由服务端的运动学目标决定 —— 死区、位置修正这一整类补丁
     * 连同它们引入的"拽回冲量"一起消失。</p>
     */

    private ClientPhysics() {
    }

    /** 客户端物理是否可用（原生库已加载且世界已创建）。 */
    public static boolean available() {
        return PhysicsNatives.isAvailable() && world > 0;
    }

    /**
     * 玩家双刚体（渲染/诊断用；未接管时为 null）。
     *
     * <p>供 {@code PlayerColliderRender} 把主/兄弟两个盒子画成线框 —— 在此之前，
     * "双刚体到底在哪"只能靠日志里的句柄回答，肉眼是看不到的。</p>
     */
    public static PlayerPhysicsBody playerRig() {
        return playerRig;
    }

    /**
     * 客户端物理世界句柄（供 {@code LevelAssist} 用原生射线取"脚下地板面"）。
     * 未就绪返回 0。
     */
    public static long worldHandle() {
        return world;
    }

    /** 通知某区块的方块变了（由 LevelChunk 变更 mixin 调用）。 */
    public static void markChunkDirty(long chunkKey) {
        if (terrainBodies.containsKey(chunkKey)) {
            terrainDirty.add(chunkKey);
        }
    }

    /** 退出世界/切换维度时清理。 */
    public static void shutdown() {
        for (ShipBody ship : ships.values()) {
            if (world > 0) {
                NativePhysics.bodyDestroy(world, ship.body);
            }
        }
        ships.clear();
        for (long body : terrainBodies.values()) {
            if (body > 0 && world > 0) {
                NativePhysics.bodyDestroy(world, body);
            }
        }
        terrainBodies.clear();
        terrainDirty.clear();
        if (playerRig != null) {
            PhysicsDrivenPlayers.unmark(playerRig.owner());
            playerRig.destroy();
            playerRig = null;
        }
        playerBody = 0;
        if (world > 0) {
            PhysicsStepThread.remove(world);
            NativePhysics.worldDestroy(world);
        }
        world = 0;
        worldDimension = null;
        lastTerrainChunk = Long.MIN_VALUE;
        takeoverLogged = false;
        hasSafe = false;
        lastPlayerMode = "";
    }

    /**
     * 每客户端 tick（{@code ClientTickEvent.Pre}）调用：维护物理世界并步进。
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        // 每 tick 先清"本 tick 真接管过"，由 drive() 成功时置位（见 droveThisTick）。
        droveThisTick = false;
        // 单机暂停：ClientTickEvent 仍然每 tick 照发（Minecraft.tick() → fireClientTickPre()），
        // 但实体已经不 tick 了。此时若继续步进物理世界，刚体会带着暂停前的速度继续积分，
        // 恢复游戏时位置就被改写（表现：暂停一会儿回来，人自己飘走了）。
        // Minecraft.isPaused() 只在"单机 + 暂停菜单 + 未开局域网"时为真，
        // 联机 / 已发布局域网不受影响。
        if (mc.isPaused()) {
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            if (world > 0) {
                shutdown();
            }
            return;
        }
        if (!PhysicsNatives.isAvailable()) {
            return;
        }
        if (!shouldSimulate(player)) {
            if (world > 0) {
                shutdown();
            }
            noteMode("关闭（非太空维度，且 64 格内没有物理体）");
            return;
        }
        ensureWorld(player, level);
        if (world <= 0) {
            return;
        }

        terrainCenterY = player.getBlockY();
        updateTerrain(level, player);
        syncShips();
        // 创造/旁观飞行：只把"玩家自己"交回原版移动（飞行手感照旧），
        // 物理世界与飞船/建筑照常模拟 —— 早先把整世界停掉是错的。
        // 碰撞箱由 ensurePlayerBody 锁死旋转（照 space 的 PhysicalEntity）：
        // 姿态不再跟着 6DOF 身体转，那一整类"角扎进地形被弹飞"的问题从根上消失。
        if (player.isSpectator()) {
            releasePlayerBody();
        } else if (playerRig == null) {
            // ⚠️ 结构对齐 space：`PhysicalEntity` 只在 `space$physicalEntity == null` 时被构造一次，
            // 之后**没有任何代码路径会按原版姿态去重建它的碰撞体**。
            // 我们原来每 tick 都调 ensurePlayerBody(player)，把"原版姿态箱"当成物理体的几何来源，
            // 于是原版姿态的任何抖动/换姿都会穿透到物理层（尺寸一变就 bodyClearColliders + 重建）。
            // 现在：刚体的几何归刚体自己所有，建一次，之后只在销毁（切维度/旁观）后才会重建。
            ensurePlayerBody(player);
        }

        // 步进交给 PhysicsStepThread 按 10ms 独立推进（照 space/MPS），这里不再自己走子步。
        // 安全位置：位置看起来正常（没有掉到世界底部以下）时持续记录。
        if (player.getY() > level.getMinBuildHeight() - 32) {
            safeX = player.getX();
            safeY = player.getY();
            safeZ = player.getZ();
            hasSafe = true;
        }
        logScaleIfDue(level);
    }

    /**
     * 每 10 秒记一行"物理规模 + 内存"。
     *
     * <h2>为什么必须有</h2> 2026-09 的一次"在别的星球走动时画面卡死"里，进程工作集
     * 达 <b>8.9 GB</b>（远高于 Java 堆上限 ⇒ 大头在<b>原生</b>），日志里却<b>一行痕迹都没有</b>
     * —— 没有异常、没有崩溃报告、jstack 也没赶上（进程已被关掉）。
     * 于是唯一能回答"是不是原生体/碰撞体在涨"的办法就是把它<b>定期打出来</b>：
     * 走路/传送过程中 {@code Collider} 数若单调上涨，泄漏当场坐实；若不涨，
     * 就说明是别的原因（体素化耗时、GC），也有据可查。
     *
     * <p>{@code RigidBody}/{@code Collider} 是 Rapier 世界的真实规模，
     * 不是我们的记账 —— 记账会和真实情况一起错，计数不会。</p>
     */
    private static void logScaleIfDue(ClientLevel level) {
        long now = System.currentTimeMillis();
        if (now - lastScaleLogMs < SCALE_LOG_MS) {
            return;
        }
        lastScaleLogMs = now;
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
        long maxMb = rt.maxMemory() / (1024L * 1024L);
        int bodies = world > 0 ? NativePhysics.worldGetRigidBodySetSize(world) : -1;
        int colliders = world > 0 ? NativePhysics.worldGetColliderSetSize(world) : -1;
        int nativeBodies = world > 0 ? NativePhysics.worldBodyCount(world) : -1;
        LOGGER.info("[PolyMech] 物理规模 维={} 堆={}/{}MB 原生 刚体={} 碰撞体={} 体计={} 地形区块={} 船={}",
                level.dimension().location(), usedMb, maxMb,
                bodies, colliders, nativeBodies, terrainBodies.size(), ships.size());
        if (maxMb > 0 && usedMb > maxMb * 85L / 100L) {
            LOGGER.warn("[PolyMech] Java 堆已用 {}%:{} / {} MB —— 继续增长会触发 GC 抖动"
                    + "（表现为画面卡顿、窗口未响应）", usedMb * 100L / maxMb, usedMb, maxMb);
        }
    }

    /** 物理规模日志间隔（毫秒）。 */
    private static final long SCALE_LOG_MS = 10_000L;
    private static long lastScaleLogMs = 0L;

    /**
     * 由 {@code EntityPhysicsDriveMixin} 在 {@code Entity.move} 的 <b>HEAD</b> 调用 ——
     * 逐字对应 space 0.1.3 的 {@code MixinEntity.space$moveWithRapier}：
     *
     * <pre>
     * 五点向下 0.1m 探地 → setOnGround / verticalCollisionBelow / resetFallDistance
     * physicalEntity.move(movement.x, movement.y, movement.z);   // own = 位移 × 20
     * entity.setPos(physicalEntity.getPos());
     * if (entity.onGround())
     *     entity.setDeltaMovement(movement.x, movement.y + getGravity(), movement.z);
     * ci.cancel();
     * </pre>
     *
     * <p><b>这里的 {@code movement} 是「碰撞前」的位移</b>（vanilla {@code move} 还没跑），
     * 所以 {@code own} 里带着玩家真实的推进意图。</p>
     *
     * <p><b>回灌量是 {@code getGravity()} 而不是 space 字面的 {@code 0.08}</b>：
     * space 写 {@code +0.08} 的<b>意图</b>是"把 vanilla 随后要减掉的那一份重力补回来"
     * （{@code LivingEntity.travel} 在我们的 HEAD cancel 之后才做 {@code d2 -= getGravity()}），
     * 而 {@code 0.08} 只是"主世界重力"这一个特例的数值。太空 {@code getGravity() = 0}、
     * 行星是 {@code 0.08 × 倍率}，照抄字面值就会每 tick 净增 {@code 0.08 − getGravity()}，
     * 形成 {@code D ← 0.98(D + 0.08 − g)} 的递推并把 own.y 顶到几十 m/s —— 那正是
     * "慢速靠近船/方块被弹飞"。详见方法体内注释与
     * {@code native/jni-smoketest/PlayerWallProbeTest} 的 H/I 两节。</p>
     *
     * <p>以前我们是"碰撞后位移 + 垂直夹成 ≤0"，等于把配套的两半句都改坏：
     * 既丢了输入意图（贴墙时 own 已被原版夹掉），又让回灌变成恒定的向上偏置。
     * 单看哪一半都不对，必须一起回到 space 的样子。</p>
     *
     * @return true 表示已接管（调用方 {@code cancel()} 掉原版 {@code move}）
     */
    public static boolean drive(LocalPlayer player, Vec3 movement) {
        if (!available()) {
            noteMode("关闭（物理世界未就绪 / 原生库不可用）");
            return false;
        }
        if (player.isSpectator()) {
            // space：旁观者销毁刚体，位置完全交回原版
            releasePlayerBody();
            PhysicsDrivenPlayers.unmark(player.getUUID());
            noteMode("原版驱动（旁观者）");
            return false;
        }
        if (playerFlies(player)) {
            // 创造飞行：位置交回原版，刚体保留并跟随（继续与飞船/建筑碰撞，见 afterPlayerTick）。
            PhysicsDrivenPlayers.unmark(player.getUUID());
            noteMode("原版驱动（创造飞行，刚体仍跟随）");
            return false;
        }
        if (playerRig == null || playerBody <= 0) {
            noteMode("原版驱动（玩家双刚体未建成）");
            return false;
        }
        if (!hasTerrainAt(player)) {
            // 地形还没就绪 → 本 tick 交回原版移动。但**刚体必须跟着玩家走**，
            // 否则两边位置越差越远，恢复物理接管那一刻玩家会被拽回旧位置（橡皮筋）。
            PhysicsDrivenPlayers.unmark(player.getUUID());
            playerRig.teleport(player.getX(), player.getY() + playerCenterOffset, player.getZ(),
                    0.0, 0.0, 0.0);
            noteMode("原版驱动（本区块地形碰撞体尚未就绪）");
            return false;
        }
        PhysicsDrivenPlayers.mark(player.getUUID());
        noteMode("物理接管：主刚体=" + playerRig.mainHandle() + " 兄弟=" + playerRig.siblingHandle()
                + " 碰撞箱=" + fmt2(playerHalfWidth * 2.0) + "×" + fmt2(playerHalfHeight * 2.0)
                + " 中心偏移=" + fmt2(playerCenterOffset)
                + " 姿态=" + player.getPose()
                + " 游泳=" + player.isSwimming() + " 滑翔=" + player.isFallFlying()
                + " 潜行=" + player.isCrouching() + " 贴地=" + player.onGround()
                + "（碰撞体积由 Rapier 双刚体裁定；原版碰撞箱只作实体本身用）");

        // ── space 的三步，顺序也照抄：先探地（用碰撞箱当前姿态），再把位移交给刚体，最后读回位置 ──
        probeGround(player);
        playerRig.move(movement.x, movement.y, movement.z);

        final double[] pos = new double[3];
        if (!NativePhysics.bodyReadTranslation(world, playerBody, pos)) {
            return false;
        }

        // 安全网（本地兜底，不在 space 里）：物理位置异常（掉到世界底部以下）→ 复位并交还原版，
        // 避免"物理出问题把人送进虚空"这种灾难性后果。
        double minY = player.level().getMinBuildHeight() - 64.0;
        if (pos[1] - playerCenterOffset < minY && hasSafe) {
            LOGGER.warn("[PolyMech] 物理位置异常（y={}），已复位到安全位置", pos[1]);
            player.setPos(safeX, safeY, safeZ);
            NativePhysics.bodySetTranslation(world, playerBody, safeX, safeY + playerCenterOffset, safeZ);
            NativePhysics.bodySetVelocity(world, playerBody, 0.0, 0.0, 0.0);
            return false;
        }

        double oldX = player.getX();
        double oldY = player.getY();
        double oldZ = player.getZ();
        player.setPos(pos[0], pos[1] - playerCenterOffset, pos[2]);

        // ── 补 vanilla 的步伐账（HEAD 取消 move 会一并跳过它，见 Entity.move 末尾的
        //    walkDist / moveDist 累加）──
        // walkDist → 走路上下颠簸 + 手臂摆动幅度；moveDist → 脚步声/落地声的节拍。
        // 公式与 vanilla 逐字一致（水平/全向距离 × 0.6），只是位移换成"物理真正应用的位移"。
        // 不补的话表现是"画面不再起伏、自己听不到脚步"。
        // （声音本身服务端那边仍会照常发，所以这不是物理量，纯粹是本地表现账。）
        double mx = pos[0] - oldX;
        double my = (pos[1] - playerCenterOffset) - oldY;
        double mz = pos[2] - oldZ;
        player.walkDist += (float) Math.sqrt(mx * mx + mz * mz) * 0.6F;
        player.moveDist += (float) Math.sqrt(mx * mx + my * my + mz * mz) * 0.6F;

        // space 原样：贴地时把位移回灌给 vanilla（抵消 vanilla 随后要减掉的那一份重力）。
        //
        // ⚠️ 这里**不能**照抄 space 的字面 `0.08`。回灌发生在 travel 减重力**之前**
        //（1.21.1 `LivingEntity`：我们的 HEAD cancel 位于 `handleRelativeFrictionAndCalculateMovement`
        // 的 `:2386 this.move(...)` 内，`:2387` 立刻把回灌值读回 vec35，随后 `:2331 d2 -= d0`、
        // `:2341 d2 * 0.98`），所以每 tick 的垂直递推是
        //     D' = 0.98 · (D + 注入 − getGravity())
        // 固定点 D* = 49 · (注入 − getGravity()) 格/tick。于是：
        //   · 主世界  getGravity() = 0.08    → 0.08 − 0.08 = 0        → 稳定（逐位不变）
        //   · 太空    getGravity() = 0       → 每 tick 净增 +0.08      → D* = 3.92 格/tick
        //   · 行星    getGravity() = 0.08·f  → 每 tick 净增 +0.08(1−f) → 同上按 (1−f) 缩放
        // 这三个重力值由 {@code MixinEntity.polymech$gravity} 按维度给（太空 0 / 行星 G/9.807）。
        //
        // 实测证据：9/18 20:16 客户端日志（维度 poly_mech:space，世界重力 -0.0）里
        // `own.y` 依次 1.57 → 7.53 → 14.34 → 20.50 → 26.06 → 31.09，正是第二条递推的
        // 第 1/5/10/15/20/25 tick（日志节流 250ms = 5 tick），线性外推到 78 m/s。
        // 链子随后把 own 原样加进主刚体（`main = sibling + own`），人就被顶上天 ——
        // 这就是"慢速靠近船/普通方块被弹飞"的根因。
        //
        // 因此注入量取实体自己的重力，而不是字面 0.08：与 space 的**意图**（抵消 vanilla 减掉的
        // 那一份）一致，主世界逐位等价，且在任何重力倍率下都成立。
        // 离线复现与回归见 native/jni-smoketest/PlayerWallProbeTest.java 的 H、I 两节。
        if (player.onGround()) {
            player.setDeltaMovement(movement.x, movement.y + player.getGravity(), movement.z);
        }
        if (!takeoverLogged) {
            takeoverLogged = true;
            LOGGER.info("[PolyMech] 客户端物理接管已启用（玩家位置由 Rapier 驱动，维度 {}）",
                    player.level().dimension().location());
        }
        droveThisTick = true;
        return true;
    }

    // ==================== 内部 ====================

    /**
     * 玩家物理模式的**边沿触发**日志：只在模式**变化**时打印一行（不是每 tick 心跳）。
     *
     * <p>为什么值得长期留着：玩家到底"由谁裁定碰撞"这件事在这个项目里无法靠肉眼分辨 ——
     * Rapier 的双刚体碰撞体**尺寸就是从原版碰撞箱取的**
     *（`ensurePlayerBody`：`halfWidth = getBbWidth()*0.5`、`halfHeight = getBbHeight()*0.5`，
     *  与 space 的 `PhysicalEntity:69-70` 逐字一致），
     * 而且两个刚体同位置、靠 `(2,5)/(5,5)` 互不相撞 ⇒ 从外面看永远是**一个**盒子。
     * 所以"我看到的是原版碰撞箱"这个观察本身区分不出"物理在接管"还是"退回原版了" ——
     * 只有这一行能。模式串里带主/兄弟句柄与碰撞箱尺寸，也就直接回答了"我的双刚体在哪"。</p>
     */
    private static String lastPlayerMode = "";
    private static long lastPlayerModeMs = 0L;

    /**
     * 模式变化时记一行（诊断）。
     *
     * <h2>为什么不能直接比整串</h2> 同一个状态有<b>多个调用点、理由串不同</b>：
     * {@code tick()} 说"关闭（非太空维度…）"，{@code drive()} 说
     * "关闭（物理世界未就绪…）" —— 两者逐 tick 交替出现，
     * 于是"整串比较"的边沿触发被击穿，变成<b>每 tick 一行</b>。
     * 2026-09 实测：50 秒的会话里这个 logger 刷了 <b>1344 行（≈27 行/秒）</b>，
     * 全在客户端线程上做字符串格式化 + 同步日志 I/O —— 玩家感受就是"走走卡卡"。
     *
     * <p>所以按<b>粗粒度</b>（括号前那一段：关闭 / 原版驱动 / 物理接管）判变化；
     * 同一粗粒度下的理由变化最多 2 秒报一次，既保住"为什么"，又不刷屏。</p>
     */
    private static void noteMode(String mode) {
        long now = System.currentTimeMillis();
        if (coarseMode(mode).equals(coarseMode(lastPlayerMode))
                && (mode.equals(lastPlayerMode) || now - lastPlayerModeMs < 2000L)) {
            return;
        }
        lastPlayerMode = mode;
        lastPlayerModeMs = now;
        LOGGER.info("[PolyMech] 玩家物理模式 → {}", mode);
    }

    /** 模式串的粗粒度部分（括号前的段）。 */
    private static String coarseMode(String mode) {
        if (mode == null) {
            return "";
        }
        int cut = mode.indexOf('（');
        return cut > 0 ? mode.substring(0, cut) : mode;
    }

    private static String fmt2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static boolean shouldSimulate(LocalPlayer player) {
        // 太空维度：全程物理。
        if (player.level().dimension().equals(PlanetDimensions.SPACE)) {
            return true;
        }
        // 其它维度：附近有物理体才启用，避免影响正常玩法。
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            double dx = body.tickX() - px;
            double dy = body.tickY() - py;
            double dz = body.tickZ() - pz;
            if (dx * dx + dy * dy + dz * dz < 64.0 * 64.0) {
                return true;
            }
        }
        return false;
    }

    /** 创造/旁观飞行：玩家移动交回原版（只影响玩家，不影响物理世界）。 */
    /**
     * 玩家 tick 结束后调用（{@code ClientTickEvent.Post}）。
     *
     * <p>创造/旁观飞行时位置由原版驱动，物理层不再接管位置 —— 但刚体要**跟着玩家走**，
     * 这样它在物理世界里始终占着玩家所在的位置，飞船/建筑的碰撞照常发生
     * （否则飞行期间玩家就是个幽灵，能穿进任何东西）。</p>
     */
    public static void afterPlayerTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || world <= 0 || playerBody <= 0) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }
        if (player.getAbilities().flying) {
            double half = playerCenterOffset;
            Vec3 dm = player.getDeltaMovement();
            double vx = net.minecraft.util.Mth.clamp(dm.x * 20.0, -100.0, 100.0);
            double vy = net.minecraft.util.Mth.clamp(dm.y * 20.0, -100.0, 100.0);
            double vz = net.minecraft.util.Mth.clamp(dm.z * 20.0, -100.0, 100.0);
            // 飞行：位置由原版驱动，**两个**刚体一起跟随玩家（继续参与碰撞）
            if (playerRig != null) {
                playerRig.teleport(player.getX(), player.getY() + half, player.getZ(), vx, vy, vz);
            }
            return;
        }
        // 非飞行：位置由物理驱动 —— **这里不再回写**。
        //
        // space 对实体位置只有一处写入（`Entity.move` 内的 `entity.setPos`），而 `Entity.move`
        // 已经在 `drive()` 里写过了；本方法过去又写一次，等于"每 tick 两个位置权威"。
        // 而且它只在 `droveThisTick` 为真时才走到（即 drive 刚写过），所以那一份**纯属冗余**。
        // （`droveThisTick` 现在只剩"本 tick 是否真被物理接管"这个语义，供飞行/旁观判断用。）
    }

    // ── 已删除：frameWriteBack()（每帧把刚体位置写进玩家 + 抹平 xo/yo/zo/xOld…）──
    //
    // 症状：玩家持续顶住物理体时**抽搐**。根因不在物理，而在"位置怎么交给渲染"。
    //
    // space 0.1.3 全仓库对**实体位置**只有一处写入：`MixinEntity.space$moveWithRapier`
    // 里的 `entity.setPos(pos.x, pos.y, pos.z)`（位于 `Entity.move` 内）——
    // 也就是**每个 tick 一次（20Hz）**，并且**从不触碰 xo/yo/zo/xOld/yOld/zOld**。
    // 飞船（物理体）不一样：`ClientPhysicalBody.render(...)` 直接读刚体当前位姿（100Hz），
    // 因为那是**他们自己渲染的对象**，不经过原版实体管线。
    //
    // 我们早先把"物理体逐帧直读"这条**误推到了玩家实体上**：每帧 setPos 并且把
    // xo/yo/zo/xOld… 全部对齐到当前位置，等于**关掉原版的 tick 插值**。
    // 而玩家身体在接触里本来就有 ~1 个子步量级（own×dt ≈ 4cm）的 100Hz 微抖
    // ——两个刚体同位置、兄弟体每子步被瞬移回主刚体位置，接触解算给出的法向速度
    // 与 own 不会逐位抵消。原版管线本来会把这条 100Hz 抖动插值成平滑轨迹，
    // 我们把插值拆掉之后，它就直接变成镜头上的抽搐。
    //
    // 对齐后：位置只在 `drive()`（= space 的 `Entity.move`）与 `afterPlayerTick()`
    // 兜底里写，**每个 tick 各一次**，插值字段完全交给原版 —— 与 space 同构。
    // 这也是 space 这台机械的总体设计意图：**实体是 100Hz 仿真的 20Hz 消费者，
    // 平滑交给引擎自己的插值；只有自己渲染的对象才直读刚体状态。**

    private static boolean playerFlies(LocalPlayer player) {
        return player.getAbilities().flying || player.isSpectator();
    }

    /** 销毁玩家双刚体（旁观/停用；需要时自动重建）。 */
    private static void releasePlayerBody() {
        if (playerRig != null) {
            PhysicsDrivenPlayers.unmark(playerRig.owner());
            playerRig.destroy();
            playerRig = null;
        } else if (playerBody > 0 && world > 0) {
            NativePhysics.bodyDestroy(world, playerBody);
        }
        playerBody = 0;
        playerHalfWidth = 0.0f;
        playerHalfHeight = 0.0f;
        playerCenterOffset = 0.9;
    }

    /**
     * 着地判定（照抄 space 0.1.3 的 {@code MixinEntity.space$moveWithRapier}）：
     * 从碰撞箱底面「中心 + 四角」五点向下打 0.1m，任意一条命中即着地。
     *
     * <p>物理接管之后原版的 {@code onGround} 就没人维护了 —— 不设的话"站在船上/地上"
     * 在原版眼里是悬空：不能跳、{@code fallDistance} 一直累加（落地摔死）、冲刺被打断。</p>
     *
     * <p>射线起点取<b>刚体位置</b>（space 的 {@code probePos = physicalEntity.getPos()}），
     * 不是实体坐标：space 的刚体原点在脚底，我们的在碰撞箱中心，所以减掉半高才是等价位置。
     * 这样超人姿态的"头盒"也天然探的是它自己的底面，不需要额外的偏移推导。</p>
     */
    private static void probeGround(LocalPlayer player) {
        if (world <= 0 || playerBody <= 0) {
            return;
        }
        final double[] pos = new double[3];
        if (!NativePhysics.bodyReadTranslation(world, playerBody, pos)) {
            return;
        }
        boolean grounded = PhysicsGroundProbe.grounded(world,
                pos[0], pos[1] - playerHalfHeight, pos[2], playerHalfWidth);
        player.setOnGround(grounded);
        player.verticalCollisionBelow = grounded;
        if (grounded) {
            player.resetFallDistance();
        }
    }

    private static void ensureWorld(LocalPlayer player, ClientLevel level) {
        ResourceKey<Level> dim = level.dimension();
        if (world > 0 && dim.equals(worldDimension)) {
            return;
        }
        if (world > 0) {
            shutdown();
        }
        float gravity = PlanetDimensions.gravity(dim);
        world = NativePhysics.worldCreate(0.0, -9.8 * gravity, 0.0);
        // 步进交给独立线程（10ms/步）：照 space/MPS，状态必须是真 100Hz 更新，
        // 而不是"每 tick 一次性走 5 子步"（那样每秒只有 20 次状态更新，渲染必抖）。
        PhysicsStepThread.setClientPausedSupplier(() -> Minecraft.getInstance().isPaused());
        PhysicsStepThread.add(world);
        // 玩家双刚体的速度继承链必须**每个 100Hz 子步之后**跑（space 的 RapierWorld tickListener）：
        // 只在 20Hz 的 tick 重设速度的话，一个 tick 里的 5 个子步之间速度会漂。
        long createdWorld = world;
        PhysicsStepThread.addPostStep(createdWorld, () -> PlayerPhysicsBody.afterStep(createdWorld));
        // 客户端镜像体的运动学推进也要**每个子步**跑（space 把它挂在 PhysicalWorld 的 tickListener 上）：
        // 只在 20Hz 的 tick 里写目标位姿，运动学体每步都会"到点即停"，动的船会一顿一顿。
        PhysicsStepThread.addPostStep(createdWorld, () -> stepShipSync(createdWorld));
        if (world > 0) {
            NativePhysics.worldSetTimestep(world, DT);
            worldDimension = dim;
            LOGGER.info("[PolyMech] 客户端物理世界已创建：维度 {} 重力 {} m/s²",
                    dim.location(), -9.8 * gravity);
        }
    }

    /**
     * 玩家双刚体（主 + 兄弟，照 space 0.1.3 的 {@code PhysicalEntity}）。
     *
     * <p>{@code playerBody} 保留为"主刚体句柄"，读位置/回写/安全网全部只针对主刚体；
     * 兄弟刚体完全由 {@link PlayerPhysicsBody} 内部维护（速度继承链）。</p>
     */
    private static PlayerPhysicsBody playerRig;

    private static void ensurePlayerBody(LocalPlayer player) {
        float halfWidth = Math.max(0.05f, player.getBbWidth() * 0.5f);
        float halfHeight = Math.max(0.05f, player.getBbHeight() * 0.5f);
        // 中心偏移：普通 = 半高（盒子坐底在脚底）；超人 = 眼高（0.6³ 头盒只包头脸）。
        double centerOffset = com.mss.polymech.space.SpacePlayerData.bodyCenterOffset(player);
        PlayerPhysicsBody rig = PlayerPhysicsBody.getOrCreate(player.getUUID(), world,
                player.getX(), player.getY() + centerOffset, player.getZ(),
                halfWidth, halfHeight, centerOffset);
        playerRig = rig;
        playerBody = rig == null ? 0L : rig.mainHandle();
        playerHalfWidth = halfWidth;
        playerHalfHeight = halfHeight;
        playerCenterOffset = centerOffset;
    }

    private static void updateTerrain(ClientLevel level, LocalPlayer player) {
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        long centerChunk = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
        // ★ 深空守卫（docs/mps-clone-plan.md §30.10）：区块坐标在深空会**别名到原点附近**，
        //   于是 `level.getChunk(...)` 与随后的体素化会在"别的地方"建出地形碰撞体 ——
        //   这比"没有地形"更糟（玩家会在深空撞到属于原点的地形）。深空没有方块空间，直接不建。
        //   注意守卫用的是**世界坐标**（player.getX()），不是已经被截断的 BlockPos。
        if (com.mss.polymech.space.SpaceWorld.isDeepSpace(player.getX(), player.getZ())) {
            for (Long key : new ArrayList<>(terrainBodies.keySet())) {
                Long body = terrainBodies.remove(key);
                if (body != null && body > 0) {
                    NativePhysics.bodyDestroy(world, body);
                }
            }
            terrainDirty.clear();
            return;
        }

        boolean moved = centerChunk != lastTerrainChunk;
        // 竖直带移动 ⇒ 所有区块的体素都要重裁（见 lastTerrainBandY 的注释）。
        boolean bandMoved = Math.abs(terrainCenterY - lastTerrainBandY) >= BAND_REBUILD_STEP;
        if (!moved && !bandMoved && terrainDirty.isEmpty()) {
            return;
        }
        lastTerrainChunk = centerChunk;
        lastTerrainBandY = terrainCenterY;

        // ⚠️ 这里原来写的是 `if (moved || terrainDirty.contains(key) || !terrainBodies.containsKey(key))`。
        // `moved` 是**水平跨区块**：玩家每走 16 格就把视野内 9 个区块全部重新体素化一遍
        // （每块最多 16×16×128 格 = 32k 次取方块 + 原生单元插入）。地形<b>没变</b>，
        // 纯粹白干 —— 而且这一下发生在客户端线程上，就是"走着走着突然一顿/卡死"。
        // 正确判据只有两个：这个区块<b>变过</b>（terrainDirty）或<b>还没建过</b>；
        // 外加"竖直径向带整体移动了"才需要全量重裁。
        long t0 = System.nanoTime();
        int built = 0;
        Set<Long> desired = new HashSet<>();
        for (int dx = -TERRAIN_RADIUS; dx <= TERRAIN_RADIUS; dx++) {
            for (int dz = -TERRAIN_RADIUS; dz <= TERRAIN_RADIUS; dz++) {
                int ccx = cx + dx;
                int ccz = cz + dz;
                long key = (((long) ccx) << 32) ^ (ccz & 0xFFFFFFFFL);
                if (!level.getChunkSource().hasChunk(ccx, ccz)) {
                    continue;
                }
                desired.add(key);
                if (bandMoved || terrainDirty.contains(key) || !terrainBodies.containsKey(key)) {
                    buildTerrainChunk(level, ccx, ccz, key);
                    built++;
                }
            }
        }
        terrainDirty.clear();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms >= TERRAIN_SLOW_MS) {
            // 这条日志就是"卡死"的取证：真的慢了，就能看到慢在几个区块、多少毫秒。
            LOGGER.warn("[PolyMech] 地形体素化耗时 {} ms（重建 {} 个区块，带移动={}，中心 chunk={},{}）"
                            + " —— 客户端线程上的长操作，表现为掉帧/未响应",
                    ms, built, bandMoved, cx, cz);
        }

        List<Long> stale = new ArrayList<>();
        for (Long key : terrainBodies.keySet()) {
            if (!desired.contains(key)) {
                stale.add(key);
            }
        }
        for (Long key : stale) {
            Long body = terrainBodies.remove(key);
            if (body != null && body > 0) {
                NativePhysics.bodyDestroy(world, body);
            }
        }
    }

    private static void buildTerrainChunk(ClientLevel level, int cx, int cz, long key) {
        Long old = terrainBodies.remove(key);
        if (old != null && old > 0) {
            NativePhysics.bodyDestroy(world, old);
        }
        LevelChunk chunk = level.getChunk(cx, cz);
        int minY = level.getMinBuildHeight();
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        List<Long> cells = new ArrayList<>();
        // B1：非满碰撞形状（台阶/楼梯/栅栏/墙/锁链…）另收复合盒。
        // 客户端必须与服务端一致，否则"服务端能走上去的台阶，客户端身体被卡住"。
        PhysicsShapes.Boxes boxes = new PhysicsShapes.Boxes();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
            if (baseY + 15 < terrainCenterY - VERTICAL_BAND || baseY > terrainCenterY + VERTICAL_BAND) {
                continue;
            }
            int yStart = Math.max(0, terrainCenterY - VERTICAL_BAND - baseY);
            int yEnd = Math.min(15, terrainCenterY + VERTICAL_BAND - baseY);
            for (int y = yStart; y <= yEnd; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir() || !state.getFluidState().isEmpty()) {
                            continue;
                        }
                        int localY = baseY - minY + y;
                        if (PhysicsShapes.isFullBlock(state)) {
                            cells.add(NativePhysics.packCell(x, localY, z));
                        } else if (PhysicsNatives.hasTier1()) {
                            BlockPos worldPos = new BlockPos(minX + x, baseY + y, minZ + z);
                            boxes.addShape(
                                    state.getCollisionShape(level, worldPos, net.minecraft.world.phys.shapes.CollisionContext.empty()),
                                    x, localY, z,
                                    PhysicsShapes.MAX_BOXES_PER_CHUNK - boxes.count());
                        } else {
                            // 原生层低于 ABI 5：退回旧行为（统统当整格），并与服务端保持同一套判定
                            cells.add(NativePhysics.packCell(x, localY, z));
                        }
                    }
                }
            }
        }
        long[] packed = new long[cells.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = cells.get(i);
        }
        double[] boxArray = boxes.toArray();
        if (packed.length == 0 && boxArray.length == 0) {
            terrainBodies.put(key, 0L);
            return;
        }
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                cx << 4, minY, cz << 4, 0.0, 0.0, 0.0, 1.0, 0.0);
        if (body <= 0) {
            return;
        }
        int attached = 0;
        if (packed.length > 0) {
            long collider = NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, packed,
                    PhysicsMaterials.TERRAIN_FRICTION, 0.0);
            if (collider > 0) {
                PhysicsMaterials.apply(world, collider, PhysicsMaterials.TERRAIN_FRICTION, 0.0);
                attached++;
            }
        }
        if (boxArray.length > 0) {
            long collider = NativePhysics.colliderAttachBoxes(world, body, boxArray,
                    PhysicsMaterials.TERRAIN_FRICTION, 0.0, 1, -1);
            if (collider > 0) {
                PhysicsMaterials.apply(world, collider, PhysicsMaterials.TERRAIN_FRICTION, 0.0);
                attached++;
            }
        }
        if (attached == 0) {
            NativePhysics.bodyDestroy(world, body);
            return;
        }
        terrainBodies.put(key, body);
    }

    /** 玩家所在区块是否已有地形碰撞体（安全阀）。 */
    /**
     * 玩家所在区块是否已处理过地形（安全阀）。
     *
     * <p>关键：空区块（太空维度、纯虚空）没有方块、因此没有碰撞体，但它同样算"已就绪"。
     * 早期版本用 {@code body > 0} 判断，导致太空里永远判定"地形未就绪" → 物理接管不生效
     * （玩家继续穿模）。这里只要求该区块被处理过。</p>
     */
    private static boolean hasTerrainAt(LocalPlayer player) {
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        long key = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
        return terrainBodies.containsKey(key);
    }

    /**
     * 读取客户端刚体当前变换并写进 {@link ClientPhysicsWorld.ClientBody}（渲染用）。
     *
     * <p>这是 space(MPS) 的做法：客户端物理世界自己按 100Hz 积分，渲染直接取刚体的状态；
     * 20Hz 的同步包只负责周期性把刚体拉回权威位置/速度/角速度；
     * 若改成在网络包之间插值，信息量永远只有 20Hz，怎么插都不顺。
     *
     * @param beforeStep true = 步进前（记为"上一帧"，作为插值起点）；false = 步进后（本帧状态）。
     */
    private static void captureShipTransforms(boolean beforeStep) {
        if (world <= 0 || ships.isEmpty()) {
            return;
        }
        double[] p = new double[3];
        double[] q = new double[4];
        for (Map.Entry<Long, ShipBody> entry : ships.entrySet()) {
            ClientPhysicsWorld.ClientBody body = ClientPhysicsWorld.body(entry.getKey());
            if (body == null) {
                continue;
            }
            long handle = entry.getValue().body;
            if (!NativePhysics.bodyReadTranslation(world, handle, p)
                    || !NativePhysics.bodyReadRotation(world, handle, q)) {
                continue;
            }
            if (beforeStep) {
                body.snapshotPhysics(p[0], p[1], p[2],
                        (float) q[0], (float) q[1], (float) q[2], (float) q[3]);
            } else {
                body.updatePhysics(p[0], p[1], p[2],
                        (float) q[0], (float) q[1], (float) q[2], (float) q[3]);
            }
        }
    }

    /**
     * 已删除：{@code localCenter}。
     *
     * <p>它是为"给 DYNAMIC 镜像体补质量下限（附加热质量必须挂包围盒中心）"服务的；
     * 镜像体改成运动学后没有质量可言，这个方法随之作废。</p>
     */

    /**
     * 把服务端同步来的飞船/建筑建成<b>运动学</b>刚体 —— 逐字照 space 0.1.3 的
     * {@code ClientPhysicalBody}（构造时 {@code super(level, pos, rotation, uuid, kinematic = true)}
     * → {@code RigidBody.Type.KINEMATIC_POSITION}）。
     *
     * <p><b>为什么必须是运动学</b>（我们之前建成 DYNAMIC，注释里还写着"照 MPS"——那是误读）：
     * 动态镜像体有有限质量，玩家一顶就在本地被推动；随后服务端同步（或"偏差超过 1 格才硬拉"的
     * 位置修正）再把它拽回去，<b>这个"拽回"就是把玩家弹飞的冲量来源</b>。
     * 运动学体无限质量、本地推不动，位置只跟服务端走，问题连同位置修正补丁一起消失。</p>
     *
     * <p>位姿推进照 space：本方法（主线程，每个客户端 tick）只负责把"当前位姿 → 服务端新位姿"
     * 写进 {@link ShipBody#syncFrom}/{@link ShipBody#syncTo} 并把 {@code progress} 归零；
     * 真正的推进在 {@link #stepShipSync}（步进线程，每 100Hz 子步 +0.2）。
     * 姿态直接取同步值 —— space 的 {@code setRotation} 也不插值。</p>
     */
    private static void syncShips() {
        Set<Long> alive = new HashSet<>();
        for (ClientPhysicsWorld.ClientBody ship : ClientPhysicsWorld.bodies()) {
            alive.add(ship.id());
            ShipBody existing = ships.get(ship.id());
            if (existing != null && existing.blockCount != ship.blocks().size()) {
                NativePhysics.bodyDestroy(world, existing.body);
                ships.remove(ship.id());
                existing = null;
            }
            if (existing == null) {
                long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_KINEMATIC_POSITION,
                        ship.tickX(), ship.tickY(), ship.tickZ(),
                        ship.qx(), ship.qy(), ship.qz(), ship.qw(), 0.0);
                if (body <= 0) {
                    continue;
                }
                long[] cells = new long[ship.blocks().size()];
                for (int i = 0; i < cells.length; i++) {
                    ClientPhysicsWorld.BlockEntry e = ship.blocks().get(i);
                    cells[i] = NativePhysics.packCell(e.dx(), e.dy(), e.dz());
                }
                long collider = PhysicsNatives.hasCollisionGroups()
                        ? NativePhysics.colliderAttachVoxelsGrouped(world, body, 1.0, 1.0, 1.0, cells,
                                PhysicsMaterials.BODY_FRICTION, 0.0, BODY_MEMBERSHIP, BODY_FILTER)
                        : NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, cells,
                                PhysicsMaterials.BODY_FRICTION, 0.0);
                PhysicsMaterials.apply(world, collider, PhysicsMaterials.BODY_FRICTION, 0.0);
                ships.put(ship.id(), new ShipBody(body, ship.blocks().size(),
                        ship.tickX(), ship.tickY(), ship.tickZ()));
            } else {
                // space 的 onMoveSync：起点 = 当前实际位姿，终点 = 服务端新位姿，进度归零。
                double[] cur = new double[3];
                if (!NativePhysics.bodyReadTranslation(world, existing.body, cur)) {
                    cur = new double[]{ship.tickX(), ship.tickY(), ship.tickZ()};
                }
                existing.syncFrom = cur;
                existing.syncTo = new double[]{ship.tickX(), ship.tickY(), ship.tickZ()};
                existing.progress = 0.0;
                // 姿态直接取同步值（space: physicalBody.setRotation(entry.rotate())）。
                NativePhysics.bodySetRotation(world, existing.body,
                        ship.qx(), ship.qy(), ship.qz(), ship.qw(), true);
            }
        }
        List<Long> gone = new ArrayList<>();
        for (Long id : ships.keySet()) {
            if (!alive.contains(id)) {
                gone.add(id);
            }
        }
        for (Long id : gone) {
            ShipBody body = ships.remove(id);
            if (body != null) {
                NativePhysics.bodyDestroy(world, body.body);
            }
        }
    }
}
