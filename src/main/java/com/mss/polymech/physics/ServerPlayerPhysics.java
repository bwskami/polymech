package com.mss.polymech.physics;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.space.SpacePlayerData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端玩家物理（对应 {@code space} 的 MPS 服务端半边）。
 *
 * <p><b>为什么必须有它</b>：vanilla 的 {@code ServerGamePacketListenerImpl.handleMovePlayer}
 * 会自己把玩家按上报位移移动一遍（{@code player.move(MoverType.PLAYER, delta)}），
 * 然后比较"服务端位置"与"客户端上报位置"的残差：残差² &gt; 0.0625 就判定
 * {@code moved wrongly} 并**拒绝采纳客户端位置** → 客户端被拉回（表现为"走一会闪一下"）。</p>
 *
 * <p>如果只有客户端跑物理，服务端的原版移动与客户端的物理位置会持续漂移 → 反复触发该校验。
 * 反之，两端都用<b>同一套物理驱动</b>（同样的位移 → 同样的冲量 → 同样的加速度），
 * 残差始终接近 0，校验自然通过 —— 这正是 {@code space}(MPS) 在
 * {@code MixinEntity.move} 里对客户端/服务端分别取 world 的原因。</p>
 *
 * <p>开关条件必须与客户端 {@code ClientPhysics#shouldSimulate} <b>严格一致</b>，
 * 否则两端一个跑物理、一个跑原版，反而会制造漂移。</p>
 */
public final class ServerPlayerPhysics {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/ServerPlayer");

    public static final double TICK_SECONDS = 1.0 / 20.0;
    private static final double PLAYER_MASS = 50.0;
    /** MPS 力系数：F = delta × 70.0（牛顿），见 space 的 {@code PhysicalEntity.move()}。 */
    private static final double MPS_FORCE = 70.0;
    /** 该力作用时长（秒），MPS 用 0.05。 */
    private static final double MPS_FORCE_SECONDS = 0.05;
    /** 等价冲量系数 = 70 × 0.05 = 3.5（N·s per 格位移）→ Δv = 0.07·delta。 */
    private static final double MPS_IMPULSE = MPS_FORCE * MPS_FORCE_SECONDS;
    /** 地形快照刷新间隔（tick）。 */
    private static final int TERRAIN_INTERVAL = 20;
    /** 附近有物理体才启用物理的半径（与客户端一致）。 */
    private static final double BODY_RANGE = 64.0;

    private static final Map<UUID, Long> BODIES = new HashMap<>();
    private static final Map<UUID, Long> BODY_WORLD = new HashMap<>();
    /** 本 tick 真正被物理接管的玩家。 */
    private static final java.util.Set<UUID> DRIVING = new java.util.HashSet<>();
    private static final Map<UUID, Integer> LAST_TERRAIN = new HashMap<>();
    private static final Map<UUID, Integer> LAST_CHUNK = new HashMap<>();
    private static final Map<UUID, double[]> SAFE = new HashMap<>();
    private static final Map<UUID, double[]> HALF = new HashMap<>();

    private ServerPlayerPhysics() {
    }

    /** 由 {@code EntityPhysicsDriveMixin} 调用；返回 true 表示已接管这次移动。 */
    public static boolean tryDrive(net.minecraft.world.entity.Entity entity, Vec3 delta) {
        if (!(entity instanceof ServerPlayer player)) {
            return false;
        }
        return drive(player, delta);
    }

    /**
     * 每服务端 tick：把所有正在被物理接管的玩家位置回写。
     *
     * <p>不能只靠 {@code Entity.move} 里 setPos 重定向那次回写 —— 原版在位移≈0 时不触达它，
     * 于是玩家站着不动时物理仍在后台积分、位置却没人写，一按 WASD 就跳到后台算出的位置。</p>
     */
    public static void writeBackAll() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || DRIVING.isEmpty()) {
            return;
        }
        double[] pos = new double[3];
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                if (!DRIVING.contains(player.getUUID()) || player.isSpectator()
                        || player.getAbilities().flying) {
                    continue;
                }
                Long body = BODIES.get(player.getUUID());
                Long world = BODY_WORLD.get(player.getUUID());
                double[] half = HALF.get(player.getUUID());
                if (body == null || world == null || half == null || body <= 0 || world <= 0) {
                    continue;
                }
                if (NativePhysics.bodyReadTranslation(world, body, pos)) {
                    player.setPos(pos[0], pos[1] - half[1], pos[2]);
                }
            }
        }
    }

    /**
     * 该玩家本 tick 是否由物理接管了位置。
     * <p>供 {@code ServerMoveTrustMixin} 判断：物理算出来的位置原版校验不了
     * （{@code moved wrongly} / {@code isPlayerCollidingWithAnythingNew}），
     * 命中就会把玩家 teleport 回上一 tick 的位置 —— 表现就是"撞到东西被弹回来"。</p>
     */
    public static boolean isDriving(ServerPlayer player) {
        return DRIVING.contains(player.getUUID());
    }

    /**
     * 把刚体对齐到实体当前位置（服务端采纳客户端上报位置后调用）。
     * <p>否则服务端刚体会停在"物理自己算的位置"，与玩家位置越差越远。</p>
     */
    public static void snapTo(ServerPlayer player) {
        Long body = BODIES.get(player.getUUID());
        Long world = BODY_WORLD.get(player.getUUID());
        double[] half = HALF.get(player.getUUID());
        if (body == null || world == null || half == null || body <= 0 || world <= 0) {
            return;
        }
        NativePhysics.bodySetTranslation(world, body,
                player.getX(), player.getY() + half[1], player.getZ());
        NativePhysics.bodySetVelocity(world, body, 0.0, 0.0, 0.0);
    }

    private static boolean drive(ServerPlayer player, Vec3 delta) {
        DRIVING.remove(player.getUUID());
        if (!PhysicsNatives.isAvailable()) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (!shouldSimulate(player)) {
            forget(player);
            return false;
        }
        long world = PhysicsWorldManager.world(level);
        if (world <= 0) {
            return false;
        }

        // 地形快照：玩家附近必须有碰撞体，否则物理驱动的玩家会穿地
        updateTerrain(player, level, world);

        long body = ensureBody(player, level, world);
        if (body <= 0) {
            return false;
        }

        // 碰撞体姿态跟玩家 6DOF 朝向：人滚转/俯仰时碰撞箱也要跟着转
        SpacePlayerData data = SpacePlayerData.get(player);
        if (data.isInitialized()) {
            org.joml.Quaternionf q = data.orientation(new org.joml.Quaternionf());
            NativePhysics.bodySetRotation(world, body, q.x(), q.y(), q.z(), q.w(), true);
        }

        // 创造飞行：位置交回原版，但刚体保留并跟随玩家 —— 飞行中照样与飞船/建筑碰撞。
        // （旁观者在上面的 shouldSimulate 里已经排除，这里到不了。）
        if (player.getAbilities().flying && !player.isSpectator()) {
            double[] half = HALF.get(player.getUUID());
            double halfHeight = half == null ? 0.9 : half[1];
            NativePhysics.bodySetTranslation(world, body,
                    player.getX() + delta.x, player.getY() + delta.y + halfHeight, player.getZ() + delta.z);
            NativePhysics.bodySetMotion(world, body,
                    delta.x * 20.0, delta.y * 20.0, delta.z * 20.0, 0.0, 0.0, 0.0, true);
            return false;
        }

        // ── MPS 力模型（与客户端 ClientPhysics.drive 严格一致）──
        //   space: applyForce(new Force(delta × 70.0, 0.05))  质量 50kg、无阻尼
        //   世界每 MC tick 走 5 × 1/100s = 0.05s，正好是该力的作用时长，
        //   故等价冲量 = delta × 70 × 0.05 = delta × 3.5 (N·s) → Δv = 0.07·delta
        //   不做速度反馈：速度只累加不衰减（惯性/漂移是这个模型的目的，不是 bug）
        NativePhysics.bodyApplyImpulse(world, body,
                delta.x * MPS_IMPULSE, delta.y * MPS_IMPULSE, delta.z * MPS_IMPULSE);

        double[] pos = new double[3];
        if (!NativePhysics.bodyReadTranslation(world, body, pos)) {
            return false;
        }
        double[] half = HALF.get(player.getUUID());
        double halfHeight = half == null ? 0.9 : half[1];

        double minY = level.getMinBuildHeight() - 64.0;
        double[] safe = SAFE.get(player.getUUID());
        if (pos[1] - halfHeight < minY && safe != null) {
            LOGGER.warn("[PolyMech] 服务端物理位置异常（y={}），复位", pos[1]);
            player.teleportTo(safe[0], safe[1], safe[2]);
            NativePhysics.bodySetTranslation(world, body, safe[0], safe[1] + halfHeight, safe[2]);
            NativePhysics.bodySetVelocity(world, body, 0.0, 0.0, 0.0);
            return false;
        }
        player.setPos(pos[0], pos[1] - halfHeight, pos[2]);

        if (player.getY() > minY + 32) {
            SAFE.put(player.getUUID(), new double[]{player.getX(), player.getY(), player.getZ()});
        }
        DRIVING.add(player.getUUID());
        return true;
    }

    /** 与客户端 {@code ClientPhysics#shouldSimulate} 完全一致的开关条件。 */
    private static boolean shouldSimulate(ServerPlayer player) {
        // 旁观者不参与物理：没有刚体、不产生任何碰撞。
        if (player.isSpectator()) {
            return false;
        }
        // 创造飞行**不**排除：飞行时刚体保留（参与碰撞），只是位置交回原版（见 drive()）。
        if (player.level().dimension().equals(PlanetDimensions.SPACE)) {
            return true;
        }
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        for (long id : PhysicsBodyTracker.ids()) {
            double[] p = PhysicsBodyTracker.positionOf(id);
            if (p == null) {
                continue;
            }
            double dx = p[0] - px;
            double dy = p[1] - py;
            double dz = p[2] - pz;
            if (dx * dx + dy * dy + dz * dz < BODY_RANGE * BODY_RANGE) {
                return true;
            }
        }
        return false;
    }

    private static void updateTerrain(ServerPlayer player, ServerLevel level, long world) {
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        int chunkKey = cx * 31 + cz;
        Integer lastChunk = LAST_CHUNK.get(player.getUUID());
        int now = player.tickCount;
        Integer last = LAST_TERRAIN.get(player.getUUID());
        boolean chunkChanged = lastChunk == null || lastChunk != chunkKey;
        if (!chunkChanged && last != null && now - last < TERRAIN_INTERVAL) {
            return;
        }
        LAST_CHUNK.put(player.getUUID(), chunkKey);
        LAST_TERRAIN.put(player.getUUID(), now);
        PhysicsWorldManager.terrain(level).update(BlockPos.containing(player.getX(), player.getY(), player.getZ()), 1);
    }

    private static long ensureBody(ServerPlayer player, ServerLevel level, long world) {
        float halfWidth = Math.max(0.05f, player.getBbWidth() * 0.5f);
        float halfHeight = Math.max(0.05f, player.getBbHeight() * 0.5f);
        Long existing = BODIES.get(player.getUUID());
        Long existingWorld = BODY_WORLD.get(player.getUUID());
        if (existing != null && existing > 0 && existingWorld != null && existingWorld == world) {
            return existing;
        }
        if (existing != null && existing > 0 && existingWorld != null) {
            NativePhysics.bodyDestroy(existingWorld, existing);
            BODIES.remove(player.getUUID());
        }
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                player.getX(), player.getY() + halfHeight, player.getZ(),
                0.0, 0.0, 0.0, 1.0, PLAYER_MASS);
        if (body <= 0) {
            return 0;
        }
        NativePhysics.colliderAttachCuboid(world, body, halfWidth, halfHeight, halfWidth, 0.6, 0.0);
        // 姿态每 tick 按玩家 6DOF 朝向写入（见 drive()），这里不锁旋转
        BODIES.put(player.getUUID(), body);
        BODY_WORLD.put(player.getUUID(), world);
        HALF.put(player.getUUID(), new double[]{halfWidth, halfHeight});
        LOGGER.info("[PolyMech] 服务端玩家物理体已创建: {} 维度 {}", player.getName().getString(),
                level.dimension().location());
        return body;
    }

    /** 玩家下线/切维度/关闭物理时清理刚体。 */
    public static void forget(ServerPlayer player) {
        DRIVING.remove(player.getUUID());
        Long body = BODIES.remove(player.getUUID());
        Long world = BODY_WORLD.remove(player.getUUID());
        LAST_TERRAIN.remove(player.getUUID());
        LAST_CHUNK.remove(player.getUUID());
        SAFE.remove(player.getUUID());
        HALF.remove(player.getUUID());
        if (body != null && body > 0 && world != null && world > 0) {
            NativePhysics.bodyDestroy(world, body);
        }
    }

    /** 服务端停止：清空全部。 */    public static void clear() {
        for (Map.Entry<UUID, Long> entry : BODIES.entrySet()) {
            Long world = BODY_WORLD.get(entry.getKey());
            if (world != null && world > 0 && entry.getValue() > 0) {
                NativePhysics.bodyDestroy(world, entry.getValue());
            }
        }
        BODIES.clear();
        BODY_WORLD.clear();
        LAST_TERRAIN.clear();
        LAST_CHUNK.clear();
        SAFE.clear();
        HALF.clear();
        DRIVING.clear();
    }
}
