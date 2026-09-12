package com.mss.polymech.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 地形体素快照管线：把真实 Minecraft 区块的方块转成 Rapier 体素碰撞体，
 * 让物理世界里的刚体（飞船/货物/被接管的实体）能站在、撞在真实地形上。
 *
 * <p>做法与 space 模组的 PhysicalChunkManager 同构：</p>
 * <ul>
 *   <li>以"关注点"为中心、若干区块为半径，逐区块建立<b>固定刚体 + 体素碰撞体</b>；</li>
 *   <li>刚体原点取区块最小角（{@code cx*16, minY, cz*16}），体素坐标即方块在该区块内的局部坐标；</li>
 *   <li>只遍历非空 section，跳过空气与流体，控制体素数量；</li>
 *   <li>方块变更由 {@code LevelChunk.setBlockState} mixin 标记为脏，下一 tick 重建该区块；</li>
 *   <li>移出半径的区块立即销毁碰撞体，避免物理世界里残留幽灵地面。</li>
 * </ul>
 */
public final class PhysicsTerrain {

    private static final Logger LOGGER = LoggerFactory.getLogger("PolyMech/Physics/Terrain");

    /** 单区块体素上限（防御性：整块 16x384x16 = 98304，正常远低于此）。 */
    private static final int MAX_CELLS_PER_CHUNK = 200_000;

    /**
     * 垂直带宽：只把关注点上下这么多格计入碰撞体。
     * <p>物理交互只发生在关注点附近；把深层石头也塞进去会让体素数暴涨
     * （实测整块约 3 万体素/区块，9 区块 26 万体素、建表 255ms）。
     * 带宽 ±64 通常能覆盖地面到飞船的高度范围，且体素数下降一个数量级。</p>
     */
    private static final int VERTICAL_BAND = 64;

    private final ServerLevel level;
    private final Map<Long, ChunkBody> chunks = new HashMap<>();
    private final Set<Long> dirty = new HashSet<>();

    private BlockPos center = BlockPos.ZERO;
    private int radius = 0;

    private int lastSkippedUnloaded;

    private long totalCells;
    private long totalBuildNanos;
    private int buildCount;

    private record ChunkBody(long body, int cells) {
    }

    public PhysicsTerrain(ServerLevel level) {
        this.level = level;
    }

    /** 是否已启用（半径 &gt; 0）。 */
    public boolean isActive() {
        return radius > 0;
    }

    public int radius() {
        return radius;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public long totalCells() {
        return totalCells;
    }

    public int buildCount() {
        return buildCount;
    }

    /** 上一次 update 中因区块未加载而跳过的数量。 */
    public int lastSkippedUnloaded() {
        return lastSkippedUnloaded;
    }

    public double averageBuildMs() {
        return buildCount == 0 ? 0.0 : totalBuildNanos / 1_000_000.0 / buildCount;
    }

    /** 设定关注点与半径（单位：区块）；半径 0 表示停止。 */
    public void update(BlockPos center, int radius) {
        this.center = center;
        this.radius = Math.max(0, Math.min(radius, 8));
        if (this.radius == 0) {
            clear();
            return;
        }
        long world = PhysicsWorldManager.world(level);
        if (world <= 0) {
            return;
        }

        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        Set<Long> desired = new HashSet<>();
        lastSkippedUnloaded = 0;

        for (int dx = -this.radius; dx <= this.radius; dx++) {
            for (int dz = -this.radius; dz <= this.radius; dz++) {
                int cx = centerChunkX + dx;
                int cz = centerChunkZ + dz;
                if (!level.hasChunk(cx, cz)) {
                    // 不为物理快照强行加载区块：未加载的区块没有实体在里面活动
                    lastSkippedUnloaded++;
                    continue;
                }
                long key = ChunkPos.asLong(cx, cz);
                desired.add(key);
                if (!chunks.containsKey(key) || dirty.contains(key)) {
                    buildChunk(new ChunkPos(cx, cz));
                }
            }
        }

        // 卸载移出范围的区块
        List<Long> toRemove = new ArrayList<>();
        for (Long key : chunks.keySet()) {
            if (!desired.contains(key)) {
                toRemove.add(key);
            }
        }
        for (Long key : toRemove) {
            removeChunk(key);
        }
        dirty.clear();
    }

    /** 标记区块为脏（下一 tick 重建）。 */
    public void markDirty(ChunkPos pos) {
        long key = pos.toLong();
        if (chunks.containsKey(key)) {
            dirty.add(key);
        }
    }

    /** 每 tick：重建脏区块。 */
    public void tick() {
        if (dirty.isEmpty() || radius == 0) {
            return;
        }
        List<Long> rebuild = new ArrayList<>(dirty);
        dirty.clear();
        for (Long key : rebuild) {
            if (chunks.containsKey(key)) {
                buildChunk(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key)));
            }
        }
    }

    /** 销毁全部碰撞体。 */
    public void clear() {
        for (Long key : new ArrayList<>(chunks.keySet())) {
            removeChunk(key);
        }
        dirty.clear();
        radius = 0;
    }

    // ==================== 内部 ====================

    private void buildChunk(ChunkPos pos) {
        long world = PhysicsWorldManager.world(level);
        if (world <= 0) {
            return;
        }
        removeChunk(pos.toLong());
        if (!level.hasChunk(pos.x, pos.z)) {
            return;
        }

        long start = System.nanoTime();
        LevelChunk chunk = level.getChunk(pos.x, pos.z);
        int minY = level.getMinBuildHeight();
        LevelChunkSection[] sections = chunk.getSections();

        List<Long> cells = new ArrayList<>();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section == null || section.hasOnlyAir()) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(index) << 4;
            if (baseY + 15 < center.getY() - VERTICAL_BAND || baseY > center.getY() + VERTICAL_BAND) {
                continue;
            }
            int yStart = Math.max(0, center.getY() - VERTICAL_BAND - baseY);
            int yEnd = Math.min(15, center.getY() + VERTICAL_BAND - baseY);
            for (int y = yStart; y <= yEnd; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.isAir() || !state.getFluidState().isEmpty()) {
                            continue;
                        }
                        cells.add(NativePhysics.packCell(x, baseY - minY + y, z));
                        if (cells.size() >= MAX_CELLS_PER_CHUNK) {
                            break;
                        }
                    }
                }
            }
        }

        if (cells.isEmpty()) {
            chunks.put(pos.toLong(), new ChunkBody(0, 0));
            return;
        }

        long[] packed = new long[cells.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = cells.get(i);
        }

        long body = NativePhysics.bodyCreate(world, NativePhysics.BODY_FIXED,
                chunk.getPos().getMinBlockX(), minY, chunk.getPos().getMinBlockZ(),
                0.0, 0.0, 0.0, 1.0, 0.0);
        long collider = NativePhysics.colliderAttachVoxels(world, body,
                1.0, 1.0, 1.0, packed, 0.7, 0.0);
        if (body <= 0 || collider <= 0) {
            if (body > 0) {
                NativePhysics.bodyDestroy(world, body);
            }
            LOGGER.warn("[PolyMech] 区块 {} 体素碰撞体创建失败", pos);
            return;
        }

        long elapsed = System.nanoTime() - start;
        chunks.put(pos.toLong(), new ChunkBody(body, packed.length));
        totalCells += packed.length;
        totalBuildNanos += elapsed;
        buildCount++;
    }

    private void removeChunk(long key) {
        ChunkBody body = chunks.remove(key);
        if (body == null || body.body() <= 0) {
            return;
        }
        long world = PhysicsWorldManager.world(level);
        if (world > 0) {
            NativePhysics.bodyDestroy(world, body.body());
        }
        totalCells -= body.cells();
    }
}
