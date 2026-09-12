package com.mss.polymech.client.physics;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.physics.NativePhysics;
import com.mss.polymech.physics.PhysicsNatives;
import com.mss.polymech.physics.PhysicsStepThread;
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
 * 客户端物理世界：让玩家"真的活在物理里"（对应 {@code space} 的 MPS 客户端半边）。
 *
 * <p>与之前"先原版移动再把人推出来"的做法不同，这里采用 space 的思路：
 * <b>原版算出的位移不再直接应用，而是转成力交给刚体，位置从刚体读回</b>。</p>
 *
 * <p>为此客户端必须自带一套碰撞世界，包含三部分：</p>
 * <ol>
 *   <li><b>地形</b>：把玩家周围区块体素化成固定碰撞体（否则位置来自物理的玩家会直接穿过地面）；</li>
 *   <li><b>飞船/建筑</b>：把服务端同步来的方块快照建成<b>运动学</b>刚体（位置由服务端权威，客户端只负责碰撞）；</li>
 *   <li><b>玩家</b>：动态刚体（质量 50、锁定旋转），受"原版位移转成的力"驱动。</li>
 * </ol>
 *
 * <p><b>力模型（照抄 space 0.1.0 / MPS）</b>：{@code rigidBody.applyForce(new Force(delta × 70.0, 0.05))}，
 * 质量 50kg、<b>无阻尼</b>。原版每 tick 算出的位移被当成一个持续 0.05s 的力，
 * 刚体自己积分出速度与位置，位置再读回实体 —— 所以速度<b>只累加不衰减</b>：
 * 按住 W 持续加速，松开键会一直漂（这就是太空手感）。
 * 绝不能再叠加"速度伺服"（把速度强行拉到原版值），那会把惯性整条抹掉。</p>
 *
 * <p><b>安全阀</b>：只有当"玩家所在区块的地形碰撞体已就绪"时才接管移动；
 * 否则回退原版移动（返回 false），避免掉出世界。</p>
 */
public final class ClientPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/Client");

    /** 物理子步：每客户端 tick (1/20s) 走 5 个 1/100s 子步 ≈ 100Hz。 */
    private static final int SUBSTEPS = 5;
    private static final double DT = 1.0 / 100.0;
    private static final double TICK_SECONDS = 1.0 / 20.0;
    private static final double PLAYER_MASS = 50.0;

    /**
     * MPS 力系数：{@code F = delta × 70.0}（牛顿），delta 为原版本 tick 位移（格）。
     * <p>对应 space 的 {@code PhysicalEntity.move()}：{@code applyForce(new Force(delta.mul(70.0), 0.05))}。</p>
     */
    private static final double MPS_FORCE = 70.0;
    /** 该力的作用时长（秒）：MPS 用 0.05（= 5 个 1/100s 子步，正好一个 MC tick）。 */
    private static final double MPS_FORCE_SECONDS = 0.05;
    /**
     * 每 tick 施加的等价冲量系数 = 70 × 0.05 = 3.5（N·s per 格位移）。
     * <p>Δv = 3.5·delta/50 = 0.07·delta，与"持续力 0.05s"在无阻尼下完全等价，
     * 且不依赖"下一步何时步进"，不会像持续力那样在 move 少调一次时泄漏。</p>
     */
    private static final double MPS_IMPULSE = MPS_FORCE * MPS_FORCE_SECONDS;

    /** 地形体素化的垂直带宽（关注点上下），与服务器侧一致。 */
    private static final int VERTICAL_BAND = 64;    /** 地形重建半径（区块）。 */
    private static final int TERRAIN_RADIUS = 1;

    /**
     * 位置修正阈值（格）：客户端刚体与服务器权威位置偏差超过它才硬拉回。
     * <p>每 tick 无条件 setPos 会让本地 100Hz 积分每帧"贴回"服务器 20Hz 的台阶 → 锯齿。</p>
     */
    private static final double POSITION_CORRECTION = 1.0;
    private static final double POSITION_CORRECTION_SQ = POSITION_CORRECTION * POSITION_CORRECTION;

    private static long world = 0;
    private static ResourceKey<Level> worldDimension = null;

    private static long playerBody = 0;
    private static float playerHalfWidth = 0;
    private static float playerHalfHeight = 0;

    private static final Map<Long, Long> terrainBodies = new HashMap<>();
    private static final Set<Long> terrainDirty = new HashSet<>();
    private static long lastTerrainChunk = Long.MIN_VALUE;
    private static boolean takeoverLogged = false;
    /** 最近一次看起来正常的位置（防止物理异常时掉出世界）。 */
    private static double safeX, safeY, safeZ;
    private static boolean hasSafe = false;
    private static int terrainCenterY = 64;

    private static final Map<Long, ShipBody> ships = new HashMap<>();

    /** 推船回报节流（按玩家）。 */
    private static final Map<UUID, Integer> LAST_PUSH = new HashMap<>();
    private static final int PUSH_INTERVAL = 4;

    /**
     * 客户端镜像刚体。
     *
     * @param lastMotion 上次写入的 [vx,vy,vz,ax,ay,az]：用于"值没变就不唤醒"，让静置的船能休眠
     */
    private record ShipBody(long body, int blockCount, double[] lastMotion) {
    }

    private static double[] motionOf(ClientPhysicsWorld.ClientBody ship) {
        return new double[]{ship.vx(), ship.vy(), ship.vz(), ship.avx(), ship.avy(), ship.avz()};
    }

    /**
     * 读取客户端刚体**当前**的变换（渲染用，照 MPS：直接取当前状态，不做插值）。
     *
     * <p>步进由 {@link PhysicsStepThread} 按 10ms 独立推进，状态是真 100Hz 更新的，
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
        if (!NativePhysics.bodyReadTranslation(w, ship.body(), posOut)
                || !NativePhysics.bodyReadRotation(w, ship.body(), q)) {
            return false;
        }
        rotOut[0] = (float) q[0];
        rotOut[1] = (float) q[1];
        rotOut[2] = (float) q[2];
        rotOut[3] = (float) q[3];
        return true;
    }

    /**
     * 速度同步死区：服务器速度与"上次写入的值"差值小于它就不写、不唤醒。
     *
     * <p>为什么需要：服务器与客户端各自积分，速度每 tick 都会有微小差异。
     * 若按"值不等就写"，就会<b>每 tick 唤醒并重写速度</b>，20Hz 的速度微跳在画面上
     * 表现为"频率快、幅度小"的抖动 —— 静置时刚体休眠所以干净，一动就抖，正是这个原因。
     * 有了死区，客户端按自己的速度连续积分，只有真正漂了（差值累积超过死区）才纠正。</p>
     */
    private static final double MOTION_DEADBAND = 0.02;

    private static boolean sameMotion(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > MOTION_DEADBAND) {
                return false;
            }
        }
        return true;
    }

    private ClientPhysics() {
    }

    /** 客户端物理是否可用（原生库已加载且世界已创建）。 */
    public static boolean available() {
        return PhysicsNatives.isAvailable() && world > 0;
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
                NativePhysics.bodyDestroy(world, ship.body());
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
        if (playerBody > 0 && world > 0) {
            NativePhysics.bodyDestroy(world, playerBody);
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
    }

    /**
     * 每客户端 tick（建议在 {@code ClientTickEvent.Pre}）调用：维护物理世界并步进。
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
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
        // 不再锁旋转：碰撞体姿态改为每 tick 跟玩家朝向走（见 syncPlayerBodyRotation）
        if (player.isSpectator()) {
            releasePlayerBody();
        } else {
            ensurePlayerBody(player);
            syncPlayerBodyRotation(player);
        }

        // 步进由 PhysicsStepThread 按 10ms 独立推进（照 space/MPS），这里不再自己走子步。

        // 安全位置：位置看起来正常（没有掉到世界底部以下）时持续记录
        if (player.getY() > level.getMinBuildHeight() - 32) {
            safeX = player.getX();
            safeY = player.getY();
            safeZ = player.getZ();
            hasSafe = true;
        }
    }

    /**
     * 由 mixin 在 {@code Entity.move} 里调用：把原版位移转成力，并从刚体读回位置。
     *
     * @return true 表示已接管（调用方不要再执行原版 setPos）
     */
    public static boolean drive(LocalPlayer player, Vec3 delta) {
        if (!available() || playerBody <= 0) {
            return false;
        }
        if (playerFlies(player)) {
            // 创造/旁观飞行：位置交回原版。刚体**保留**（继续与飞船/建筑碰撞），
            // 只是不再由物理解算驱动位置 —— 每 tick 由 afterPlayerTick 同步到玩家位置。
            return false;
        }
        if (!hasTerrainAt(player)) {
            return false; // 地形还没就绪 → 回退原版，避免掉出世界
        }
        // ── MPS 力模型：原版位移 → 冲量，不做任何速度反馈 ──
        //   space: applyForce(new Force(delta × 70.0, 0.05))  质量 50kg、无阻尼
        //   我们的世界每 MC tick 走 5 × 1/100s = 0.05s，正好是该力的作用时长，
        //   故等价冲量 = delta × 70 × 0.05 = delta × 3.5 (N·s)
        //   → Δv = 0.07·delta：速度只累加不衰减（按住 W 持续加速，松手继续漂）
        NativePhysics.bodyApplyImpulse(world, playerBody,
                delta.x * MPS_IMPULSE, delta.y * MPS_IMPULSE, delta.z * MPS_IMPULSE);

        // 被挡住（想走却走不动）且旁边有飞船 → 回报服务端施加冲量（玩家推得动船）
        final double[] v = new double[3];
        if (NativePhysics.bodyReadVelocity(world, playerBody, v)) {
            reportPushIfBlocked(player, delta, v);
        }

        // 位置从刚体读回（空间：刚体中心 → 实体脚底）
        final double[] pos = new double[3];
        if (!NativePhysics.bodyReadTranslation(world, playerBody, pos)) {
            return false;
        }

        // 安全网：物理位置异常（掉到世界底部以下）→ 复位到最近的安全位置并本 tick 交还原版，
        // 避免"物理出问题把人送进虚空"这种灾难性后果
        double minY = player.level().getMinBuildHeight() - 64.0;
        if (pos[1] - playerHalfHeight < minY && hasSafe) {
            LOGGER.warn("[PolyMech] 物理位置异常（y={}），已复位到安全位置", pos[1]);
            player.setPos(safeX, safeY, safeZ);
            NativePhysics.bodySetTranslation(world, playerBody, safeX, safeY + playerHalfHeight, safeZ);
            NativePhysics.bodySetVelocity(world, playerBody, 0.0, 0.0, 0.0);
            return false;
        }

        player.setPos(pos[0], pos[1] - playerHalfHeight, pos[2]);
        if (!takeoverLogged) {
            takeoverLogged = true;
            LOGGER.info("[PolyMech] 客户端物理接管已启用（玩家位置由 Rapier 驱动，维度 {}）",
                    player.level().dimension().location());
        }
        return true;
    }

    // ==================== 内部 ====================

    private static boolean shouldSimulate(LocalPlayer player) {
        // 太空维度：全程物理
        if (player.level().dimension().equals(PlanetDimensions.SPACE)) {
            return true;
        }
        // 其它维度：附近有物理体才启用，避免影响正常玩法
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
            double half = playerHalfHeight;
            Vec3 dm = player.getDeltaMovement();
            double vx = net.minecraft.util.Mth.clamp(dm.x * 20.0, -100.0, 100.0);
            double vy = net.minecraft.util.Mth.clamp(dm.y * 20.0, -100.0, 100.0);
            double vz = net.minecraft.util.Mth.clamp(dm.z * 20.0, -100.0, 100.0);
            // 飞行：位置由原版驱动，刚体跟随玩家（继续参与碰撞）
            NativePhysics.bodySetTranslation(world, playerBody,
                    player.getX(), player.getY() + half, player.getZ());
            NativePhysics.bodySetMotion(world, playerBody, vx, vy, vz, 0.0, 0.0, 0.0, true);
            return;
        }
        // 非飞行：物理驱动位置，**每 tick 无条件回写**。
        // 不能只依赖 Entity.move 里 setPos 重定向那次回写 —— 原版在位移≈0 时会走捷径、
        // 根本不触达那次 setPos，于是"站着一动不动"时物理仍在后台积分、画面却停在原地，
        // 一按 WASD 才跳到后台算出的位置。
        double[] pos = new double[3];
        if (!NativePhysics.bodyReadTranslation(world, playerBody, pos)) {
            return;
        }
        player.setPos(pos[0], pos[1] - playerHalfHeight, pos[2]);
    }

    /**
     * 每**帧**调用（{@code RenderFrameEvent.Pre}）：把刚体位置写进玩家实体。
     *
     * <p>为什么不能只在 tick 里回写：物理由独立线程按 10ms 推进（100Hz），
     * 而 20Hz 的 tick 每次采样到的物理步数是 4/5/6 波动 → 每 tick 位移忽多忽少，
     * 表现就是"一顿一顿"。逐帧取样后，相机直接跟随 100Hz 的物理状态，
     * 与飞船渲染同源（space/MPS 就是渲染时直接取刚体状态）。</p>
     */
    public static void frameWriteBack() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || world <= 0 || playerBody <= 0) {
            return;
        }
        if (player.isSpectator() || player.getAbilities().flying) {
            return; // 旁观者无刚体；飞行由原版驱动位置
        }
        double[] pos = new double[3];
        if (!NativePhysics.bodyReadTranslation(world, playerBody, pos)) {
            return;
        }
        double x = pos[0];
        double y = pos[1] - playerHalfHeight;
        double z = pos[2];
        player.setPos(x, y, z);
        // O 与 Old 一并对齐：让相机用"当前物理位置"而不是在两个 tick 采样之间插值
        player.xo = x;
        player.yo = y;
        player.zo = z;
        player.xOld = x;
        player.yOld = y;
        player.zOld = z;
    }

    private static boolean playerFlies(LocalPlayer player) {
        return player.getAbilities().flying || player.isSpectator();
    }

    /** 销毁玩家刚体（飞行期间不需要；停飞后会自动重建）。 */
    private static void releasePlayerBody() {
        if (playerBody > 0 && world > 0) {
            NativePhysics.bodyDestroy(world, playerBody);
        }
        playerBody = 0;
        playerHalfWidth = 0.0f;
        playerHalfHeight = 0.0f;
    }

    /**
     * 玩家碰撞体姿态跟随：把 6DOF 朝向写进刚体。
     * <p>只在太空且朝向已初始化时生效；其它维度保持默认（yaw 对方底盒子无影响）。</p>
     */
    private static void syncPlayerBodyRotation(LocalPlayer player) {
        if (playerBody <= 0 || world <= 0
                || !player.level().dimension().equals(PlanetDimensions.SPACE)) {
            return;
        }
        com.mss.polymech.space.SpacePlayerData data =
                com.mss.polymech.space.SpacePlayerData.get(player);
        if (!data.isInitialized()) {
            return;
        }
        org.joml.Quaternionf q = data.orientation(new org.joml.Quaternionf());
        NativePhysics.bodySetRotation(world, playerBody, q.x(), q.y(), q.z(), q.w(), true);
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
        if (world > 0) {
            NativePhysics.worldSetTimestep(world, DT);
            worldDimension = dim;
            LOGGER.info("[PolyMech] 客户端物理世界已创建：维度 {} 重力 {} m/s²",
                    dim.location(), -9.8 * gravity);
        }
    }

    private static void ensurePlayerBody(LocalPlayer player) {
        float halfWidth = Math.max(0.05f, player.getBbWidth() * 0.5f);
        float halfHeight = Math.max(0.05f, player.getBbHeight() * 0.5f);
        if (playerBody > 0 && Math.abs(halfWidth - playerHalfWidth) < 1.0e-3
                && Math.abs(halfHeight - playerHalfHeight) < 1.0e-3) {
            return;
        }
        if (playerBody > 0) {
            NativePhysics.bodyDestroy(world, playerBody);
            playerBody = 0;
        }
        playerHalfWidth = halfWidth;
        playerHalfHeight = halfHeight;
        playerBody = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                player.getX(), player.getY() + halfHeight, player.getZ(),
                0.0, 0.0, 0.0, 1.0, PLAYER_MASS);
        if (playerBody <= 0) {
            return;
        }
        NativePhysics.colliderAttachCuboid(world, playerBody, halfWidth, halfHeight, halfWidth, 0.6, 0.0);
        // 玩家不该翻滚
        // 姿态由 syncPlayerBodyRotation 每 tick 写入（跟随 6DOF 朝向），这里不锁旋转
    }

    private static void updateTerrain(ClientLevel level, LocalPlayer player) {
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        long centerChunk = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
        boolean moved = centerChunk != lastTerrainChunk;
        if (!moved && terrainDirty.isEmpty()) {
            return;
        }
        lastTerrainChunk = centerChunk;

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
                if (moved || terrainDirty.contains(key) || !terrainBodies.containsKey(key)) {
                    buildTerrainChunk(level, ccx, ccz, key);
                }
            }
        }
        terrainDirty.clear();

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
        LevelChunkSection[] sections = chunk.getSections();
        List<Long> cells = new ArrayList<>();
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
                        cells.add(NativePhysics.packCell(x, baseY - minY + y, z));
                    }
                }
            }
        }
        if (cells.isEmpty()) {
            terrainBodies.put(key, 0L);
            return;
        }
        long[] packed = new long[cells.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = cells.get(i);
        }
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                cx << 4, minY, cz << 4, 0.0, 0.0, 0.0, 1.0, 0.0);
        if (body <= 0) {
            return;
        }
        if (NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, packed, 0.7, 0.0) <= 0) {
            NativePhysics.bodyDestroy(world, body);
            return;
        }
        terrainBodies.put(key, body);
    }

    /** 玩家所在区块是否已有地形碰撞体（安全阀）。 */
    /**
     * "想走却走不动"= 正在推东西：把方向回报服务端施加冲量（质量感知）。
     * 服务端的飞船是动态刚体，客户端这里只是运动学镜像，所以推的效果必须走网络。
     */
    private static void reportPushIfBlocked(LocalPlayer player, Vec3 delta, double[] velocity) {
        double wantX = delta.x;
        double wantZ = delta.z;
        double len = Math.sqrt(wantX * wantX + wantZ * wantZ);
        if (len < 0.02) {
            return;
        }
        // 实际水平速度远低于期望 → 被挡住
        double actual = Math.sqrt(velocity[0] * velocity[0] + velocity[2] * velocity[2]);
        double desired = len / TICK_SECONDS;
        if (actual > desired * 0.5) {
            return;
        }
        int now = player.tickCount;
        Integer last = LAST_PUSH.get(player.getUUID());
        if (last != null && now - last < PUSH_INTERVAL) {
            return;
        }
        // 找一个最近的飞船
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        ClientPhysicsWorld.ClientBody nearest = null;
        double best = Double.MAX_VALUE;
        for (ClientPhysicsWorld.ClientBody body : ClientPhysicsWorld.bodies()) {
            double dx = body.tickX() - px;
            double dy = body.tickY() - py;
            double dz = body.tickZ() - pz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) {
                best = d;
                nearest = body;
            }
        }
        if (nearest == null || best > 36.0) {
            return;
        }
        LAST_PUSH.put(player.getUUID(), now);
        float strength = (float) Math.min(1.0, len * 20.0);
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.mss.polymech.network.PhysicsBodyPushPacket(nearest.id(),
                        (float) (wantX / len), (float) (wantZ / len), strength));
    }

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
     * 20Hz 的同步包只负责周期性把刚体拉回权威位置/速度/角速度。
     * 若改成"在网络包之间插值"，信息量永远只有 20Hz，怎么插都不顺。</p>
     *
     * @param beforeStep true = 步进前（记为"上一帧"，作为插值起点）；false = 步进后（本帧状态）
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
            long handle = entry.getValue().body();
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

    /** 把服务端同步来的飞船/建筑建成运动学刚体（位置跟随服务端）。 */
    private static void syncShips() {
        Set<Long> alive = new HashSet<>();
        for (ClientPhysicsWorld.ClientBody ship : ClientPhysicsWorld.bodies()) {
            alive.add(ship.id());
            ShipBody existing = ships.get(ship.id());
            if (existing != null && existing.blockCount() != ship.blocks().size()) {
                NativePhysics.bodyDestroy(world, existing.body());
                ships.remove(ship.id());
                existing = null;
            }
            if (existing == null) {
                // 照 MPS：客户端物理体是 DYNAMIC（不是"运动学墙"），位置与速度每 tick 由服务端权威覆盖
                long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
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
                NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, cells, 0.6, 0.0);
                // 不再锁旋转：MPS 的客户端刚体是自由的，靠同步来的角速度自己转，
                // 碰撞体姿态于是跟着船一起转；锁住的话转动的船碰撞体会永久停在建体姿态。
                double[] motion = motionOf(ship);
                NativePhysics.bodySetMotion(world, body,
                        motion[0], motion[1], motion[2], motion[3], motion[4], motion[5], true);
                ships.put(ship.id(), new ShipBody(body, ship.blocks().size(), motion));
            } else {
                double[] motion = motionOf(ship);
                // 值没变就不唤醒：静止的船才能进入 Rapier 的休眠。
                // 每 tick 无条件唤醒 + 重写速度，会让求解器每步都在解它，接触噪声被反复喂回去
                // → 姿态抖动 / 朝向漂移，而且白烧 CPU（帧数低）。
                boolean changed = !sameMotion(existing.lastMotion(), motion);
                NativePhysics.bodySetMotion(world, existing.body(),
                        motion[0], motion[1], motion[2], motion[3], motion[4], motion[5], changed);
                // 位置修正：**只在偏差明显时才硬拉**。
                // 每 tick 无条件 setPos，会把客户端本地 100Hz 的积分每帧"贴回"服务器 20Hz 的台阶，
                // 渲染出来就是锯齿（一抽一抽）。小偏差让本地积分自然收敛，只有真正漂了才纠正。
                double[] cur = new double[3];
                if (NativePhysics.bodyReadTranslation(world, existing.body(), cur)) {
                    double ex = ship.tickX() - cur[0];
                    double ey = ship.tickY() - cur[1];
                    double ez = ship.tickZ() - cur[2];
                    if (ex * ex + ey * ey + ez * ez > POSITION_CORRECTION_SQ) {
                        NativePhysics.bodySetTranslation(world, existing.body(),
                                ship.tickX(), ship.tickY(), ship.tickZ());
                    }
                }
                ships.put(ship.id(), new ShipBody(existing.body(), existing.blockCount(), motion));
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
                NativePhysics.bodyDestroy(world, body.body());
            }
        }
    }
}
