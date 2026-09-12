package com.mss.polymech.physics;

import com.mss.polymech.network.PhysicsBodySyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端物理体追踪：把"从世界里抠出来的建筑"登记为物理刚体，并把状态同步给客户端。
 *
 * <p>生命周期：</p>
 * <ol>
 *   <li>{@link #createFromRegion}：读取区域方块 → 从世界移除 → 建刚体（同一批方块同时作为体素碰撞体）
 *       → 发 CREATE（带方块快照）；</li>
 *   <li>每 tick：读刚体变换 → 发 UPDATE；</li>
 *   <li>{@link #drop}：按当前姿态把方块<b>放回世界</b>，销毁刚体并发 REMOVE。</li>
 * </ol>
 *
 * <p>刚体原点取区域最小角（整数），体素局部坐标即"方块 − 最小角"，因此碰撞体与渲染完全对齐。</p>
 */
public final class PhysicsBodyTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/Body");

    /** 单次 grab 的方块数上限（渲染目前逐方块发出，先保守限制）。 */
    public static final int MAX_BLOCKS = 4096;
    /** 区域边长上限。 */
    public static final int MAX_REGION = 32;

    private static final Map<Long, TrackedBody> BODIES = new HashMap<>();
    /** 存档同步节流（每 N tick 写一次变换到 SavedData）。 */
    private static final int PERSIST_INTERVAL = 40;
    private static int persistTimer;

    private record TrackedBody(ServerLevel level, long body, BlockPos origin,
                               List<PhysicsBodySyncPacket.BlockEntry> blocks,
                               double x, double y, double z,
                               float qx, float qy, float qz, float qw,
                               boolean frozen) {

        TrackedBody withTransform(double nx, double ny, double nz,
                                  float nqx, float nqy, float nqz, float nqw) {
            return new TrackedBody(level, body, origin, blocks, nx, ny, nz, nqx, nqy, nqz, nqw, frozen);
        }

        TrackedBody withFrozen(boolean value) {
            return new TrackedBody(level, body, origin, blocks, x, y, z, qx, qy, qz, qw, value);
        }

        /** 转成存档记录（方块打包成 [dx,dy,dz,stateId] 四元组）。 */
        PhysicsBodySavedData.Entry toSaved(long id) {
            int[] packed = new int[blocks.size() * 4];
            for (int i = 0; i < blocks.size(); i++) {
                PhysicsBodySyncPacket.BlockEntry e = blocks.get(i);
                packed[i * 4] = e.dx();
                packed[i * 4 + 1] = e.dy();
                packed[i * 4 + 2] = e.dz();
                packed[i * 4 + 3] = e.stateId();
            }
            return new PhysicsBodySavedData.Entry(id, level.dimension(), x, y, z, qx, qy, qz, qw, packed);
        }
    }

    private PhysicsBodyTracker() {
    }

    public static int bodyCount() {
        return BODIES.size();
    }

    public static List<Long> ids() {
        return new ArrayList<>(BODIES.keySet());
    }

    /** 诊断：id → 当前位置与方块数。 */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, TrackedBody> entry : BODIES.entrySet()) {
            TrackedBody t = entry.getValue();
            out.add(String.format("id=%d pos=(%.2f, %.2f, %.2f) 方块=%d",
                    entry.getKey(), t.x, t.y, t.z, t.blocks.size()));
        }
        return out;
    }

    /**
     * 把区域内的方块"抠出来"变成动态刚体。
     *
     * @return 新刚体 id；失败返回 -1
     */
    public static long createFromRegion(ServerLevel level, BlockPos a, BlockPos b) {
        if (!PhysicsNatives.isAvailable()) {
            return -1;
        }
        BlockPos min = new BlockPos(Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        BlockPos max = new BlockPos(Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        int sx = max.getX() - min.getX() + 1;
        int sy = max.getY() - min.getY() + 1;
        int sz = max.getZ() - min.getZ() + 1;
        if (sx > MAX_REGION || sy > MAX_REGION || sz > MAX_REGION) {
            return -1;
        }

        List<PhysicsBodySyncPacket.BlockEntry> blocks = new ArrayList<>();
        List<Long> cells = new ArrayList<>();
        outer:
        for (int dx = 0; dx < sx; dx++) {
            for (int dy = 0; dy < sy; dy++) {
                for (int dz = 0; dz < sz; dz++) {
                    BlockPos pos = min.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (blocks.size() >= MAX_BLOCKS) {
                        LOGGER.warn("[PolyMech] 区域方块数超过上限 {}，已截断", MAX_BLOCKS);
                        break outer;
                    }
                    blocks.add(new PhysicsBodySyncPacket.BlockEntry((short) dx, (short) dy, (short) dz,
                            PhysicsBodySyncPacket.stateId(state)));
                    cells.add(NativePhysics.packCell(dx, dy, dz));
                }
            }
        }
        if (blocks.isEmpty()) {
            return -1;
        }

        long world = PhysicsWorldManager.world(level);
        if (world <= 0) {
            return -1;
        }
        // 物理世界需要有地面可落：顺手为该区域建立/刷新地形快照（半径 2 区块）
        PhysicsWorldManager.terrain(level).update(min, 2);

        // 刚体原点 = 区域最小角；方块局部坐标即 (dx, dy, dz)
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                min.getX(), min.getY(), min.getZ(), 0.0, 0.0, 0.0, 1.0, 0.0);
        if (body <= 0) {
            return -1;
        }
        long[] packed = new long[cells.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = cells.get(i);
        }
        if (NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, packed, 0.6, 0.0) <= 0) {
            NativePhysics.bodyDestroy(world, body);
            return -1;
        }
        // 飞船需要自由旋转（实体接管才锁旋转）
        NativePhysics.bodyLockRotations(world, body, false);

        // 从世界移除原方块：它们现在只作为刚体存在
        for (PhysicsBodySyncPacket.BlockEntry entry : blocks) {
            level.removeBlock(min.offset(entry.dx(), entry.dy(), entry.dz()), false);
        }

        PhysicsBodySavedData saved = PhysicsBodySavedData.get(level.getServer());
        long id = saved.allocateId();
        TrackedBody tracked = new TrackedBody(level, body, min, blocks,
                min.getX(), min.getY(), min.getZ(), 0.0f, 0.0f, 0.0f, 1.0f, false);
        BODIES.put(id, tracked);
        saved.put(tracked.toSaved(id));
        broadcast(PhysicsBodySyncPacket.create(id, tracked.x, tracked.y, tracked.z,
                tracked.qx, tracked.qy, tracked.qz, tracked.qw, blocks), level);
        LOGGER.info("[PolyMech] 物理体 {} 已创建：方块 {} 个，原点 {}", id, blocks.size(), min);
        return id;
    }

    /**
     * 破坏物理体上的一个方块。
     *
     * @return 是否成功（方块存在且删除成功）
     */
    public static boolean breakBlock(long id, int dx, int dy, int dz) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null) {
            return false;
        }
        List<PhysicsBodySyncPacket.BlockEntry> blocks = new ArrayList<>(tracked.blocks);
        boolean removed = blocks.removeIf(e -> e.dx() == dx && e.dy() == dy && e.dz() == dz);
        if (!removed) {
            return false;
        }
        if (blocks.isEmpty()) {
            // 最后一块被敲掉 → 刚体没有意义了，直接清理
            dropWithoutRestoring(id);
            return true;
        }
        TrackedBody updated = new TrackedBody(tracked.level, tracked.body, tracked.origin, blocks,
                tracked.x, tracked.y, tracked.z, tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.frozen);
        BODIES.put(id, updated);
        rebuildCollider(updated);
        persist(updated, id);
        refresh(id, updated);
        return true;
    }

    /**
     * 在物理体上放置一个方块（相对某个已存在的方块的面）。
     *
     * @return 是否成功
     */
    public static boolean placeBlock(long id, int dx, int dy, int dz, int stateId) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null) {
            return false;
        }
        for (PhysicsBodySyncPacket.BlockEntry e : tracked.blocks) {
            if (e.dx() == dx && e.dy() == dy && e.dz() == dz) {
                return false; // 该位置已有方块
            }
        }
        if (tracked.blocks.size() >= MAX_BLOCKS) {
            return false;
        }
        List<PhysicsBodySyncPacket.BlockEntry> blocks = new ArrayList<>(tracked.blocks);
        blocks.add(new PhysicsBodySyncPacket.BlockEntry((short) dx, (short) dy, (short) dz, stateId));
        TrackedBody updated = new TrackedBody(tracked.level, tracked.body, tracked.origin, blocks,
                tracked.x, tracked.y, tracked.z, tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.frozen);
        BODIES.put(id, updated);
        rebuildCollider(updated);
        persist(updated, id);
        refresh(id, updated);
        return true;
    }

    /** 用当前方块列表重建体素碰撞体（先清掉旧的，避免旧形状残留）。 */
    private static void rebuildCollider(TrackedBody tracked) {
        long world = PhysicsWorldManager.world(tracked.level);
        if (world <= 0) {
            return;
        }
        NativePhysics.bodyClearColliders(world, tracked.body);
        long[] cells = new long[tracked.blocks.size()];
        for (int i = 0; i < cells.length; i++) {
            PhysicsBodySyncPacket.BlockEntry e = tracked.blocks.get(i);
            cells[i] = NativePhysics.packCell(e.dx(), e.dy(), e.dz());
        }
        NativePhysics.colliderAttachVoxels(world, tracked.body, 1.0, 1.0, 1.0, cells, 0.6, 0.0);
    }

    private static void persist(TrackedBody tracked, long id) {
        PhysicsBodySavedData.get(tracked.level.getServer()).put(tracked.toSaved(id));
    }

    /** 把最新的方块快照整包重发给客户端（简化实现：不做增量）。 */
    private static void refresh(long id, TrackedBody tracked) {
        broadcast(PhysicsBodySyncPacket.create(id, tracked.x, tracked.y, tracked.z,
                tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.blocks), tracked.level);
    }

    /** 直接销毁刚体与存档记录，不把方块放回世界（最后一块被破坏时用）。 */
    private static void dropWithoutRestoring(long id) {
        TrackedBody tracked = BODIES.remove(id);
        if (tracked == null) {
            return;
        }
        long world = PhysicsWorldManager.world(tracked.level);
        if (world > 0 && tracked.body > 0) {
            NativePhysics.bodyDestroy(world, tracked.body);
        }
        PhysicsBodySavedData.get(tracked.level.getServer()).remove(id);
        broadcast(PhysicsBodySyncPacket.remove(id), tracked.level);
    }

    /** 按当前姿态把方块放回世界（吸附到方块网格），并销毁刚体。 */
    public static boolean drop(long id) {
        TrackedBody tracked = BODIES.remove(id);
        if (tracked == null) {
            return false;
        }
        ServerLevel level = tracked.level;
        long world = PhysicsWorldManager.world(level);

        double ox = Math.round(tracked.x);
        double oy = Math.round(tracked.y);
        double oz = Math.round(tracked.z);

        Quaterniond rotation = new Quaterniond(tracked.qx, tracked.qy, tracked.qz, tracked.qw).normalize();
        Vector3d tmp = new Vector3d();
        for (PhysicsBodySyncPacket.BlockEntry entry : tracked.blocks) {
            tmp.set(entry.dx() + 0.5, entry.dy() + 0.5, entry.dz() + 0.5);
            rotation.transform(tmp);
            BlockPos pos = new BlockPos(
                    (int) Math.round(ox + tmp.x - 0.5),
                    (int) Math.round(oy + tmp.y - 0.5),
                    (int) Math.round(oz + tmp.z - 0.5));
            if (level.isLoaded(pos)) {
                level.setBlockAndUpdate(pos, PhysicsBodySyncPacket.stateFrom(entry.stateId()));
            }
        }

        if (world > 0 && tracked.body > 0) {
            NativePhysics.bodyDestroy(world, tracked.body);
        }
        PhysicsBodySavedData.get(level.getServer()).remove(id);
        broadcast(PhysicsBodySyncPacket.remove(id), level);
        return true;
    }

    /** 每 tick：同步所有物理体的变换；恢复出来的冻结体会在玩家靠近后解冻。 */
    public static void tick() {
        if (BODIES.isEmpty() || !PhysicsNatives.isAvailable()) {
            return;
        }
        double[] pos = new double[3];
        double[] rot = new double[4];
        double[] vel = new double[3];
        List<Long> stale = new ArrayList<>();
        boolean persistTick = ++persistTimer >= PERSIST_INTERVAL;
        if (persistTick) {
            persistTimer = 0;
        }
        PhysicsBodySavedData saved = persistTick
                ? PhysicsBodySavedData.get(BODIES.values().iterator().next().level.getServer())
                : null;
        for (Map.Entry<Long, TrackedBody> entry : BODIES.entrySet()) {
            TrackedBody tracked = entry.getValue();
            long world = PhysicsWorldManager.world(tracked.level);
            // 恢复出来的冻结体：等到有玩家靠近、地形碰撞体就绪后再切回动态，
            // 否则无人时它会自由落体掉出世界（区块没加载 → 地形快照是空的）
            if (world > 0 && tracked.frozen() && shouldUnfreeze(tracked)) {
                PhysicsWorldManager.terrain(tracked.level)
                        .update(BlockPos.containing(tracked.x, tracked.y, tracked.z), 2);
                NativePhysics.bodySetBodyType(world, tracked.body, NativePhysics.BODY_DYNAMIC);
                // 注意：必须用解冻后的实例继续本 tick 的后续处理，
                // 否则下面 withTransform 会带着旧的 frozen=true 把状态写回去 → 每 tick 反复解冻
                tracked = tracked.withFrozen(false);
                BODIES.put(entry.getKey(), tracked);
                LOGGER.info("[PolyMech] 物理体 {} 解冻（玩家靠近，地形就绪）", entry.getKey());
            }
            if (world <= 0
                    || !NativePhysics.bodyReadTranslation(world, tracked.body, pos)
                    || !NativePhysics.bodyReadRotation(world, tracked.body, rot)) {
                stale.add(entry.getKey());
                continue;
            }
            TrackedBody updated = tracked.withTransform(pos[0], pos[1], pos[2],
                    (float) rot[0], (float) rot[1], (float) rot[2], (float) rot[3]);
            BODIES.put(entry.getKey(), updated);
            // 线速度一并下发（MPS 的 SyncPhysicalBodyMove 同时发 Pos 与 LinSpeed）：
            // 客户端刚体只有带着速度，接触求解才会把玩家朝正确的方向推。
            double vx = 0.0;
            double vy = 0.0;
            double vz = 0.0;
            if (NativePhysics.bodyReadVelocity(world, tracked.body, vel)) {
                vx = vel[0];
                vy = vel[1];
                vz = vel[2];
            }
            double avx = 0.0;
            double avy = 0.0;
            double avz = 0.0;
            if (NativePhysics.bodyReadAngvel(world, tracked.body, vel)) {
                avx = vel[0];
                avy = vel[1];
                avz = vel[2];
            }
            broadcast(PhysicsBodySyncPacket.update(entry.getKey(), updated.x, updated.y, updated.z,
                    updated.qx, updated.qy, updated.qz, updated.qw, vx, vy, vz, avx, avy, avz), tracked.level);
            if (saved != null) {
                saved.updateTransform(entry.getKey(), updated.x, updated.y, updated.z,
                        updated.qx, updated.qy, updated.qz, updated.qw);
            }
        }
        for (Long id : stale) {
            drop(id);
        }
    }

    /**
     * 玩家是否已靠近到可以解冻（同时该区块必须已加载，否则地形快照建不起来）。
     */
    private static boolean shouldUnfreeze(TrackedBody tracked) {
        BlockPos pos = BlockPos.containing(tracked.x, tracked.y, tracked.z);
        if (!tracked.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        for (ServerPlayer player : tracked.level.players()) {
            if (player.distanceToSqr(tracked.x, tracked.y, tracked.z) < 96.0 * 96.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 进存档时恢复：按存档记录重建刚体（先建成固定体/冻结态，玩家靠近再解冻）。
     * <p>方块在世界里本来就已经是"被抠掉"的状态，所以这里不需要动世界，只重建刚体。</p>
     */
    public static void restore(MinecraftServer server) {
        if (!PhysicsNatives.isAvailable()) {
            return;
        }
        PhysicsBodySavedData saved = PhysicsBodySavedData.get(server);
        if (saved.isEmpty()) {
            return;
        }
        int restored = 0;
        for (PhysicsBodySavedData.Entry entry : new ArrayList<>(saved.all())) {
            ServerLevel level = server.getLevel(entry.dimension());
            if (level == null) {
                LOGGER.warn("[PolyMech] 物理体 {} 的维度 {} 未加载，跳过恢复",
                        entry.id(), entry.dimension().location());
                continue;
            }
            long world = PhysicsWorldManager.world(level);
            if (world <= 0) {
                continue;
            }
            List<PhysicsBodySyncPacket.BlockEntry> blocks = new ArrayList<>();
            List<Long> cells = new ArrayList<>();
            int[] packed = entry.blocks();
            for (int i = 0; i + 3 < packed.length; i += 4) {
                int dx = packed[i];
                int dy = packed[i + 1];
                int dz = packed[i + 2];
                blocks.add(new PhysicsBodySyncPacket.BlockEntry((short) dx, (short) dy, (short) dz, packed[i + 3]));
                cells.add(NativePhysics.packCell(dx, dy, dz));
            }
            if (blocks.isEmpty()) {
                saved.remove(entry.id());
                continue;
            }
            long[] cellArray = new long[cells.size()];
            for (int i = 0; i < cellArray.length; i++) {
                cellArray[i] = cells.get(i);
            }

            // 冻结为固定体：无人时不会被重力带走
            long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                    entry.x(), entry.y(), entry.z(),
                    entry.qx(), entry.qy(), entry.qz(), entry.qw(), 0.0);
            if (body <= 0) {
                continue;
            }
            if (NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, cellArray, 0.6, 0.0) <= 0) {
                NativePhysics.bodyDestroy(world, body);
                continue;
            }
            TrackedBody tracked = new TrackedBody(level, body, BlockPos.containing(entry.x(), entry.y(), entry.z()),
                    blocks, entry.x(), entry.y(), entry.z(),
                    entry.qx(), entry.qy(), entry.qz(), entry.qw(), true);
            BODIES.put(entry.id(), tracked);
            restored++;
        }
        LOGGER.info("[PolyMech] 物理体恢复完成：{} / {} 个", restored, saved.all().size());
    }

    /** 新玩家进入：补发其所在维度的全部物理体。 */
    public static void sendAllTo(ServerPlayer player) {
        for (Map.Entry<Long, TrackedBody> entry : BODIES.entrySet()) {
            TrackedBody tracked = entry.getValue();
            if (tracked.level != player.serverLevel()) {
                continue;
            }
            player.connection.send(PhysicsBodySyncPacket.create(entry.getKey(),
                    tracked.x, tracked.y, tracked.z,
                    tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.blocks));
        }
    }

    /** 对刚体施加冲量（质量感知：同样冲量下轻物飞得远、重物几乎不动）。 */
    public static boolean applyImpulse(long id, double ix, double iy, double iz) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null) {
            return false;
        }
        long world = PhysicsWorldManager.world(tracked.level);
        boolean ok = world > 0 && NativePhysics.bodyApplyImpulse(world, tracked.body, ix, iy, iz);
        LOGGER.info("[PolyMech] 推动物理体 {}: 冲量=({}, {}, {}) 冻结={} 结果={}",
                id, String.format("%.2f", ix), String.format("%.2f", iy), String.format("%.2f", iz),
                tracked.frozen, ok);
        return ok;
    }

    /**
     * 目标刚体是否在某玩家附近（服务端校验编辑/推动请求）。
     * <p>注意：刚体原点取的是区域<b>最小角</b>，直接拿它算距离会把"大船的远端"判成超距，
     * 于是推送请求被静默拒绝 —— 这里改为用方块包围盒算到盒子的最近距离。</p>
     */
    /** 取刚体当前世界坐标（开关条件/诊断用）；不存在返回 null。 */
    public static double[] positionOf(long id) {
        TrackedBody tracked = BODIES.get(id);
        return tracked == null ? null : new double[]{tracked.x, tracked.y, tracked.z};
    }

    public static boolean isNear(long id, ServerPlayer player, double range) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null || tracked.level != player.serverLevel()) {
            return false;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PhysicsBodySyncPacket.BlockEntry e : tracked.blocks) {
            minX = Math.min(minX, e.dx());
            maxX = Math.max(maxX, e.dx());
            minY = Math.min(minY, e.dy());
            maxY = Math.max(maxY, e.dy());
            minZ = Math.min(minZ, e.dz());
            maxZ = Math.max(maxZ, e.dz());
        }
        if (minX == Integer.MAX_VALUE) {
            return false;
        }
        double bx = tracked.x, by = tracked.y, bz = tracked.z;
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        double dx = Math.max(0.0, Math.max(bx + minX - px, px - (bx + maxX + 1.0)));
        double dy = Math.max(0.0, Math.max(by + minY - py, py - (by + maxY + 1.0)));
        double dz = Math.max(0.0, Math.max(bz + minZ - pz, pz - (bz + maxZ + 1.0)));
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    /** 取某刚体的方块快照（客户端做射线/预测用）。 */
    public static List<PhysicsBodySyncPacket.BlockEntry> blocksOf(long id) {
        TrackedBody tracked = BODIES.get(id);
        return tracked == null ? List.of() : tracked.blocks;
    }

    /** 锁定/解锁刚体旋转（薄板落下会翻滚，调试时可冻结）。 */
    public static boolean lockRotations(long id, boolean locked) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null) {
            return false;
        }
        long world = PhysicsWorldManager.world(tracked.level);
        return world > 0 && NativePhysics.bodyLockRotations(world, tracked.body, locked);
    }

    /** 施加速度（推动/发射）。 */
    public static boolean push(long id, double vx, double vy, double vz) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null) {
            return false;
        }
        long world = PhysicsWorldManager.world(tracked.level);
        return world > 0 && NativePhysics.bodySetVelocity(world, tracked.body, vx, vy, vz);
    }

    /**
     * 服务端停止：销毁全部 Rapier 刚体。
     * <p><b>不要</b>动 {@link PhysicsBodySavedData} —— 方块已被抠除，存档记录是它们的唯一副本。</p>
     */
    public static void clear() {
        for (TrackedBody tracked : BODIES.values()) {
            long world = PhysicsWorldManager.world(tracked.level);
            if (world > 0 && tracked.body > 0) {
                NativePhysics.bodyDestroy(world, tracked.body);
            }
        }
        BODIES.clear();
    }

    private static void broadcast(PhysicsBodySyncPacket packet, ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, packet);
    }
}
