package com.mss.polymech.mps.physical.physical_world;

import com.mss.polymech.mps.physical.entity.PhysicalEntity;
import com.mss.polymech.mps.physical.helper.PhysicalChunk;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端物理世界 —— <b>与 {@code org.polaris2023.mps.physical.physical_world.ClientPhysicalWorld}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <h2>职责</h2>
 * 客户端那份"我这个维度自己跑一遍"的物理世界：地形区块的碰撞体、以及<b>玩家自己的双刚体</b>。
 *
 * <h2>为什么是这个形态（照 space 0.1.3，别改）</h2>
 * <ul>
 *   <li><b>玩家刚体登记在世界上，由 {@link #step()} 每子步驱动</b>（而不是在
 *       {@code Entity.move} 里驱动）：速度继承链必须按物理子步节奏跑，
 *       放在 move 里就变成 20Hz —— 一个 tick 走 5 个子步，子步之间速度会漂。</li>
 *   <li><b>地形是"区块事件驱动"的</b>（{@link #onChunkReceived}/{@link #onChunkDropped}/
 *       {@link #onChunkBlockChanged}）：客户端只在区块真的到手/卸载/改块时重建，
 *       不需要每 tick 扫区块。这与服务端 {@code PhysicalChunkManager} 的"兴趣点 + 迟滞"不同，
 *       因为客户端本来就只持有已加载区块。</li>
 *   <li><b>重建时先摘旧碰撞体再建新的</b>，且体素与复合盒分开判空 —— 顺序颠倒会让新旧共存
 *       （重叠区的解算冲量会炸）。</li>
 *   <li><b>静态单例</b>（{@link #getPhysicalWorld()}）：MPS 的调用方（{@code MixinEntity}、
 *       渲染）拿不到世界引用，只能从这里取；换维度时由 {@link #init()} 清掉。</li>
 * </ul>
 */
public class ClientPhysicalWorld extends PhysicalWorld {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("PolyMech/MPS/Terrain");

    /**
     * 单个区块的地形重建超过它就记 WARN（毫秒）。
     *
     * <p>这条线存在的理由：区块重建是**同步**跑在 {@code replaceWithPacketData} 回调里的，
     * 一旦单区块几十毫秒，玩家感受到的就是"每加载一个区块卡一下"。日志把
     * <b>Java 扫描</b>与<b>原生建体</b>分开计时 —— 两者优化手段完全不同
     *（前者是调色板快路径，后者是原生形状/是否按段分体），不分开就只能猜。</p>
     */
    private static final long TERRAIN_REBUILD_WARN_MS = 5L;

    private static ClientPhysicalWorld physicalWorldInstance = null;

    /** 已建碰撞体的地形区块。 */
    protected final Map<ChunkPos, PhysicalChunk> physicalChunks = new HashMap<>();
    /** 本世界的玩家双刚体（{@link #step()} 每子步驱动它们的速度继承链）。 */
    private final Set<PhysicalEntity> playerEntities = ConcurrentHashMap.newKeySet();

    public ClientPhysicalWorld(ResourceLocation level, Vector3d g) {
        super(level, g);
    }

    public void registerPlayerEntity(PhysicalEntity physicalEntity) {
        this.playerEntities.add(physicalEntity);
    }

    public void unregisterPlayerEntity(PhysicalEntity physicalEntity) {
        this.playerEntities.remove(physicalEntity);
    }

    public static void init() {
        physicalWorldInstance = null;
    }

    /**
     * 每个物理子步：先走求解器，再跑所有玩家刚体的速度继承链
     * （{@link PhysicalEntity#afterStep()} —— 必须在子步边界跑，见类注释）。
     */
    @Override
    public void step() {
        super.step();
        for (PhysicalEntity physicalEntity : this.playerEntities) {
            physicalEntity.afterStep();
        }
    }

    // ==================== 区块事件 ====================

    /**
     * 本世界<b>是否真的需要地形碰撞体</b>。
     *
     * <h2>为什么必须有这道门（实测，不是猜）</h2>
     * 2026-09 一次"未响应"现场：`ClientCollisionPhysicalThread-step` 在 83 秒里烧掉
     * <b>41.4 秒 CPU（≈半个核持续）</b>，而那个世界 `getAllPhysicalBody()` <b>为空</b> ——
     * 里面只有"每个已载区块约 4 万格体素"的地形碰撞体，100 Hz 空转。
     * 玩家人在 `poly_mech:venus`（地表），项目自己那套客户端物理<b>按设计是关的</b>
     * （`shouldSimulate=false`，线程都不在），于是这半个核完全是为一份没人用的地形付的。
     *
     * <p>代价不在"建"那一刻（实测单区块 8 ms），而在<b>之后每一步都要扛着它</b> ——
     * 这才是"卡"的成因，也是"每维度都建"这条 space 设计在我们这个
     * "克隆层还没有任何物理体"的阶段所付出的纯成本。</p>
     *
     * <p>判定用 {@code getAllPhysicalBody()}：它**不含玩家刚体**
     * （玩家那条走 {@code addColliderBody(..., rigidBody)}，不进 physicalBodies 列表），
     * 所以"有船/有物理体"才是真的需要地形。</p>
     */
    private boolean terrainNeeded() {
        return !this.getAllPhysicalBody().isEmpty();
    }

    /** 跳过的提示（节流 5 秒一次，避免自己变成刷屏源）。 */
    private static long lastSkipLogMs = 0L;

    private void noteTerrainSkipped(String why) {
        long now = System.currentTimeMillis();
        if (now - lastSkipLogMs < 5000L) {
            return;
        }
        lastSkipLogMs = now;
        LOGGER.info("[MPS] [Terrain] 本维度暂无物理体 ⇒ {}（省下的正是每步 100Hz 扛着全部已载区块体素的成本）", why);
    }

    /** 区块到手：建碰撞体（<b>没有物理体时不建</b>，见 {@link #terrainNeeded()}）。 */
    public void onChunkReceived(LevelChunk levelChunk) {
        ChunkPos chunkPos = levelChunk.getPos();
        if (!terrainNeeded()) {
            noteTerrainSkipped("区块 " + chunkPos + " 不体素化");
            return;
        }
        PhysicalChunk physicalChunk = this.physicalChunks.get(chunkPos);
        if (physicalChunk == null) {
            physicalChunk = new PhysicalChunk(chunkPos);
            this.physicalChunks.put(chunkPos, physicalChunk);
        }
        Level level = levelChunk.getLevel();
        this.rebuildChunkColliders(physicalChunk, level);
    }

    /**
     * 第一个物理体进入本世界时：把玩家周围的区块补建起来。
     *
     * <p>为什么要有这一步：上面的门是"没体就不建"，若只做那一半，第一个物理体
     * 出现时它脚下会是空的（走进去直接穿透）。补建范围先取玩家附近 3×3 区块
     * （与项目自己那套 `ClientPhysics.TERRAIN_RADIUS` 同量级），其余区块在
     * 后续的"到手/改块"事件里自然补齐 —— 完整的"兴趣点 + 迟滞"由
     * space 的 {@code PhysicalChunkManager} 负责，那也是 S6 要把创建动作接上时的正解。</p>
     */
    @Override
    public boolean addPhysicalBody(PhysicalBody physicalBody) {
        boolean added = super.addPhysicalBody(physicalBody);
        if (added) {
            this.rebuildAroundPlayer();
        }
        return added;
    }

    /** 补建玩家周围 3×3 的区块（只补已载的，不主动请求加载）。 */
    private void rebuildAroundPlayer() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        int cx = mc.player.getBlockX() >> 4;
        int cz = mc.player.getBlockZ() >> 4;
        int built = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(cx + dx, cz + dz);
                PhysicalChunk physicalChunk = this.physicalChunks.computeIfAbsent(pos, PhysicalChunk::new);
                this.rebuildChunkColliders(physicalChunk, mc.level);
                built++;
            }
        }
        LOGGER.info("[MPS] [Terrain] 首次出现物理体 ⇒ 补建玩家周围 {} 个区块的地形碰撞体", built);
    }

    /** 区块卸载：摘碰撞体。 */
    public void onChunkDropped(ChunkPos chunkPos) {
        PhysicalChunk physicalChunk = this.physicalChunks.remove(chunkPos);
        if (physicalChunk == null) {
            return;
        }
        if (physicalChunk.getVoxelColliderBody() != null) {
            this.removeColliderBody(physicalChunk.getVoxelColliderBody());
            physicalChunk.destroyVoxelCollider();
        }
        if (physicalChunk.getCuboidColliderBody() != null) {
            this.removeColliderBody(physicalChunk.getCuboidColliderBody());
            physicalChunk.destroyCuboidColliders();
        }
    }

    /** 区块内方块变了：重建该区块。 */
    public void onChunkBlockChanged(ChunkPos chunkPos) {
        PhysicalChunk physicalChunk = this.physicalChunks.get(chunkPos);
        if (physicalChunk == null) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level != null) {
            this.rebuildChunkColliders(physicalChunk, level);
        }
    }

    /**
     * 重建一个地形区块的两种碰撞体。
     *
     * <p>体素与复合盒分别判空：半砖/楼梯那种区块可能只有盒、没有满格。</p>
     */
    private void rebuildChunkColliders(PhysicalChunk physicalChunk, Level level) {
        long tStart = System.nanoTime();
        if (physicalChunk.getVoxelColliderBody() != null) {
            this.removeColliderBody(physicalChunk.getVoxelColliderBody());
            physicalChunk.destroyVoxelCollider();
        }
        if (physicalChunk.getCuboidColliderBody() != null) {
            this.removeColliderBody(physicalChunk.getCuboidColliderBody());
            physicalChunk.destroyCuboidColliders();
        }

        long tScan = System.nanoTime();
        List<Object> data = physicalChunk.getBlockArray(level);
        boolean[] fullBlocks = (boolean[]) data.get(0);
        double[] boxData = (double[]) data.get(1);
        long scanMs = (System.nanoTime() - tScan) / 1_000_000L;
        ChunkPos pos = physicalChunk.getChunkPos();
        // 注意：这里传的是 y 方向的**格数**（段数 × 16），不是段数
        int h = level.getChunk(pos.x, pos.z).getSections().length * 16;
        long tNative = System.nanoTime();
        physicalChunk.buildVoxelCollider(fullBlocks, level.getMinBuildHeight(), h);
        if (physicalChunk.getVoxelColliderBody() != null) {
            this.addColliderBody(physicalChunk.getVoxelColliderBody());
        }
        physicalChunk.buildCuboidCollider(boxData);
        if (physicalChunk.getCuboidColliderBody() != null) {
            this.addColliderBody(physicalChunk.getCuboidColliderBody());
        }
        long nativeMs = (System.nanoTime() - tNative) / 1_000_000L;
        long totalMs = (System.nanoTime() - tStart) / 1_000_000L;
        if (totalMs >= TERRAIN_REBUILD_WARN_MS) {
            int cells = 0;
            for (boolean b : fullBlocks) {
                if (b) {
                    cells++;
                }
            }
            LOGGER.warn("[MPS] [Terrain] 区块地形重建 {} ms（Java 扫描 {} ms / 原生建体 {} ms；"
                            + "满格 {} 格，复合盒 {} 个，段数 {}) @ {} —— 同步跑在区块到手回调里，"
                            + "玩家感受就是「每加载一个区块卡一下」",
                    totalMs, scanMs, nativeMs, cells, boxData.length / 6, h / 16, pos);
        }
    }

    public Map<ChunkPos, PhysicalChunk> getPhysicalChunks() {
        return this.physicalChunks;
    }

    public static ClientPhysicalWorld getPhysicalWorld() {
        return physicalWorldInstance;
    }

    public static void setPhysicalWorld(ClientPhysicalWorld physicalWorld) {
        physicalWorldInstance = physicalWorld;
    }
}
