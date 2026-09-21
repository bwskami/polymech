package com.mss.polymech.physics;

import com.mss.polymech.network.PhysicsBodyBlockEntityPacket;
import com.mss.polymech.network.PhysicsBodyMoveBatchPacket;
import com.mss.polymech.network.PhysicsBodySyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import java.util.UUID;

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

    /** 碰撞体重建耗时超过它就告警（毫秒）—— 用来定位"每隔几秒顿一下"。 */
    private static final long REBUILD_WARN_MS = 8L;
    /** 同类告警的最小间隔（纳秒）：避免一次周期性重建把日志刷爆。 */
    private static final long REBUILD_WARN_INTERVAL_NANOS = 3_000_000_000L;
    private static long lastRebuildWarnNanos;

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

        /** 转成存档记录（方块打包成 [dx,dy,dz,stateId] 四元组；含速度与碰撞组）。 */
        PhysicsBodySavedData.Entry toSaved(long id) {
            int[] packed = new int[blocks.size() * 4];
            for (int i = 0; i < blocks.size(); i++) {
                PhysicsBodySyncPacket.BlockEntry e = blocks.get(i);
                packed[i * 4] = e.dx();
                packed[i * 4 + 1] = e.dy();
                packed[i * 4 + 2] = e.dz();
                packed[i * 4 + 3] = e.stateId();
            }
            // 速度与碰撞组不放在 TrackedBody 里：它们分别由 tick() 与命令写进这两张表，
            // 记进 record 会让 withTransform/withFrozen 每次都被迫多带 7 个字段。
            double[] motion = MOTION.get(id);
            int[] groups = COLLISION_GROUPS.get(id);
            return new PhysicsBodySavedData.Entry(id, level.dimension(), x, y, z, qx, qy, qz, qw,
                    motion == null ? 0.0 : motion[0],
                    motion == null ? 0.0 : motion[1],
                    motion == null ? 0.0 : motion[2],
                    motion == null ? 0.0 : motion[3],
                    motion == null ? 0.0 : motion[4],
                    motion == null ? 0.0 : motion[5],
                    groups == null ? PhysicsBodySavedData.GROUP_UNSET : groups[0],
                    groups == null ? PhysicsBodySavedData.GROUP_UNSET : groups[1],
                    ProjectionManager.slotOf(id), packed);
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

    /** 诊断：id → 当前位置、速度与方块数。 */
    public static List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Long, TrackedBody> entry : BODIES.entrySet()) {
            TrackedBody t = entry.getValue();
            double[] m = MOTION.get(entry.getKey());
            if (m == null) {
                m = ZERO_MOTION;
            }
            out.add(String.format("id=%d pos=(%.2f, %.2f, %.2f) 速度=(%.2f, %.2f, %.2f) 方块=%d 质量=%.1f kg%s",
                    entry.getKey(), t.x, t.y, t.z, m[0], m[1], m[2], t.blocks.size(),
                    massOf(entry.getKey()),
                    t.frozen() ? " [冻结]" : ""));
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
        // 投影维度要用的"方块 + 方块实体 NBT"清单：必须在从世界移除方块<b>之前</b>抓，
        // 否则方块实体已经被清掉，机器/熔炉的内部状态就丢了。
        List<ProjectionManager.BlockToWrite> projectionWrites = new ArrayList<>();
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
                    CompoundTag beTag = null;
                    if (state.hasBlockEntity()) {
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) {
                            beTag = be.saveWithFullMetadata(level.registryAccess());
                            // 关键：先把方块实体从世界摘掉、并清空内容物，再让下面的 removeBlock 移除方块。
                            // 否则容器在"方块被移除"那一刻会把里面的东西喷成掉落物，
                            // 而我们的 beTag 里同样有一份 —— 放回世界时就成了两份物品（复制 bug）。
                            if (be instanceof net.minecraft.world.Container container) {
                                container.clearContent();
                            }
                            level.removeBlockEntity(pos);
                        }
                    }
                    projectionWrites.add(new ProjectionManager.BlockToWrite(dx, dy, dz, state, beTag));
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
        long collider = NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, packed,
                PhysicsMaterials.BODY_FRICTION, 0.0);
        if (collider <= 0) {
            NativePhysics.bodyDestroy(world, body);
            return -1;
        }
        PhysicsMaterials.apply(world, collider, PhysicsMaterials.BODY_FRICTION, 0.0);
        // 质量下限：一格方块在 Rapier 里只有 1 kg，比玩家还轻 50 倍 —— 不补就会被玩家撞飞
        applyDensityAndFloor(world, body, collider, blocks);
        // 飞船需要自由旋转（实体接管才锁旋转）
        NativePhysics.bodyLockRotations(world, body, false);

        // 从世界移除原方块：它们现在只作为刚体存在
        for (PhysicsBodySyncPacket.BlockEntry entry : blocks) {
            level.removeBlock(min.offset(entry.dx(), entry.dy(), entry.dz()), false);
        }

        PhysicsBodySavedData saved = PhysicsBodySavedData.get(level.getServer());
        long id = saved.allocateId();

        // 分配投影地皮并把方块（含方块实体 NBT）写进去。
        // 那边是真实区块 + 真实方块实体 —— 机器/红石/熔炉在太空结构上照常 tick；
        // 刚体这边只负责碰撞与运动。维度缺失（返回 -1）时物理体照常建，只是没有方块逻辑。
        int slot = ProjectionManager.allocate(id);
        if (slot >= 0) {
            ProjectionManager.writeBlocks(slot, projectionWrites);
            LOGGER.info("[PolyMech] 物理体 {} 已投影到地皮 {}：{} 个方块（含方块实体）",
                    id, slot, projectionWrites.size());
        }

        TrackedBody tracked = new TrackedBody(level, body, min, blocks,
                min.getX(), min.getY(), min.getZ(), 0.0f, 0.0f, 0.0f, 1.0f, false);
        BODIES.put(id, tracked);
        saved.put(tracked.toSaved(id));
        broadcast(PhysicsBodySyncPacket.create(id, tracked.x, tracked.y, tracked.z,
                tracked.qx, tracked.qy, tracked.qz, tracked.qw, blocks), level);
        broadcastBlockEntities(id, level);
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
        // 同步到投影地皮：把那块从隐藏维度里也清掉（含它的方块实体）
        int slot = ProjectionManager.slotOf(id);
        if (slot >= 0) {
            ProjectionManager.clearBlocks(slot, List.of(
                    new PhysicsBodySyncPacket.BlockEntry((short) dx, (short) dy, (short) dz, 0)));
        }
        if (blocks.isEmpty()) {
            // 最后一块被敲掉 → 刚体没有意义了，直接清理
            dropWithoutRestoring(id);
            return true;
        }
        TrackedBody updated = new TrackedBody(tracked.level, tracked.body, tracked.origin, blocks,
                tracked.x, tracked.y, tracked.z, tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.frozen);
        BODIES.put(id, updated);
        rebuildCollider(id, updated);
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
        rebuildCollider(id, updated);
        persist(updated, id);
        refresh(id, updated);
        // 同步到投影地皮：否则"方块缓存里有、隐藏维度里没有"，机器那边看不到这块方块
        int slot = ProjectionManager.slotOf(id);
        if (slot >= 0) {
            ProjectionManager.writeBlocks(slot, List.of(new ProjectionManager.BlockToWrite(
                    dx, dy, dz, net.minecraft.world.level.block.Block.stateById(stateId), null)));
        }
        return true;
    }

    /**
     * 在世界里"放一块"，但走物理体：<b>并入面相邻的现有物理体，找不到就由这一块新建一个物理体</b>。
     *
     * <p>用途：太空维度里玩家摆出来的方块应当是物理体（画风统一），不再写世界方块。
     * {@link #placeBlock} 只能往"已存在的物理体"上加，没法从零起一个新结构 ——
     * 这里补上那一步。</p>
     *
     * <p>刚体质量由碰撞体推导（密度 1，与 {@link #createFromRegion} 一致），
     * 所以"一块一块搭起来"时质量随方块数一起长。</p>
     *
     * <p><b>性能</b>：邻接查找目前是"遍历所有物理体 × 它们的方块"（O(体数 × 方块数)）。
     * 建造是低频操作，先这样；体数上千后再上空间哈希。</p>
     *
     * @return 目标物理体 id；世界该格已被占用、原生层不可用或超限时返回 -1
     */
    public static long placeBlockAt(ServerLevel level, BlockPos pos, int stateId) {
        if (!PhysicsNatives.isAvailable()) {
            return -1;
        }
        // 世界该格必须为空：物理体与真实方块不重叠，避免以后 drop 回世界时打架
        if (!level.getBlockState(pos).isAir()) {
            return -1;
        }
        // ① 找面相邻（曼哈顿距离 1）的现有物理体，并进去
        for (Map.Entry<Long, TrackedBody> entry : BODIES.entrySet()) {
            TrackedBody tracked = entry.getValue();
            if (tracked.level != level) {
                continue;
            }
            for (PhysicsBodySyncPacket.BlockEntry e : tracked.blocks) {
                int wx = tracked.origin.getX() + e.dx();
                int wy = tracked.origin.getY() + e.dy();
                int wz = tracked.origin.getZ() + e.dz();
                int manhattan = Math.abs(wx - pos.getX())
                        + Math.abs(wy - pos.getY())
                        + Math.abs(wz - pos.getZ());
                if (manhattan == 1) {
                    int dx = pos.getX() - tracked.origin.getX();
                    int dy = pos.getY() - tracked.origin.getY();
                    int dz = pos.getZ() - tracked.origin.getZ();
                    return placeBlock(entry.getKey(), dx, dy, dz, stateId) ? entry.getKey() : -1;
                }
            }
        }
        // ② 没有相邻体 → 以这一块新建刚体（原点 = 该格，局部坐标 (0,0,0)）
        long world = PhysicsWorldManager.world(level);
        if (world <= 0) {
            return -1;
        }
        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_DYNAMIC,
                pos.getX(), pos.getY(), pos.getZ(), 0.0, 0.0, 0.0, 1.0, 0.0);
        if (body <= 0) {
            return -1;
        }
        long[] cells = {NativePhysics.packCell(0, 0, 0)};
        long collider = NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, cells,
                PhysicsMaterials.BODY_FRICTION, 0.0);
        if (collider <= 0) {
            NativePhysics.bodyDestroy(world, body);
            return -1;
        }
        PhysicsMaterials.apply(world, collider, PhysicsMaterials.BODY_FRICTION, 0.0);
        // 单块体同样要补质量下限（否则 1 kg，"人肉推进器"一碰就飞）
        // 单块体：同样按材质密度 + 质量下限（1 格石头 ≈2500 kg，1 格羊毛 ≈200 kg）
        applyDensityAndFloor(world, body, collider,
                List.of(new PhysicsBodySyncPacket.BlockEntry((short) 0, (short) 0, (short) 0, stateId)));
        // 结构需要自由旋转（与 createFromRegion 一致）
        NativePhysics.bodyLockRotations(world, body, false);

        List<PhysicsBodySyncPacket.BlockEntry> blocks = new ArrayList<>();
        blocks.add(new PhysicsBodySyncPacket.BlockEntry((short) 0, (short) 0, (short) 0, stateId));
        PhysicsBodySavedData saved = PhysicsBodySavedData.get(level.getServer());
        long id = saved.allocateId();
        // 单块体同样要有投影地皮，否则这块方块在隐藏维度里不存在、将来无法被 tick/交互
        int slot = ProjectionManager.allocate(id);
        if (slot >= 0) {
            List<ProjectionManager.BlockToWrite> writes = new ArrayList<>();
            writes.add(new ProjectionManager.BlockToWrite(0, 0, 0,
                    net.minecraft.world.level.block.Block.stateById(stateId), null));
            ProjectionManager.writeBlocks(slot, writes);
        }
        TrackedBody tracked = new TrackedBody(level, body, pos, blocks,
                pos.getX(), pos.getY(), pos.getZ(), 0.0f, 0.0f, 0.0f, 1.0f, false);
        BODIES.put(id, tracked);
        saved.put(tracked.toSaved(id));
        broadcast(PhysicsBodySyncPacket.create(id, tracked.x, tracked.y, tracked.z,
                tracked.qx, tracked.qy, tracked.qz, tracked.qw, blocks), level);
        broadcastBlockEntities(id, level);
        LOGGER.info("[PolyMech] 物理体 {} 由单块创建于 {}", id, pos);
        return id;
    }

    /**
     * 物理体的碰撞组（bodyId → {membership, filter}）。
     *
     * <p>没登记 = 默认（与所有组交互，即旧的 {@code colliderAttachVoxels} 行为）。
     * 登记过之后，每次重建碰撞体都会带上它 —— 否则"破坏/放置方块触发重建"会把组冲掉。</p>
     */
    private static final Map<Long, int[]> COLLISION_GROUPS = new HashMap<>();

    /**
     * 每个物理体的最近一次运动状态 {@code {vx,vy,vz, avx,avy,avz}}。
     *
     * <p>由 {@link #tick()} 每 tick 原地更新（不重新分配，避免每体每 tick 一个数组），
     * 存档与诊断从它取速度。销毁物理体时必须清 —— 否则 id 回收后会串到下一个体上。</p>
     */
    private static final Map<Long, double[]> MOTION = new HashMap<>();

    /** 诊断用零运动（避免每次都 new 一个数组）。 */
    private static final double[] ZERO_MOTION = new double[6];

    /**
     * 从存档恢复出来、但**还没到时机施加**的速度。
     *
     * <p>恢复时物理体先建成固定体（冻结，防止无人时被重力带走），而固定体的速度会被求解器
     * 清零 —— 所以速度必须等"解冻切回动态"那一刻再写回去，否则"重启后巡航中的船停住"依旧。</p>
     */
    private static final Map<Long, double[]> RESTORED_MOTION = new HashMap<>();

    /**
     * 设置某物理体的碰撞组，并立即重建它的碰撞体（改完立刻生效）。
     *
     * <p>需要的原生能力：ABI ≥ {@link PhysicsNatives#MIN_ABI_COLLISION_GROUPS}；
     * 否则返回 false（调用方给提示）。</p>
     */
    public static boolean setCollisionGroups(long id, int membership, int filter) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null || !PhysicsNatives.hasCollisionGroups()) {
            return false;
        }
        COLLISION_GROUPS.put(id, new int[]{membership, filter});
        rebuildCollider(id, tracked);
        return true;
    }

    /** 读当前登记的碰撞组（诊断用）；未登记返回 null。 */
    public static int[] collisionGroupsOf(long id) {
        return COLLISION_GROUPS.get(id);
    }

    /**
     * 方块集合在刚体局部空间的包围盒中心。
     *
     * <p>质量下限的附加质量必须挂在这里，不能挂原点：刚体原点是区域的<b>最小角</b>，
     * 挂在原点会把合成质心拽到角落 —— 表现是"整条船绕着一个角转"。
     * 方块 (x) 占 [x, x+1]，所以中心是 (min+max+1)/2。</p>
     */
    private static double[] localCenter(List<PhysicsBodySyncPacket.BlockEntry> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (PhysicsBodySyncPacket.BlockEntry e : blocks) {
            minX = Math.min(minX, e.dx());
            maxX = Math.max(maxX, e.dx());
            minY = Math.min(minY, e.dy());
            maxY = Math.max(maxY, e.dy());
            minZ = Math.min(minZ, e.dz());
            maxZ = Math.max(maxZ, e.dz());
        }
        if (maxX < minX) {
            return new double[]{0.5, 0.5, 0.5};
        }
        return new double[]{
                (minX + maxX + 1) / 2.0,
                (minY + maxY + 1) / 2.0,
                (minZ + maxZ + 1) / 2.0};
    }

    /** 给刚体套质量下限（质心取结构中心；碰撞体质量由密度表算好后传进来）。 */
    private static void applyMassFloor(long world, long body, double colliderMass,
                                       List<PhysicsBodySyncPacket.BlockEntry> blocks) {
        double[] c = localCenter(blocks);
        PhysicsBodyMass.apply(world, body, colliderMass, blocks.size(), c[0], c[1], c[2]);
    }

    /** 方块缓存 → BlockState 列表（算密度/质量用）。 */
    private static List<BlockState> statesOf(List<PhysicsBodySyncPacket.BlockEntry> blocks) {
        List<BlockState> states = new ArrayList<>(blocks.size());
        for (PhysicsBodySyncPacket.BlockEntry e : blocks) {
            states.add(PhysicsBodySyncPacket.stateFrom(e.stateId()));
        }
        return states;
    }

    /**
     * 给碰撞体套上**方块材质密度**，再补**质量下限**。
     *
     * <p>密度是"太容易被推动"的正解：Rapier 默认密度 1.0 = 一格方块 1 kg（比玩家还轻）。
     * 按材质给密度后，一格石头 ≈2500 kg、一格钢 ≈7800 kg、一格羊毛 ≈200 kg ——
     * 材质差异直接变成"推不推得动"。</p>
     *
     * @return 碰撞体实际质量（kg）
     */
    private static double applyDensityAndFloor(long world, long body, long collider,
                                              List<PhysicsBodySyncPacket.BlockEntry> blocks) {
        List<BlockState> states = statesOf(blocks);
        double colliderMass = BlockDensity.apply(world, collider, states);
        if (colliderMass < 0.0) {
            // 老 dll（没有密度接口）：仍是 Rapier 默认 1.0 → 一格 1 kg
            colliderMass = states.size();
        }
        applyMassFloor(world, body, colliderMass, blocks);
        return colliderMass;
    }

    /** 用当前方块列表重建体素碰撞体（先清掉旧的，避免旧形状残留）。 */
    private static void rebuildCollider(long id, TrackedBody tracked) {
        long world = PhysicsWorldManager.world(tracked.level);
        if (world <= 0) {
            return;
        }
        long t0 = System.nanoTime();
        NativePhysics.bodyClearColliders(world, tracked.body);
        long[] cells = new long[tracked.blocks.size()];
        for (int i = 0; i < cells.length; i++) {
            PhysicsBodySyncPacket.BlockEntry e = tracked.blocks.get(i);
            cells[i] = NativePhysics.packCell(e.dx(), e.dy(), e.dz());
        }
        int[] groups = COLLISION_GROUPS.get(id);
        long collider;
        if (groups != null && PhysicsNatives.hasCollisionGroups()) {
            collider = NativePhysics.colliderAttachVoxelsGrouped(world, tracked.body, 1.0, 1.0, 1.0, cells,
                    PhysicsMaterials.BODY_FRICTION, 0.0, groups[0], groups[1]);
        } else {
            collider = NativePhysics.colliderAttachVoxels(world, tracked.body, 1.0, 1.0, 1.0, cells,
                    PhysicsMaterials.BODY_FRICTION, 0.0);
        }
        // 每次重建都要重套材质：colliderSetMaterial 是按碰撞体 id 生效的，旧碰撞体已经没了
        PhysicsMaterials.apply(world, collider, PhysicsMaterials.BODY_FRICTION, 0.0);
        // 方块数变了 → 碰撞体质量变了，密度与质量下限都要跟着重算（投影里机器改方块会走到这里）
        applyDensityAndFloor(world, tracked.body, collider, tracked.blocks);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms >= REBUILD_WARN_MS) {
            long now = System.nanoTime();
            if (now - lastRebuildWarnNanos > REBUILD_WARN_INTERVAL_NANOS) {
                lastRebuildWarnNanos = now;
                double[] m = MOTION.get(id);
                double speed = m == null ? 0.0 : Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
                LOGGER.warn("[PolyMech] 物理体 {} 重建碰撞体耗时 {} ms（{} 块，当时速度 {} m/s）"
                                + " —— 这期间它没有碰撞体，且会唤醒刚体；若周期性出现，就是「每隔几秒顿一下」的来源",
                        id, ms, tracked.blocks.size(), String.format("%.2f", speed));
            }
        }
    }

    private static void persist(TrackedBody tracked, long id) {
        PhysicsBodySavedData.get(tracked.level.getServer()).put(tracked.toSaved(id));
    }

    /** 把最新的方块快照整包重发给客户端（简化实现：不做增量）。 */
    private static void refresh(long id, TrackedBody tracked) {
        broadcast(PhysicsBodySyncPacket.create(id, tracked.x, tracked.y, tracked.z,
                tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.blocks), tracked.level);
        broadcastBlockEntities(id, tracked.level);
    }

    /**
     * 把该物理体的<b>方块实体</b> NBT 同步给客户端。
     *
     * <p>箱子/熔炉/告示牌这类方块的渲染形状是 {@code ENTITYBLOCK_ANIMATED} ——
     * 方块模型是空的，客户端只能靠 {@code BlockEntityRenderer} 画。缺了这个包它们就是透明的。
     * 对应 space/MPS 的 {@code SyncPhysicalBodyBlockEntity}。</p>
     *
     * <p>NBT 直接从投影地皮读（那才是权威副本），与 CREATE 快照配对发送；没有方块实体时不发包。</p>
     */
    public static void broadcastBlockEntities(long id, ServerLevel level) {
        int slot = ProjectionManager.slotOf(id);
        TrackedBody tracked = BODIES.get(id);
        if (slot < 0 || tracked == null) {
            return;
        }
        List<PhysicsBodyBlockEntityPacket.Entry> entries = collectBlockEntities(slot, tracked);
        if (!entries.isEmpty()) {
            broadcast(new PhysicsBodyBlockEntityPacket(id, entries), level);
        }
    }

    private static List<PhysicsBodyBlockEntityPacket.Entry> collectBlockEntities(int slot, TrackedBody tracked) {
        List<PhysicsBodyBlockEntityPacket.Entry> entries = new ArrayList<>();
        for (PhysicsBodySyncPacket.BlockEntry e : tracked.blocks) {
            CompoundTag tag = ProjectionManager.blockEntityTag(slot, e.dx(), e.dy(), e.dz());
            if (tag != null) {
                entries.add(new PhysicsBodyBlockEntityPacket.Entry(e.dx(), e.dy(), e.dz(), tag));
            }
        }
        return entries;
    }

    /** 直接销毁刚体与存档记录，不把方块放回世界（最后一块被破坏时用）。 */
    private static void dropWithoutRestoring(long id) {
        TrackedBody tracked = BODIES.remove(id);
        if (tracked == null) {
            return;
        }
        // id 会被回收（取最小空闲号），碰撞组必须跟着一起清 —— 否则会串到下一个复用该 id 的体上
        COLLISION_GROUPS.remove(id);
        MOTION.remove(id);
        RESTORED_MOTION.remove(id);
        long world = PhysicsWorldManager.world(tracked.level);
        if (world > 0 && tracked.body > 0) {
            NativePhysics.bodyDestroy(world, tracked.body);
        }
        releaseProjection(id, tracked);
        PhysicsBodySavedData.get(tracked.level.getServer()).remove(id);
        broadcast(PhysicsBodySyncPacket.remove(id), tracked.level);
    }

    /**
     * 清空该物理体的投影地皮并释放槽位。
     *
     * <p>必须成对：地皮的区块是<b>强制加载</b>的，不释放就会永久占着 8×8 区块 ——
     * 体数一多直接拖垮服务端。</p>
     */
    private static void releaseProjection(long id, TrackedBody tracked) {
        int slot = ProjectionManager.slotOf(id);
        if (slot < 0) {
            return;
        }
        ProjectionManager.clearBlocks(slot, tracked.blocks);
        ProjectionManager.release(id);
    }

    /** 按当前姿态把方块放回世界（吸附到方块网格），并销毁刚体。 */
    public static boolean drop(long id) {
        TrackedBody tracked = BODIES.remove(id);
        if (tracked == null) {
            return false;
        }
        // 同上：id 可回收，碰撞组不能留在表里
        COLLISION_GROUPS.remove(id);
        MOTION.remove(id);
        RESTORED_MOTION.remove(id);
        ServerLevel level = tracked.level;
        long world = PhysicsWorldManager.world(level);
        int slot = ProjectionManager.slotOf(id);

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
                BlockState state = PhysicsBodySyncPacket.stateFrom(entry.stateId());
                // 静默写入（不做方块更新），而不是 setBlockAndUpdate：
                // 逐格带邻居更新地放回一个多格结构时，门/床/双高的"另一半"还没写进去，
                // 原版的"孤儿半块自毁"逻辑会把先写的半边判成非法拆掉 —— 表现就是结构被截掉。
                // 整批写完后再由方块自己恢复（我们写的是抓取时的完整状态）。
                level.setBlock(pos, state, Block.UPDATE_CLIENTS);
                // 方块实体从投影带回来：机器是在投影维度里 tick 的，那边的 NBT 才是权威的
                // （熔炉烧到一半、箱子里的东西都在 NBT 里，只写回方块状态会丢）。
                CompoundTag tag = ProjectionManager.blockEntityTag(slot, entry.dx(), entry.dy(), entry.dz());
                if (tag != null) {
                    net.minecraft.world.level.block.entity.BlockEntity be =
                            net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                                    pos, state, tag, level.registryAccess());
                    if (be != null) {
                        level.setBlockEntity(be);
                    }
                }
            }
        }

        if (world > 0 && tracked.body > 0) {
            NativePhysics.bodyDestroy(world, tracked.body);
        }
        releaseProjection(id, tracked);
        PhysicsBodySavedData.get(level.getServer()).remove(id);
        broadcast(PhysicsBodySyncPacket.remove(id), level);
        return true;
    }

    /** 每 tick：同步所有物理体的变换；恢复出来的冻结体会在玩家靠近后解冻。 */
    public static void tick() {
        if (BODIES.isEmpty() || !PhysicsNatives.isAvailable()) {
            return;
        }
        // 没被客户端确认的快照定期重发（见 PENDING_ACKS）：这是"体凭空消失"的根治
        resendPendingAcks();
        double[] pos = new double[3];
        double[] rot = new double[4];
        double[] vel = new double[3];
        List<Long> stale = new ArrayList<>();
        /** 本 tick 要下发的变换：按维度攒批，最后每维度只发一个包。 */
        Map<ServerLevel, List<PhysicsBodyMoveBatchPacket.Entry>> batches = new HashMap<>();
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
                // 存档里带回来的速度必须**在切回动态之后**写：固定体的速度会被求解器清零，
                // 在 restore 里写等于没写（重启后巡航中的船会原地停住）。
                double[] restored = RESTORED_MOTION.remove(entry.getKey());
                if (restored != null) {
                    NativePhysics.bodySetMotion(world, tracked.body,
                            restored[0], restored[1], restored[2],
                            restored[3], restored[4], restored[5], true);
                }
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
            // 不再逐体发包：攒进该维度的批量列表，循环结束后一次发出去
            //（照 space 0.1.3 的 SyncPhysicalBodyMoveBatch —— 包数从 N/tick 降到 1/tick/维度）
            batches.computeIfAbsent(tracked.level, k -> new ArrayList<>())
                    .add(new PhysicsBodyMoveBatchPacket.Entry(entry.getKey(),
                            updated.x, updated.y, updated.z,
                            updated.qx, updated.qy, updated.qz, updated.qw,
                            vx, vy, vz, avx, avy, avz));
            // 运动状态原地记账（存档与诊断读它），用固定数组原地写、每体每 tick 零分配。
            //
            // 冻结体例外：它被建成固定体，读回来的速度恒为 0；若照写就会把 restore 从存档
            // 带回来的"解冻后应有的速度"抹掉 —— 玩家在远处存档退出，重启后船就永远不动了。
            // 注意下发（上面的 batch）仍然用物理真值 0：客户端那个刚体是 DYNAMIC 镜像，
            // 给它灌一个"意图速度"会让船在客户端自己飞走。
            double[] motion = MOTION.computeIfAbsent(entry.getKey(), k -> new double[6]);
            if (!tracked.frozen()) {
                motion[0] = vx;
                motion[1] = vy;
                motion[2] = vz;
                motion[3] = avx;
                motion[4] = avy;
                motion[5] = avz;
            }
            if (saved != null) {
                saved.updateTransform(entry.getKey(), updated.x, updated.y, updated.z,
                        updated.qx, updated.qy, updated.qz, updated.qw,
                        motion[0], motion[1], motion[2], motion[3], motion[4], motion[5]);
            }
        }
        for (Map.Entry<ServerLevel, List<PhysicsBodyMoveBatchPacket.Entry>> batch : batches.entrySet()) {
            if (!batch.getValue().isEmpty()) {
                broadcast(new PhysicsBodyMoveBatchPacket(batch.getValue()), batch.getKey());
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

            // 投影地皮：按存档记录的槽位挂回（槽位持久化过，绝不重新编号），
            // 再检查地皮内容是否还在 —— 旧存档没有 Slot 字段，或者投影维度被清过时会不在，
            // 那就用方块缓存补一份（方块实体 NBT 只存在于投影维度的 region 文件里，补不回来）。
            int slot = entry.slot();
            boolean newlyAssigned = slot < 0;
            if (newlyAssigned) {
                slot = ProjectionManager.allocate(entry.id());
            } else {
                ProjectionManager.restoreSlot(entry.id(), slot);
            }
            if (slot >= 0) {
                // 校验**整块**地皮，不能只看第一块。
                // 只看第一块的话，"地皮里缺了一部分方块"会被放过 —— 而那些方块在方块缓存
                // （以及客户端快照）里还在：玩家对着它们挖，服务端每次都在
                // "投影里是空气"处早退，客户端的射线永远停在那一格 →
                // 表现就是"第一格破了之后手一直在挥，后面的格子再也挖不动"。
                int missing = 0;
                for (PhysicsBodySyncPacket.BlockEntry e : blocks) {
                    if (!ProjectionManager.hasBlockAt(slot, e.dx(), e.dy(), e.dz())) {
                        missing++;
                    }
                }
                if (missing > 0) {
                    List<ProjectionManager.BlockToWrite> writes = new ArrayList<>(blocks.size());
                    for (PhysicsBodySyncPacket.BlockEntry e : blocks) {
                        writes.add(new ProjectionManager.BlockToWrite(e.dx(), e.dy(), e.dz(),
                                PhysicsBodySyncPacket.stateFrom(e.stateId()), null));
                    }
                    ProjectionManager.writeBlocks(slot, writes);
                    LOGGER.warn("[PolyMech] 物理体 {} 的地皮 {} 与存档不一致（{} / {} 块不在投影里），已按方块缓存整块重建（方块实体状态不可恢复）",
                            entry.id(), slot, missing, blocks.size());
                }
                if (newlyAssigned) {
                    saved.put(new PhysicsBodySavedData.Entry(entry.id(), entry.dimension(),
                            entry.x(), entry.y(), entry.z(),
                            entry.qx(), entry.qy(), entry.qz(), entry.qw(),
                            entry.vx(), entry.vy(), entry.vz(),
                            entry.avx(), entry.avy(), entry.avz(),
                            entry.membership(), entry.filter(), slot, entry.blocks()));
                }
            }

            // 冻结为固定体：无人时不会被重力带走
            long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                    entry.x(), entry.y(), entry.z(),
                    entry.qx(), entry.qy(), entry.qz(), entry.qw(), 0.0);
            if (body <= 0) {
                continue;
            }
            // 碰撞组一起恢复：此前不存也不还原，"重启后本该互相穿过/互不作用的两个体"会开始互撞。
            int membership = entry.membership();
            int filter = entry.filter();
            boolean hasGroups = membership != PhysicsBodySavedData.GROUP_UNSET
                    || filter != PhysicsBodySavedData.GROUP_UNSET;
            long collider;
            if (hasGroups && PhysicsNatives.hasCollisionGroups()) {
                collider = NativePhysics.colliderAttachVoxelsGrouped(world, body, 1.0, 1.0, 1.0, cellArray,
                        PhysicsMaterials.BODY_FRICTION, 0.0, membership, filter);
                if (collider > 0) {
                    COLLISION_GROUPS.put(entry.id(), new int[]{membership, filter});
                }
            } else {
                collider = NativePhysics.colliderAttachVoxels(world, body, 1.0, 1.0, 1.0, cellArray,
                        PhysicsMaterials.BODY_FRICTION, 0.0);
            }
            if (collider <= 0) {
                NativePhysics.bodyDestroy(world, body);
                COLLISION_GROUPS.remove(entry.id());
                continue;
            }
            PhysicsMaterials.apply(world, collider, PhysicsMaterials.BODY_FRICTION, 0.0);
            // 质量下限：RESTORED_MOTION 与它无关，但被撞飞的门槛与它有关
            applyDensityAndFloor(world, body, collider, blocks);
            // 速度先寄存：固定体的速度会被求解器清零，等解冻切回动态那一刻再写（见 tick）。
            RESTORED_MOTION.put(entry.id(), new double[]{
                    entry.vx(), entry.vy(), entry.vz(), entry.avx(), entry.avy(), entry.avz()});
            double[] motion = MOTION.computeIfAbsent(entry.id(), k -> new double[6]);
            motion[0] = entry.vx();
            motion[1] = entry.vy();
            motion[2] = entry.vz();
            motion[3] = entry.avx();
            motion[4] = entry.avy();
            motion[5] = entry.avz();
            TrackedBody tracked = new TrackedBody(level, body, BlockPos.containing(entry.x(), entry.y(), entry.z()),
                    blocks, entry.x(), entry.y(), entry.z(),
                    entry.qx(), entry.qy(), entry.qz(), entry.qw(), true);
            BODIES.put(entry.id(), tracked);
            restored++;
        }
        LOGGER.info("[PolyMech] 物理体恢复完成：{} / {} 个", restored, saved.all().size());
    }

    // ==================== 可靠创建握手（照 space 0.1.3 的 ReliableCreateSender） ====================

    /**
     * 等待客户端确认的快照。
     *
     * <p>为什么需要：CREATE 可能"发早了"—— 客户端还在建关卡，收到后刚存下就被
     * "卸载旧关卡"的清空流程冲掉，而服务端毫不知情 → 玩家看到"船没了"。
     * 此前靠 {@code PhysicsBodyEvents} 里"换维度后延迟 2 秒补发"兜着，那是权宜做法。</p>
     */
    private record PendingAck(long bodyId, int attempts, int lastSentTick) {
    }

    /** 玩家 → (bodyId → 待确认)。 */
    private static final Map<UUID, Map<Long, PendingAck>> PENDING_ACKS = new HashMap<>();
    /** 多久没确认就重发（tick）；与 space 0.1.3 一致。 */
    private static final int ACK_RESEND_INTERVAL = 40;
    /** 最多重发几次；与 space 0.1.3 的普通包一致（它另有关键包 10 次）。 */
    private static final int ACK_MAX_ATTEMPTS = 5;

    /** 新玩家进入 / 换维度：把该维度的全部物理体<b>可靠地</b>发给这个玩家。 */
    public static void sendAllTo(ServerPlayer player) {
        for (Map.Entry<Long, TrackedBody> entry : BODIES.entrySet()) {
            TrackedBody tracked = entry.getValue();
            if (tracked.level != player.serverLevel()) {
                continue;
            }
            sendBodyTo(player, entry.getKey(), tracked);
        }
    }

    /** 发一次快照并登记待确认（客户端 ACK 后才会撤销）。 */
    public static void sendBodyTo(ServerPlayer player, long id, TrackedBody tracked) {
        sendBodyNow(player, id, tracked);
        PENDING_ACKS.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .put(id, new PendingAck(id, 1, player.tickCount));
    }

    /** 真正把 CREATE + 方块实体快照推给该玩家。 */
    private static void sendBodyNow(ServerPlayer player, long id, TrackedBody tracked) {
        player.connection.send(PhysicsBodySyncPacket.create(id,
                tracked.x, tracked.y, tracked.z,
                tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.blocks));
        // 方块实体快照要跟着补发，否则换维度回来"箱子又透明了"
        int slot = ProjectionManager.slotOf(id);
        if (slot >= 0) {
            List<PhysicsBodyBlockEntityPacket.Entry> entries = collectBlockEntities(slot, tracked);
            if (!entries.isEmpty()) {
                player.connection.send(new PhysicsBodyBlockEntityPacket(id, entries));
            }
        }
    }

    /** 客户端确认已收到该物理体快照。 */
    public static void ackBody(ServerPlayer player, long bodyId) {
        Map<Long, PendingAck> map = PENDING_ACKS.get(player.getUUID());
        if (map != null) {
            map.remove(bodyId);
            if (map.isEmpty()) {
                PENDING_ACKS.remove(player.getUUID());
            }
        }
    }

    /** 玩家下线：清掉其待确认表，避免残留。 */
    public static void forgetAcks(ServerPlayer player) {
        PENDING_ACKS.remove(player.getUUID());
    }

    /** 每 tick：把超过重发间隔、还没被确认的快照重发；超过次数上限就放弃并记警告。 */
    private static void resendPendingAcks() {
        if (PENDING_ACKS.isEmpty()) {
            return;
        }
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        for (var playerEntry : new ArrayList<>(PENDING_ACKS.entrySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerEntry.getKey());
            if (player == null) {
                PENDING_ACKS.remove(playerEntry.getKey());
                continue;
            }
            for (PendingAck pending : new ArrayList<>(playerEntry.getValue().values())) {
                TrackedBody tracked = BODIES.get(pending.bodyId());
                if (tracked == null) {
                    ackBody(player, pending.bodyId()); // 体已经没了，不必再确认
                    continue;
                }
                if (player.tickCount - pending.lastSentTick() < ACK_RESEND_INTERVAL) {
                    continue;
                }
                if (pending.attempts() >= ACK_MAX_ATTEMPTS) {
                    LOGGER.warn("[PolyMech] 物理体 {} 的快照重发 {} 次仍未被 {} 确认，放弃",
                            pending.bodyId(), pending.attempts(), player.getName().getString());
                    ackBody(player, pending.bodyId());
                    continue;
                }
                sendBodyNow(player, pending.bodyId(), tracked);
                playerEntry.getValue().put(pending.bodyId(),
                        new PendingAck(pending.bodyId(), pending.attempts() + 1, player.tickCount));
            }
        }
    }

    /** 待确认数量（诊断用）。 */
    public static int pendingAckCount() {
        int n = 0;
        for (Map<Long, PendingAck> map : PENDING_ACKS.values()) {
            n += map.size();
        }
        return n;
    }

    // ==================== 投影 ↔ 刚体 的交互支撑 ====================

    /**
     * 地皮局部方块中心 → 该物理体所在世界的坐标。
     * <p>用途：投影维度里的掉落物要"搬"到这里 —— 玩家在另一个维度，
     * 原版把东西喷在投影维度那一格，玩家根本看不见也捡不到。</p>
     *
     * @return 世界坐标 {x,y,z}；刚体不存在返回 null
     */
    public static double[] localToWorld(long id, int dx, int dy, int dz) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null) {
            return null;
        }
        Quaterniond rotation = new Quaterniond(tracked.qx, tracked.qy, tracked.qz, tracked.qw).normalize();
        Vector3d v = new Vector3d(dx + 0.5, dy + 0.5, dz + 0.5);
        rotation.transform(v);
        return new double[]{tracked.x + v.x, tracked.y + v.y, tracked.z + v.z};
    }

    /**
     * 按<b>投影维度</b>的现状校正方块缓存里的一个位置 —— 交互（破坏/放置/使用）之后调用。
     *
     * <p>方向很重要：<b>投影是权威副本</b>（机器在那边 tick），缓存只是"给碰撞与渲染用的镜像"。
     * 交互改了投影之后，这里把那一格的现状读回来、更新缓存、重建体素碰撞体、写存档、
     * 并把新快照与方块实体发给客户端。</p>
     *
     * @return 该位置的状态是否与之前不同
     */
    public static boolean syncBlockFromProjection(long id, int dx, int dy, int dz) {
        return syncFromProjection(id, dx, dy, dz, 0);
    }

    /**
     * 交互之后按投影现状校正缓存。
     *
     * <p>方向很重要：<b>投影是权威副本</b>（机器在那边 tick），缓存只是"给碰撞与渲染用的镜像"。
     * 把命中点（以及半径内的邻格）读回来、更新缓存、重建体素碰撞体、写存档，
     * 最后<b>只广播一次</b>新快照与方块实体 —— 半径扫描不能每格都发一遍包。</p>
     */
    public static boolean syncFromProjection(long id, int dx, int dy, int dz, int radius) {
        TrackedBody tracked = BODIES.get(id);
        int slot = ProjectionManager.slotOf(id);
        if (tracked == null || slot < 0) {
            return false;
        }
        List<PhysicsBodySyncPacket.BlockEntry> blocks = new ArrayList<>(tracked.blocks);
        boolean anyChanged = false;
        for (int ox = -radius; ox <= radius; ox++) {
            for (int oy = -radius; oy <= radius; oy++) {
                for (int oz = -radius; oz <= radius; oz++) {
                    int lx = dx + ox;
                    int ly = dy + oy;
                    int lz = dz + oz;
                    BlockState now = ProjectionManager.blockStateAt(slot, lx, ly, lz);
                    if (now == null) {
                        continue; // 投影维度不可用时不动缓存
                    }
                    boolean had = blocks.removeIf(e -> e.dx() == lx && e.dy() == ly && e.dz() == lz);
                    if (!now.isAir()) {
                        blocks.add(new PhysicsBodySyncPacket.BlockEntry((short) lx, (short) ly, (short) lz,
                                PhysicsBodySyncPacket.stateId(now)));
                        if (!had) {
                            anyChanged = true;
                        }
                    } else if (had) {
                        anyChanged = true;
                    }
                }
            }
        }
        if (blocks.isEmpty()) {
            // 最后一块没了 → 体没有意义，直接销毁（内部会释放地皮与槽位）
            dropWithoutRestoring(id);
            return true;
        }
        if (!anyChanged && blocks.size() == tracked.blocks.size()) {
            return false; // 什么都没变：不重建、不发包
        }
        TrackedBody updated = new TrackedBody(tracked.level, tracked.body, tracked.origin, blocks,
                tracked.x, tracked.y, tracked.z, tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.frozen);
        BODIES.put(id, updated);
        rebuildCollider(id, updated);
        persist(updated, id);
        refresh(id, updated);
        return true;
    }

    /**
     * 交互之后，按投影现状校正"命中点周围一圈"的方块缓存。
     *
     * <p>为什么不止同步命中那一格：门/床/双高方块被交互时会<b>连带改相邻格的状态</b>
     * （下半扇门一开，上半扇的 {@code open} 也变），只同步命中格就会留下一半是旧状态 ——
     * 表现是"门只开了一半"。半径 1 的 3³ 扫描只有 27 次读，代价可忽略。</p>
     */
    public static void syncAroundFromProjection(long id, int dx, int dy, int dz, int radius) {
        syncFromProjection(id, dx, dy, dz, Math.max(0, radius));
    }

    /**
     * 把一个"投影里被改动过的区块"回写到刚体的方块缓存（阶段 2b 的核心）。
     *
     * <p>这是"投影里发生的事能传到玩家眼前"的唯一通道：红石灯亮灭、活塞推块、
     * 机器自改结构，全都要经过这里。</p>
     *
     * <p>为什么是<b>扫整个区块</b>而不是"按缓存里的条目反查投影"：改动有三种 ——
     * 新增（活塞推出头、机器放块）、删除（导线脱落、方块被推走）、状态变化（灯亮灭）。
     * 只反查缓存看不到"新增"，所以必须逐格比对所在区块（用哈希表做 O(1) 查找，
     * 按区块 section 跳过空段）。</p>
     *
     * <p>有变化才重建体素碰撞体、写存档、把新快照与方块实体发给客户端。</p>
     */
    public static void reconcileChunkFromProjection(long bodyId, int slot, int cx, int cz) {
        TrackedBody tracked = BODIES.get(bodyId);
        ServerLevel proj = ProjectionManager.level();
        if (tracked == null || proj == null) {
            return;
        }
        BlockPos start = ProjectionManager.startOf(slot);
        net.minecraft.world.level.chunk.LevelChunk chunk = proj.getChunk(cx, cz);
        Map<Long, PhysicsBodySyncPacket.BlockEntry> index = new HashMap<>(tracked.blocks.size() * 2);
        for (PhysicsBodySyncPacket.BlockEntry e : tracked.blocks) {
            index.put(NativePhysics.packCell(e.dx(), e.dy(), e.dz()), e);
        }

        boolean changed = false;
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            if (baseY + 15 < start.getY() || baseY >= start.getY() + ProjectionManager.SLOT_SIZE) {
                continue;
            }
            net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
            boolean empty = section == null || section.hasOnlyAir();
            for (int ly = 0; ly < 16; ly++) {
                int dy = baseY + ly - start.getY();
                if (dy < 0 || dy >= ProjectionManager.SLOT_SIZE) {
                    continue;
                }
                for (int lx = 0; lx < 16; lx++) {
                    int dx = (cx << 4) + lx - start.getX();
                    if (dx < 0 || dx >= ProjectionManager.SLOT_SIZE) {
                        continue;
                    }
                    for (int lz = 0; lz < 16; lz++) {
                        int dz = (cz << 4) + lz - start.getZ();
                        if (dz < 0 || dz >= ProjectionManager.SLOT_SIZE) {
                            continue;
                        }
                        long key = NativePhysics.packCell(dx, dy, dz);
                        PhysicsBodySyncPacket.BlockEntry existing = index.get(key);
                        if (empty) {
                            if (existing != null) {
                                index.remove(key);
                                changed = true;
                            }
                            continue;
                        }
                        BlockState now = section.getBlockState(lx, ly, lz);
                        int nowId = now.isAir() ? -1 : PhysicsBodySyncPacket.stateId(now);
                        if (existing == null) {
                            if (nowId >= 0) {
                                index.put(key, new PhysicsBodySyncPacket.BlockEntry(
                                        (short) dx, (short) dy, (short) dz, nowId));
                                changed = true;
                            }
                        } else if (existing.stateId() != nowId) {
                            if (nowId < 0) {
                                index.remove(key);
                            } else {
                                index.put(key, new PhysicsBodySyncPacket.BlockEntry(
                                        (short) dx, (short) dy, (short) dz, nowId));
                            }
                            changed = true;
                        }
                    }
                }
            }
        }
        if (!changed) {
            return;
        }

        List<PhysicsBodySyncPacket.BlockEntry> blocks = new ArrayList<>(index.values());
        if (blocks.isEmpty()) {
            dropWithoutRestoring(bodyId);
            return;
        }
        TrackedBody updated = new TrackedBody(tracked.level, tracked.body, tracked.origin, blocks,
                tracked.x, tracked.y, tracked.z, tracked.qx, tracked.qy, tracked.qz, tracked.qw, tracked.frozen);
        BODIES.put(bodyId, updated);
        rebuildCollider(bodyId, updated);
        persist(updated, bodyId);
        refresh(bodyId, updated);
    }

    /** 该物理体所在的维度；不存在返回 null。 */
    public static ServerLevel levelOf(long id) {
        TrackedBody tracked = BODIES.get(id);
        return tracked == null ? null : tracked.level;
    }

    /** 对刚体施加冲量（质量感知：同样冲量下轻物飞得远、重物几乎不动）。 */
    public static boolean applyImpulse(long id, double ix, double iy, double iz) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null) {
            return false;
        }
        long world = PhysicsWorldManager.world(tracked.level);
        boolean ok = world > 0 && NativePhysics.bodyApplyImpulse(world, tracked.body, ix, iy, iz);
        // DEBUG：推动是持续行为（客户端每 N tick 一个包），INFO 会把日志刷爆
        LOGGER.debug("[PolyMech] 推动物理体 {}: 冲量=({}, {}, {}) 冻结={} 结果={}",
                id, String.format("%.2f", ix), String.format("%.2f", iy), String.format("%.2f", iz),
                tracked.frozen, ok);
        return ok;
    }

    /**
     * 取刚体最近一次同步到的运动状态 {@code [vx,vy,vz, avx,avy,avz]}；不存在返回 null。
     *
     * <p>调用方<b>不要改这个数组</b>（它是内部记账用的，改了下一次存档就写错了）。</p>
     */
    public static double[] motionOf(long id) {
        return MOTION.get(id);
    }

    /**
     * 取刚体当前质量（kg）；刚体不存在或原生层过旧返回 -1。
     *
     * <p>推动冲量必须按它缩放 —— 固定冲量打在轻体上就是"打飞"（实测 1 格方块 = 1 kg，
     * 原本的 24 N·s 固定冲量等于瞬加 24 m/s）。</p>
     */
    public static double massOf(long id) {
        TrackedBody tracked = BODIES.get(id);
        if (tracked == null || !PhysicsNatives.hasTier1()) {
            return -1.0;
        }
        long world = PhysicsWorldManager.world(tracked.level);
        if (world <= 0) {
            return -1.0;
        }
        return NativePhysics.bodyGetMass(world, tracked.body);
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
        COLLISION_GROUPS.clear();
        MOTION.clear();
        RESTORED_MOTION.clear();
    }

    /** 把任意物理体相关包发给该维度全部玩家（方块快照、方块实体快照、挖掘进度共用）。 */
    public static void broadcast(net.minecraft.network.protocol.common.custom.CustomPacketPayload packet,
                                 ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, packet);
    }
}
