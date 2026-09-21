package com.mss.polymech.mps.physical.physical_body;

import com.mss.polymech.mps.physical.helper.DoubleAABB;
import com.mss.polymech.mps.physical.helper.IntAABB;
import com.mss.polymech.mps.physical.helper.PalettedChunk;
import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import com.mss.polymech.mps.rapier.helper.ColliderBody;
import com.mss.polymech.mps.rapier.helper.RigidBody;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 物理体 —— <b>与 {@code org.polaris2023.mps.physical.physical_body.PhysicalBody} 同形</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 一个"会动的结构"的方块快照 + 它的刚体。方块不存在于世界里，只存在这里（128³ = 2×2×2 个
 * {@link PalettedChunk}），碰撞由每个 64³ 子块一份体素碰撞体提供。
 *
 * <h2>为什么是这个形态（照 space 0.1.3，别改）</h2>
 * <ul>
 *   <li><b>坐标是"中心在原点"的 -64..63</b>：{@code setBlockState} 一律 {@code +64}，
 *       子块索引 {@code 0/1}；碰撞体平移量因此是 {@code chunk*64 - 64}。
 *       这样可以只靠"刚体原点 + 旋转"把任意方块映射到世界，不需要额外基准点。</li>
 *   <li><b>方块改动分两条路</b>：{@link #setBlockState} 立刻重建该子块碰撞体
 *       （单格放置要马上有碰撞）；{@link #setBlockStateDeferred} 只打脏标记，
 *       由 {@link #flushBlockStateUpdates} 批量重建 —— 批量导入/同步时避免每格一次重建。</li>
 *   <li><b>每个子块一份碰撞体</b>（不是整体一份）：单格改动只需重建那 64³；
 *       拆成 8 份也让 Rapier 的宽相位更省。</li>
 *   <li>{@link #upChunk} 在<b>换碰撞体之前</b>先从世界摘掉旧的、换好再加新的 ——
 *       顺序颠倒会让新旧两份碰撞体短暂共存（重叠区的解算冲量会炸）。</li>
 * </ul>
 *
 * <h2>尚未移植（不猜、不造假）</h2>
 * <ul>
 *   <li>{@code getStatus()}：MPS 委派给 {@code RigidBody.getStatus()}，其语义需再对照后补；</li>
 *   <li>存档（{@code saveToTag}/{@code fromTag}）：由 S6 与 {@code PhysicsBodySavedData} 一起接。</li>
 * </ul>
 */
public class PhysicalBody {

    /** 物理体最大边长（方块）。 */
    public static final int MAX_WIDTH = 128;
    /** 最大方块数（128³）。 */
    public static final int MAX_SIZE = 2097152;
    /** 子块边长。 */
    public static final int CHUNK_WIDTH = 64;
    /** 子块方块数（64³）。 */
    public static final int CHUNK_SIZE = 262144;
    /** 每轴子块数。 */
    private static final int CHUNK_COUNT = 2;
    /** 子块总数（2×2×2）。 */
    private static final int CHUNK_TOTAL = 8;

    protected UUID uuid;
    /** 8 个 64³ 方块快照；{@code null} = 全空气。 */
    protected final PalettedChunk[] chunks;
    /** 哪些子块的"渲染缓存"需要重烘（客户端用）。 */
    protected boolean[][][] blocks_render_update;
    /** 哪些子块的"碰撞体"需要重建（{@link #flushBlockStateUpdates} 消费）。 */
    protected final boolean[][][] blocks_collision_update;
    protected final Map<BlockPos, BlockEntity> blockEntityMap = new HashMap<>();
    /** 每个子块一个体素碰撞体；{@code null} = 该子块没有可碰撞方块。 */
    private final ColliderBody[][][] colliderBodies;
    private final RigidBody rigidBody;
    protected ResourceLocation level;
    protected PhysicalWorld physicalWorld;
    protected volatile boolean locked = false;
    /** 物理体缩放（体素随之缩放，S5 的 scale）。 */
    protected final Vector3d scale = new Vector3d(1.0);
    /** 体素在刚体局部空间里的包围盒（格，-64..63）。 */
    public final IntAABB projectionAABB = new IntAABB();
    /** 把 {@link #projectionAABB} 用当前位姿/缩放变换到世界空间的结果。 */
    public final DoubleAABB worldAABB = new DoubleAABB();

    public static int chunkIndex(int chunkX, int chunkY, int chunkZ) {
        return chunkX * CHUNK_COUNT * CHUNK_COUNT + chunkY * CHUNK_COUNT + chunkZ;
    }

    protected PhysicalBody(Vector3d pos, Quaterniond rotation) {
        this(null, pos, rotation);
    }

    protected PhysicalBody(ResourceLocation level, Vector3d pos, Quaterniond rotation) {
        this(level, pos, rotation, UUID.randomUUID());
    }

    protected PhysicalBody(ResourceLocation level, Vector3d pos, Quaterniond rotation, UUID uuid) {
        this(level, pos, rotation, uuid, false);
    }

    /**
     * @param kinematic true = 运动学体（客户端镜像：位置由服务端位姿驱动、本地推不动）
     */
    protected PhysicalBody(ResourceLocation level, Vector3d pos, Quaterniond rotation, UUID uuid, boolean kinematic) {
        this.level = level;
        this.uuid = uuid;
        this.chunks = new PalettedChunk[CHUNK_TOTAL];
        this.blocks_render_update = new boolean[CHUNK_COUNT][CHUNK_COUNT][CHUNK_COUNT];
        this.blocks_collision_update = new boolean[CHUNK_COUNT][CHUNK_COUNT][CHUNK_COUNT];
        this.rigidBody = new RigidBody(
                kinematic ? RigidBody.Type.KINEMATIC_POSITION : RigidBody.Type.DYNAMIC, pos, rotation);
        this.colliderBodies = new ColliderBody[CHUNK_COUNT][CHUNK_COUNT][CHUNK_COUNT];
    }

    // ==================== 世界挂载 ====================

    public void attachToWorld(PhysicalWorld physicalWorld) {
        ResourceLocation worldLevel = physicalWorld.getLevel();
        if (this.level != null && !this.level.equals(worldLevel)) {
            throw new IllegalArgumentException(
                    "PhysicalBody level " + this.level + " does not match PhysicalWorld level " + worldLevel);
        }
        this.level = worldLevel;
        this.physicalWorld = physicalWorld;
    }

    public void onRemovedFromWorld() {
    }

    /** 服务端位姿同步到客户端镜像体时调用（{@code ClientPhysicalBody} 覆写做插值）。 */
    public void onMoveSync(Vector3d target) {
    }

    // ==================== 位姿 / 速度（一律委派给刚体） ====================

    public void setPos(Vector3d pos) {
        this.rigidBody.setPos(pos);
    }

    public void setNextKinematicPosition(Vector3d pos) {
        this.rigidBody.setNextKinematicPositionDirect(pos);
    }

    public void setRotation(Quaterniond rotation) {
        this.rigidBody.setRotation(rotation);
    }

    public void setLinSpeed(Vector3d linvel) {
        this.rigidBody.setLinvel(linvel);
    }

    public void setAngSpeed(Vector3d angvel) {
        this.rigidBody.setAngvel(angvel);
    }

    public void addForce(RigidBody.Force force) {
        this.rigidBody.applyForce(force);
    }

    public void applyImpulse(Vector3dc impulse) {
        this.rigidBody.applyImpulse(impulse);
    }

    public Vector3d getPos() {
        return this.rigidBody.getPos();
    }

    public Quaterniond getRotation() {
        return this.rigidBody.getRotation();
    }

    public double getMass() {
        return this.rigidBody.getMass();
    }

    public Vector3d getLinvel() {
        return this.rigidBody.getLinvel();
    }

    public Vector3d getAngvel() {
        return this.rigidBody.getAngvel();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PhysicalBody that
                && Objects.equals(this.uuid, that.uuid)
                && Objects.equals(this.level, that.level);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.uuid, this.level);
    }

    @Override
    public String toString() {
        return "PhysicalBody:{Pos:" + this.getPos() + ",Rotate:" + this.getRotation()
                + ",Speed:" + this.getLinvel() + "}";
    }

    // ==================== 方块实体 ====================

    private static boolean blockEntityInBounds(BlockPos pos) {
        int half = 64;
        return pos.getX() >= -half && pos.getX() < half
                && pos.getY() >= -half && pos.getY() < half
                && pos.getZ() >= -half && pos.getZ() < half;
    }

    public void putBlockEntity(BlockPos pos, BlockEntity blockEntity) {
        if (pos != null && blockEntity != null && blockEntityInBounds(pos)) {
            this.blockEntityMap.put(pos.immutable(), blockEntity);
        }
    }

    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.blockEntityMap.get(pos);
    }

    public void removeBlockEntity(BlockPos pos) {
        this.blockEntityMap.remove(pos);
    }

    /** 清掉一个区域内的方块实体（破坏/替换方块时）。 */
    public void clearBlockEntities(BlockPos min, BlockPos max) {
        this.blockEntityMap.keySet().removeIf(pos ->
                pos.getX() >= min.getX() && pos.getX() <= max.getX()
                        && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                        && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ());
    }

    // ==================== 子块与碰撞体 ====================

    private PalettedChunk getOrCreateChunk(int chunkX, int chunkY, int chunkZ) {
        int index = chunkIndex(chunkX, chunkY, chunkZ);
        PalettedChunk chunk = this.chunks[index];
        if (chunk == null) {
            chunk = new PalettedChunk(0);
            this.chunks[index] = chunk;
        }
        return chunk;
    }

    /**
     * 重建一个子块的碰撞体并同步到世界。
     *
     * <p>顺序照 space：<b>先摘旧的、再换、最后加新的</b> —— 颠倒会让新旧碰撞体短暂共存。</p>
     */
    public void upChunk(int chunk_x, int chunk_y, int chunk_z) {
        this.blocks_render_update[chunk_x][chunk_y][chunk_z] = true;
        int move = 64;
        ColliderBody oldCollider = this.colliderBodies[chunk_x][chunk_y][chunk_z];
        PalettedChunk chunk = this.chunks[chunkIndex(chunk_x, chunk_y, chunk_z)];
        boolean[] colliderData = chunk == null ? new boolean[CHUNK_SIZE] : chunk.colliderArray();
        ColliderBody newCollider = null;
        if (voxelBounds(colliderData, new IntAABB())) {
            int bx = chunk_x * CHUNK_WIDTH - move;
            int by = chunk_y * CHUNK_WIDTH - move;
            int bz = chunk_z * CHUNK_WIDTH - move;
            newCollider = new ColliderBody(ColliderBody.Type.VOXEL,
                    new Vector3d(bx * this.scale.x, by * this.scale.y, bz * this.scale.z),
                    new Quaterniond(), 0.01,
                    colliderData, CHUNK_WIDTH, CHUNK_WIDTH, CHUNK_WIDTH,
                    this.scale.x, this.scale.y, this.scale.z);
        }
        this.colliderBodies[chunk_x][chunk_y][chunk_z] = newCollider;
        this.updateProjectionAABB();
        this.updateWorldAABB();
        if (this.physicalWorld != null) {
            if (oldCollider != null) {
                this.physicalWorld.removePhysicalBodyCollider(oldCollider);
            }
            if (newCollider != null) {
                this.physicalWorld.addPhysicalBodyCollider(this, newCollider);
            }
        }
    }

    /** 体素数组的包围盒（索引约定与 {@link PalettedChunk#index} 一致）。 */
    private static boolean voxelBounds(boolean[] colliderData, IntAABB out) {
        out.setEmpty();
        for (int i = 0; i < colliderData.length; i++) {
            if (colliderData[i]) {
                int lx = i & 63;
                int lz = (i >> 6) & 63;
                int ly = i >> 12;
                out.union(lx, ly, lz);
            }
        }
        return out.maxX >= 0;
    }

    /** 从 8 个子块的包围盒合成"体素在局部空间里的整体包围盒"。 */
    public void updateProjectionAABB() {
        this.projectionAABB.setEmpty();
        IntAABB bounds = new IntAABB();
        for (int cx = 0; cx < CHUNK_COUNT; cx++) {
            for (int cy = 0; cy < CHUNK_COUNT; cy++) {
                for (int cz = 0; cz < CHUNK_COUNT; cz++) {
                    PalettedChunk chunk = this.chunks[chunkIndex(cx, cy, cz)];
                    if (chunk != null && voxelBounds(chunk.colliderArray(), bounds)) {
                        int baseX = cx * CHUNK_WIDTH - CHUNK_WIDTH;
                        int baseY = cy * CHUNK_WIDTH - CHUNK_WIDTH;
                        int baseZ = cz * CHUNK_WIDTH - CHUNK_WIDTH;
                        this.projectionAABB.union(baseX + bounds.minX, baseY + bounds.minY, baseZ + bounds.minZ);
                        this.projectionAABB.union(baseX + bounds.maxX, baseY + bounds.maxY, baseZ + bounds.maxZ);
                    }
                }
            }
        }
    }

    /** 把局部包围盒的 8 个角用当前位姿变换到世界空间，取并集。 */
    public void updateWorldAABB() {
        if (!isValidAABB(this.projectionAABB)) {
            this.worldAABB.setEmpty();
            return;
        }
        Vector3d pos = this.getPos();
        Quaterniond rotation = this.getRotation();
        if (pos == null || rotation == null) {
            return; // 刚体刚被摘出/读失败：保留上一次结果，好过抛异常打断物理线程
        }
        double lx0 = this.projectionAABB.minX * this.scale.x;
        double ly0 = this.projectionAABB.minY * this.scale.y;
        double lz0 = this.projectionAABB.minZ * this.scale.z;
        double lx1 = (this.projectionAABB.maxX + 1) * this.scale.x;
        double ly1 = (this.projectionAABB.maxY + 1) * this.scale.y;
        double lz1 = (this.projectionAABB.maxZ + 1) * this.scale.z;
        this.worldAABB.setEmpty();
        Vector3d corner = new Vector3d();
        for (int i = 0; i < 8; i++) {
            corner.set((i & 1) == 0 ? lx0 : lx1, (i & 2) == 0 ? ly0 : ly1, (i & 4) == 0 ? lz0 : lz1);
            rotation.transform(corner).add(pos);
            this.worldAABB.union(corner.x, corner.y, corner.z);
        }
    }

    public static boolean isValidAABB(IntAABB aabb) {
        return aabb.minX <= aabb.maxX && aabb.minY <= aabb.maxY && aabb.minZ <= aabb.maxZ;
    }

    public static boolean isValidAABB(DoubleAABB aabb) {
        return aabb.minX <= aabb.maxX && aabb.minY <= aabb.maxY && aabb.minZ <= aabb.maxZ;
    }

    // ==================== 方块读写 ====================

    /** 单格写入；<b>立刻</b>重建该子块碰撞体（放置/破坏要马上有碰撞）。 */
    public void setBlockState(int x, int y, int z, int blockState) {
        x += 64;
        y += 64;
        z += 64;
        if (x >= 0 && x < MAX_WIDTH && y >= 0 && y < MAX_WIDTH && z >= 0 && z < MAX_WIDTH) {
            int chunk_x = x / CHUNK_WIDTH;
            int chunk_y = y / CHUNK_WIDTH;
            int chunk_z = z / CHUNK_WIDTH;
            PalettedChunk chunk = blockState == 0
                    ? this.chunks[chunkIndex(chunk_x, chunk_y, chunk_z)]
                    : this.getOrCreateChunk(chunk_x, chunk_y, chunk_z);
            if (chunk != null) {
                chunk.set(x % CHUNK_WIDTH, y % CHUNK_WIDTH, z % CHUNK_WIDTH, blockState);
            }
            this.upChunk(chunk_x, chunk_y, chunk_z);
        }
    }

    public void setBlockState(int x, int y, int z, BlockState blockState) {
        this.setBlockState(x, y, z, Block.getId(blockState));
    }

    /**
     * 单格写入但**只打脏标记**（批量同步/导入时用），由 {@link #flushBlockStateUpdates} 批量重建。
     */
    public void setBlockStateDeferred(int x, int y, int z, int blockState) {
        x += 64;
        y += 64;
        z += 64;
        if (x >= 0 && x < MAX_WIDTH && y >= 0 && y < MAX_WIDTH && z >= 0 && z < MAX_WIDTH) {
            int chunkX = x / CHUNK_WIDTH;
            int chunkY = y / CHUNK_WIDTH;
            int chunkZ = z / CHUNK_WIDTH;
            PalettedChunk chunk = blockState == 0
                    ? this.chunks[chunkIndex(chunkX, chunkY, chunkZ)]
                    : this.getOrCreateChunk(chunkX, chunkY, chunkZ);
            if (chunk != null) {
                chunk.set(x % CHUNK_WIDTH, y % CHUNK_WIDTH, z % CHUNK_WIDTH, blockState);
            }
            this.blocks_collision_update[chunkX][chunkY][chunkZ] = true;
        }
    }

    public void setBlockStateDeferred(int x, int y, int z, BlockState blockState) {
        this.setBlockStateDeferred(x, y, z, Block.getId(blockState));
    }

    /**
     * 批量重建所有脏子块。
     *
     * @return 本次重建过的子块坐标，<b>已减 1</b>（即 -1/0 的子块索引）——
     *         上层直接用这个偏移发增量同步包（MPS 的 {@code SyncPhysicalBodyBlockUpdate}）
     */
    public List<Vector3i> flushBlockStateUpdates() {
        List<Vector3i> updatedChunks = new ArrayList<>();
        int chunkOffset = 1;
        for (int x = 0; x < CHUNK_COUNT; x++) {
            for (int y = 0; y < CHUNK_COUNT; y++) {
                for (int z = 0; z < CHUNK_COUNT; z++) {
                    if (this.blocks_collision_update[x][y][z]) {
                        this.blocks_collision_update[x][y][z] = false;
                        this.upChunk(x, y, z);
                        updatedChunks.add(new Vector3i(x - chunkOffset, y - chunkOffset, z - chunkOffset));
                    }
                }
            }
        }
        return updatedChunks;
    }

    /** 整块替换缓存（初始同步/存档恢复）：{@code int[2][2][2][64][64][64]}。 */
    public void replaceBlockStateCache(int[][][][][][] blockStateCache) {
        if (blockStateCache == null || blockStateCache.length != CHUNK_COUNT) {
            throw invalidCacheSize();
        }
        for (int x = 0; x < CHUNK_COUNT; x++) {
            if (blockStateCache[x] == null || blockStateCache[x].length != CHUNK_COUNT) {
                throw invalidCacheSize();
            }
            for (int y = 0; y < CHUNK_COUNT; y++) {
                if (blockStateCache[x][y] == null || blockStateCache[x][y].length != CHUNK_COUNT) {
                    throw invalidCacheSize();
                }
                for (int z = 0; z < CHUNK_COUNT; z++) {
                    int[][][] section = blockStateCache[x][y][z];
                    if (section == null || section.length != CHUNK_WIDTH) {
                        throw invalidCacheSize();
                    }
                    for (int localX = 0; localX < CHUNK_WIDTH; localX++) {
                        if (section[localX] == null || section[localX].length != CHUNK_WIDTH) {
                            throw invalidCacheSize();
                        }
                        for (int localY = 0; localY < CHUNK_WIDTH; localY++) {
                            if (section[localX][localY] == null || section[localX][localY].length != CHUNK_WIDTH) {
                                throw invalidCacheSize();
                            }
                        }
                    }
                    this.chunks[chunkIndex(x, y, z)] = PalettedChunk.from(section);
                    this.blocks_collision_update[x][y][z] = false;
                    this.upChunk(x, y, z);
                }
            }
        }
    }

    private static IllegalArgumentException invalidCacheSize() {
        return new IllegalArgumentException("PhysicalBody cache must be int[2][2][2][64][64][64]");
    }

    /** 区域填充（按子块切分，整子块直接用填充构造省一遍逐格写）。 */
    public void fillBlockState(int sx, int sy, int sz, int ex, int ey, int ez, int blockState) {
        sx = Math.max(0, Math.min(MAX_WIDTH - 1, sx + 64));
        sy = Math.max(0, Math.min(MAX_WIDTH - 1, sy + 64));
        sz = Math.max(0, Math.min(MAX_WIDTH - 1, sz + 64));
        ex = Math.max(0, Math.min(MAX_WIDTH - 1, ex + 64));
        ey = Math.max(0, Math.min(MAX_WIDTH - 1, ey + 64));
        ez = Math.max(0, Math.min(MAX_WIDTH - 1, ez + 64));
        for (int cx = sx / CHUNK_WIDTH; cx <= ex / CHUNK_WIDTH; cx++) {
            for (int cy = sy / CHUNK_WIDTH; cy <= ey / CHUNK_WIDTH; cy++) {
                for (int cz = sz / CHUNK_WIDTH; cz <= ez / CHUNK_WIDTH; cz++) {
                    int startX = cx == sx / CHUNK_WIDTH ? sx % CHUNK_WIDTH : 0;
                    int endX = cx == ex / CHUNK_WIDTH ? ex % CHUNK_WIDTH : CHUNK_WIDTH - 1;
                    int startY = cy == sy / CHUNK_WIDTH ? sy % CHUNK_WIDTH : 0;
                    int endY = cy == ey / CHUNK_WIDTH ? ey % CHUNK_WIDTH : CHUNK_WIDTH - 1;
                    int startZ = cz == sz / CHUNK_WIDTH ? sz % CHUNK_WIDTH : 0;
                    int endZ = cz == ez / CHUNK_WIDTH ? ez % CHUNK_WIDTH : CHUNK_WIDTH - 1;
                    boolean fullChunk = startX == 0 && endX == CHUNK_WIDTH - 1
                            && startY == 0 && endY == CHUNK_WIDTH - 1
                            && startZ == 0 && endZ == CHUNK_WIDTH - 1;
                    if (fullChunk) {
                        this.chunks[chunkIndex(cx, cy, cz)] =
                                blockState == 0 ? null : new PalettedChunk(blockState);
                    } else {
                        PalettedChunk chunk = blockState == 0
                                ? this.chunks[chunkIndex(cx, cy, cz)]
                                : this.getOrCreateChunk(cx, cy, cz);
                        if (chunk != null) {
                            chunk.fillRegion(startX, startY, startZ, endX, endY, endZ, blockState);
                        }
                    }
                    this.upChunk(cx, cy, cz);
                }
            }
        }
    }

    public void fillBlockState(int sx, int sy, int sz, int ex, int ey, int ez, BlockState blockState) {
        this.fillBlockState(sx, sy, sz, ex, ey, ez, Block.getId(blockState));
    }

    public int getBlockStateInt(int x, int y, int z) {
        x += 64;
        y += 64;
        z += 64;
        if (x >= 0 && x < MAX_WIDTH && y >= 0 && y < MAX_WIDTH && z >= 0 && z < MAX_WIDTH) {
            PalettedChunk chunk = this.chunks[chunkIndex(x / CHUNK_WIDTH, y / CHUNK_WIDTH, z / CHUNK_WIDTH)];
            return chunk == null ? 0 : chunk.get(x % CHUNK_WIDTH, y % CHUNK_WIDTH, z % CHUNK_WIDTH);
        }
        return 0;
    }

    public BlockState getBlockState(int x, int y, int z) {
        return Block.stateById(this.getBlockStateInt(x, y, z));
    }

    /** 整子块替换（子块坐标 -1/0）。 */
    public void setBlockStateChunk(int chunk_x, int chunk_y, int chunk_z, int[][][] blockState) {
        chunk_x++;
        chunk_y++;
        chunk_z++;
        if (chunk_x >= 0 && chunk_x < CHUNK_COUNT
                && chunk_y >= 0 && chunk_y < CHUNK_COUNT
                && chunk_z >= 0 && chunk_z < CHUNK_COUNT) {
            this.chunks[chunkIndex(chunk_x, chunk_y, chunk_z)] = PalettedChunk.from(blockState);
            this.upChunk(chunk_x, chunk_y, chunk_z);
        }
    }

    public void setBlockStateChunk(int chunk_x, int chunk_y, int chunk_z, BlockState[][][] blockState) {
        int[][][] arr = new int[CHUNK_WIDTH][CHUNK_WIDTH][CHUNK_WIDTH];
        for (int x = 0; x < CHUNK_WIDTH; x++) {
            for (int y = 0; y < CHUNK_WIDTH; y++) {
                for (int z = 0; z < CHUNK_WIDTH; z++) {
                    arr[x][y][z] = Block.getId(blockState[x][y][z]);
                }
            }
        }
        this.setBlockStateChunk(chunk_x, chunk_y, chunk_z, arr);
    }

    public int[][][] getBlockStateChunkInt(int chunk_x, int chunk_y, int chunk_z) {
        chunk_x++;
        chunk_y++;
        chunk_z++;
        if (chunk_x >= 0 && chunk_x < CHUNK_COUNT
                && chunk_y >= 0 && chunk_y < CHUNK_COUNT
                && chunk_z >= 0 && chunk_z < CHUNK_COUNT) {
            PalettedChunk chunk = this.chunks[chunkIndex(chunk_x, chunk_y, chunk_z)];
            return chunk == null ? new int[CHUNK_WIDTH][CHUNK_WIDTH][CHUNK_WIDTH] : chunk.toInt3();
        }
        return new int[CHUNK_WIDTH][CHUNK_WIDTH][CHUNK_WIDTH];
    }

    public BlockState[][][] getBlockStateChunk(int chunk_x, int chunk_y, int chunk_z) {
        int[][][] arr = this.getBlockStateChunkInt(chunk_x, chunk_y, chunk_z);
        BlockState[][][] bs = new BlockState[CHUNK_WIDTH][CHUNK_WIDTH][CHUNK_WIDTH];
        for (int x = 0; x < CHUNK_WIDTH; x++) {
            for (int y = 0; y < CHUNK_WIDTH; y++) {
                for (int z = 0; z < CHUNK_WIDTH; z++) {
                    bs[x][y][z] = Block.stateById(arr[x][y][z]);
                }
            }
        }
        return bs;
    }

    // ==================== 访问器 ====================

    public UUID getUuid() {
        return this.uuid;
    }

    public ColliderBody[][][] getColliderBodies() {
        return this.colliderBodies;
    }

    public RigidBody getRigidBody() {
        return this.rigidBody;
    }

    public ResourceLocation getLevel() {
        return this.level;
    }

    public void setLevel(ResourceLocation level) {
        this.level = level;
    }

    public PhysicalWorld getPhysicalWorld() {
        return this.physicalWorld;
    }

    public void setPhysicalWorld(PhysicalWorld physicalWorld) {
        this.physicalWorld = physicalWorld;
    }

    public boolean isLocked() {
        return this.locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    /** 该子块是否需要重烘渲染缓存（客户端用）。 */
    public boolean takeRenderUpdate(int chunkX, int chunkY, int chunkZ) {
        boolean v = this.blocks_render_update[chunkX][chunkY][chunkZ];
        this.blocks_render_update[chunkX][chunkY][chunkZ] = false;
        return v;
    }
}
