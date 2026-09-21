package com.mss.polymech.mps.physical.manger;

import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.mps.physical.helper.DoubleAABB;
import com.mss.polymech.mps.physical.helper.PhysicalChunk;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.joml.Vector2i;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端地形区块管理 —— <b>与
 * {@code org.polaris2023.mps.physical.manger.PhysicalChunkManager} 同形</b>的自有实现
 * （clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 决定"服务端在哪些区块建地形碰撞体"。物理世界不能全图建碰撞体（内存/耗时都不可接受），
 * 所以只围绕**关注点**建：每个物理体 + 每个玩家各算一个关注点，取其周围 16×16 区块。
 *
 * <h2>为什么是这个形态（照 space 0.1.3，逐条都有理由）</h2>
 * <ol>
 *   <li><b>两级区块表（up / old）</b>：{@code up_physicalChunks} 是"现在挂着碰撞体"的，
 *       {@code old_physicalChunks} 是"刚被移出、缓存留着"的。移出时不销毁 {@link PhysicalChunk}
 *       而是搬到 old —— 关注点来回移动时（玩家反复跨越边界）能立刻复用，不必重新扫 16³×N 个方块。
 *       <b>只有 old 里也没人再用时才是真的白留内存</b>，这是速度换内存的正常取舍。</li>
 *   <li><b>增删都按 CHUNK_ORDER（x 再 z）排序</b>：一批新增/移除的顺序稳定，帧与帧之间不会
 *       因为 {@code HashSet} 的迭代顺序不同而忽快忽慢（那种抖动很难查）。</li>
 *   <li><b>移除顺序按"离新增区块的最近距离"排</b>（{@link #removalDistance}）：正在被关注点接近的
 *       方向最后移除，避免"刚移掉又要建回来"的抖动（迟滞）。</li>
 *   <li><b>{@link #markDirty} 只打标记，重建发生在 {@link #tick} 里</b>：方块变更（红石/活塞/机器）可能
 *       一 tick 内成百上千次，逐次重建会把主线程钉死。</li>
 *   <li><b>只有区块已加载才建碰撞体</b>（{@code level.isLoaded}）：未加载区块读方块会强制加载，
 *       等于用物理系统把整个世界的区块拖起来。</li>
 *   <li><b>区块就绪后解锁刚体</b>（{@link #unlockBodiesWhenChunksReady}）：存档恢复时物理体先以固定体
 *       落地（免得一加载就掉出未建好的地面），等它身下的区块碰撞体齐了再切回动态。</li>
 * </ol>
 *
 * <h2>与 MPS 的差异（都是"kelvin 未移植"造成的，语义相同）</h2>
 * <ul>
 *   <li>{@code ServerSpaceWorld.isSpaceWorld(Rlevel)} → 本项目等价的
 *       {@link PlanetDimensions#SPACE}（太空维度不建地形碰撞体：那里本就是虚空）。</li>
 *   <li>{@code org.joml.Vector2i} → {@link Vector2i}；{@code org.joml.primitives.AABBd} →
 *       {@link DoubleAABB}（MC 只带 joml 核心）。</li>
 *   <li>HEIGHTMAP 分支保留（与 MPS 同构），但我们的 {@code PhysicalChunk} 侧尚未实现该形状 ——
 *       而 space 自己也没启用它（见文档第九节），故此分支恒不触发。</li>
 * </ul>
 */
public class PhysicalChunkManager {

    private final ResourceLocation Rlevel;
    private ServerLevel level;
    private final PhysicalWorld physicalWorld;

    /** 当前挂着碰撞体的区块。 */
    protected final Map<ChunkPos, PhysicalChunk> up_physicalChunks = new ConcurrentHashMap<>();
    /** 刚被移出、缓存留着的区块（复用用）。 */
    protected final Map<ChunkPos, PhysicalChunk> old_physicalChunks = new ConcurrentHashMap<>();

    private static final Comparator<ChunkPos> CHUNK_ORDER = (a, b) -> {
        int c = Integer.compare(a.x, b.x);
        return c != 0 ? c : Integer.compare(a.z, b.z);
    };

    private final TreeMap<ChunkPos, PhysicalChunk> orderedChunks = new TreeMap<>(CHUNK_ORDER);
    private final Set<ChunkPos> dirtyChunks = ConcurrentHashMap.newKeySet();
    /** 关注点：物理体（按 UUID）与玩家各一个。 */
    protected final Map<Object, InterestPoint> interestPoints = new ConcurrentHashMap<>();

    public PhysicalChunkManager(ResourceLocation Rlevel, PhysicalWorld physicalWorld) {
        this.Rlevel = Rlevel;
        this.physicalWorld = physicalWorld;
    }

    /** 每 tick（服务端地形线程 50ms 调一次）：更新关注点 → 增删区块 → 该解锁的解锁。 */
    public void tick() {
        if (this.level == null) {
            this.init();
        } else if (!isSpaceDimension(this.Rlevel)) {
            this.updateInterestPoints();
            this.updatePhysicalChunks();
            this.unlockBodiesWhenChunksReady();
        }
    }

    /** 太空维度不建地形碰撞体（那里是虚空）。MPS 用 kelvin 的 {@code ServerSpaceWorld}。 */
    private static boolean isSpaceDimension(ResourceLocation level) {
        return PlanetDimensions.SPACE.location().equals(level);
    }

    /** 刚体身下的区块碰撞体齐了就解锁（切回动态）。 */
    private void unlockBodiesWhenChunksReady() {
        for (PhysicalBody physicalBody : this.physicalWorld.getAllPhysicalBody()) {
            if (physicalBody.isLocked() && this.isBodyAABBCovered(physicalBody)) {
                this.physicalWorld.setPhysicalBodyLocked(physicalBody, false);
            }
        }
    }

    /** 该体的世界包围盒覆盖到的区块是否都已在 up 表里。 */
    private boolean isBodyAABBCovered(PhysicalBody physicalBody) {
        DoubleAABB aabb = physicalBody.worldAABB;
        if (!PhysicalBody.isValidAABB(aabb)) {
            return true; // 没有有效包围盒（空体）→ 视为已覆盖，避免永远锁着
        }
        int minX = (int) Math.floor(aabb.minX / 16.0);
        int maxX = (int) Math.floor(aabb.maxX / 16.0);
        int minZ = (int) Math.floor(aabb.minZ / 16.0);
        int maxZ = (int) Math.floor(aabb.maxZ / 16.0);
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!this.up_physicalChunks.containsKey(new ChunkPos(cx, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 方块变更：只打脏标记（重建在 {@link #tick} 里批量做）。 */
    public void markDirty(BlockPos pos) {
        this.dirtyChunks.add(new ChunkPos(pos));
    }

    private void init() {
        MinecraftServer minecraftServer = ServerLifecycleHooks.getCurrentServer();
        if (minecraftServer != null) {
            this.level = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, this.Rlevel));
        }
    }

    /** 收集关注点：每个物理体一个，每个玩家一个；消失的移除。 */
    private void updateInterestPoints() {
        for (PhysicalBody physicalBody : this.physicalWorld.getAllPhysicalBody()) {
            Object partner = physicalBody.getUuid();
            Vector3d pos = physicalBody.getPos();
            if (pos == null) {
                continue;
            }
            if (!this.interestPoints.containsKey(partner)) {
                this.interestPoints.put(partner, new InterestPoint(partner,
                        new Vector2i((int) Math.round(pos.x), (int) Math.round(pos.z))));
            } else {
                this.interestPoints.get(partner).setPos(new Vector2i((int) Math.round(pos.x), (int) Math.round(pos.z)));
            }
        }

        for (ServerPlayer player : this.level.players()) {
            BlockPos blockPos = player.getOnPos();
            if (!this.interestPoints.containsKey(player)) {
                this.interestPoints.put(player, new InterestPoint(player,
                        new Vector2i(blockPos.getX(), blockPos.getZ())));
            } else {
                this.interestPoints.get(player).setPos(new Vector2i(blockPos.getX(), blockPos.getZ()));
            }
        }

        this.interestPoints.entrySet().removeIf(entry -> {
            Object partner = entry.getKey();
            if (partner instanceof UUID uuid) {
                return this.physicalWorld.getPhysicalBody(uuid) == null;
            }
            return partner instanceof ServerPlayer serverPlayer && !this.level.players().contains(serverPlayer);
        });
    }

    /**
     * 按关注点增删区块（每个关注点取 16×16 区块的方阵，即 ±8）。
     */
    private void updatePhysicalChunks() {
        Set<ChunkPos> existenceChunks = new HashSet<>();
        Set<ChunkPos> addChunks = new HashSet<>();

        for (InterestPoint interestPoint : this.interestPoints.values()) {
            int startX = interestPoint.pos.x / 16 - 8;
            // 注意：Vector2i 的分量是 x/y，这里的 y 存的是**世界的 z**（与 MPS 原样一致）——
            // 别"顺手"改成 .z，那是我们自写替代类时的走样。
            int startZ = interestPoint.pos.y / 16 - 8;
            int endX = interestPoint.pos.x / 16 + 8;
            int endZ = interestPoint.pos.y / 16 + 8;
            for (int cx = startX; cx < endX; cx++) {
                for (int cz = startZ; cz < endZ; cz++) {
                    ChunkPos chunkPos = new ChunkPos(cx, cz);
                    existenceChunks.add(chunkPos);
                    PhysicalChunk chunk = this.up_physicalChunks.get(chunkPos);
                    if (chunk != null) {
                        this.rebuildColliderIfNeeded(chunk, chunkPos);
                    } else {
                        addChunks.add(chunkPos);
                    }
                }
            }
        }

        Set<ChunkPos> removeChunks = new HashSet<>(this.up_physicalChunks.keySet());
        removeChunks.removeAll(existenceChunks);

        // 新增按固定顺序（x→z），顺序稳定
        List<ChunkPos> adds = new ArrayList<>(addChunks);
        adds.sort(CHUNK_ORDER);
        for (ChunkPos chunkPos : adds) {
            PhysicalChunk chunk = this.old_physicalChunks.remove(chunkPos); // 缓存复用
            if (chunk == null) {
                chunk = new PhysicalChunk(chunkPos);
            }
            this.rebuildColliderIfNeeded(chunk, chunkPos);
            this.up_physicalChunks.put(chunkPos, chunk);
            this.orderedChunks.put(chunkPos, chunk);
        }

        // 移除按"离新增区块最近距离"排：正在被接近的最后移除（迟滞，避免刚删又建）
        List<ChunkPos> removes = new ArrayList<>(removeChunks);
        removes.sort(Comparator.comparingDouble(pos -> this.removalDistance(pos, adds)));
        for (ChunkPos chunkPos : removes) {
            PhysicalChunk chunk = this.up_physicalChunks.remove(chunkPos);
            if (chunk != null) {
                this.deactivateChunk(chunk);
                this.old_physicalChunks.put(chunkPos, chunk);
                this.orderedChunks.remove(chunkPos);
            }
        }
    }

    private double removalDistance(ChunkPos pos, List<ChunkPos> adds) {
        ChunkPos prev = this.orderedChunks.lowerKey(pos);
        ChunkPos next = this.orderedChunks.higherKey(pos);
        double best = Double.MAX_VALUE;
        for (ChunkPos add : adds) {
            best = Math.min(best, dist(pos, add));
            if (prev != null) {
                best = Math.min(best, dist(prev, add));
            }
            if (next != null) {
                best = Math.min(best, dist(next, add));
            }
        }
        return best;
    }

    private static double dist(ChunkPos a, ChunkPos b) {
        long dx = (long) a.x - (long) b.x;
        long dz = (long) a.z - (long) b.z;
        return Math.sqrt((double) (dx * dx + dz * dz));
    }

    /**
     * 该区块的碰撞体是否需要重建/移除。
     *
     * <p>已加载 → 脏则重建（体素与复合盒分别处理）；未加载 → 移除碰撞体
     *（未加载区块读方块会强制加载，等于用物理系统把全图区块拖起来）。</p>
     */
    private void rebuildColliderIfNeeded(PhysicalChunk chunk, ChunkPos pos) {
        boolean dirty = this.dirtyChunks.remove(pos);
        if (this.level.isLoaded(pos.getWorldPosition())) {
            if (chunk.getHeightmapColliderBody() != null) {
                this.physicalWorld.removeColliderBody(chunk.getHeightmapColliderBody());
                chunk.destroyHeightmapColliderBody();
            }
            if (dirty && chunk.getVoxelColliderBody() != null) {
                this.physicalWorld.removeColliderBody(chunk.getVoxelColliderBody());
                chunk.destroyVoxelCollider();
            }
            if (dirty && chunk.getCuboidColliderBody() != null) {
                this.physicalWorld.removeColliderBody(chunk.getCuboidColliderBody());
                chunk.destroyCuboidColliders();
            }

            if (chunk.getVoxelColliderBody() == null) {
                List<Object> data = chunk.getBlockArray(this.level);
                boolean[] fullBlocks = (boolean[]) data.get(0);
                double[] boxData = (double[]) data.get(1);
                // y 方向格数 = 段数 × 16（不是段数）
                int h = this.level.getChunk(pos.x, pos.z).getSections().length * 16;
                chunk.buildVoxelCollider(fullBlocks, this.level.getMinBuildHeight(), h);
                if (chunk.getVoxelColliderBody() != null) {
                    this.physicalWorld.addColliderBody(chunk.getVoxelColliderBody());
                }
                chunk.buildCuboidCollider(boxData);
                if (chunk.getCuboidColliderBody() != null) {
                    this.physicalWorld.addColliderBody(chunk.getCuboidColliderBody());
                }
            }
        } else {
            if (chunk.getVoxelColliderBody() != null) {
                this.physicalWorld.removeColliderBody(chunk.getVoxelColliderBody());
                chunk.destroyVoxelCollider();
            }
            if (chunk.getCuboidColliderBody() != null) {
                this.physicalWorld.removeColliderBody(chunk.getCuboidColliderBody());
                chunk.destroyCuboidColliders();
            }
        }
    }

    /** 区块移出关注范围：摘掉它挂在世界上的所有碰撞体（缓存本体留着）。 */
    private void deactivateChunk(PhysicalChunk chunk) {
        if (chunk.getVoxelColliderBody() != null) {
            this.physicalWorld.removeColliderBody(chunk.getVoxelColliderBody());
            chunk.destroyVoxelCollider();
        }
        if (chunk.getCuboidColliderBody() != null) {
            this.physicalWorld.removeColliderBody(chunk.getCuboidColliderBody());
            chunk.destroyCuboidColliders();
        }
        if (chunk.getHeightmapColliderBody() != null) {
            this.physicalWorld.removeColliderBody(chunk.getHeightmapColliderBody());
            chunk.destroyHeightmapColliderBody();
        }
    }

    /** 关注点：一个物理体或一个玩家，以及它当前所在的方块坐标（x,z）。 */
    public static class InterestPoint {
        private final Object partner;
        private final Vector2i pos = new Vector2i();

        public InterestPoint(Object partner) {
            this.partner = partner;
        }

        public InterestPoint(Object partner, Vector2i pos) {
            this.partner = partner;
            this.pos.set(pos);
        }

        public boolean is(Object o) {
            return Objects.equals(this.partner, o);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof InterestPoint that && Objects.equals(this.partner, that.partner);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(this.partner);
        }

        public void setPos(int x, int z) {
            this.pos.set(x, z);
        }

        public void setPos(Vector2i vector2i) {
            this.pos.set(vector2i);
        }

        public Object getPartner() {
            return this.partner;
        }

        public Vector2i getPos() {
            return this.pos;
        }
    }
}
