package com.mss.polymech.physics;

import com.mss.polymech.Polymech;
import com.mss.polymech.network.PhysicsBodySyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * 投影维度：让"物理体上的方块"在一个隐藏维度里以<b>真实方块</b>存在，
 * 于是机器、红石、熔炉这些需要 tick / 方块实体的东西在太空结构上照常工作。
 *
 * <p><b>照 space/MPS 的 {@code mps:projection_world}</b>：每个物理体在隐藏维度里分到一块
 * {@value #SLOT_SIZE}³ 的"地皮"，槽位按网格排布（间距 {@value #SLOT_SPACING}，留 64 格空隙，
 * 免得相邻体的流体/光照互相干扰），地皮覆盖的区块<b>强制加载</b> —— 那边是真实区块，
 * 方块实体照常 tick，破坏/放置/使用都能直接跑原版逻辑。</p>
 *
 * <p><b>与物理的关系是解耦的</b>：碰撞与运动仍由 Rapier 刚体负责（它的体素碰撞体来自方块缓存）；
 * 投影维度只负责"方块逻辑活着"。投影里发生的变化回写到方块缓存后，碰撞体随之重建 ——
 * 这就是"会动的船 + 船上机器照常工作"的实现方式。</p>
 *
 * <p><b>维度来源</b>：datapack 文件，不在代码里注入 ——
 * {@code data/poly_mech/dimension/projection_world.json} +
 * {@code data/poly_mech/dimension_type/projection_world.json}（与 {@code space.json} 同一种做法）。</p>
 *
 * <p>当前阶段只搭骨架（拿维度、分槽位、强制加载）；方块与方块实体的复制/回写/同步在后续阶段接。</p>
 */
public final class ProjectionManager {

    /** 隐藏投影维度 id。 */
    public static final ResourceLocation DIMENSION_ID =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "projection_world");

    /** 每块地皮边长（格）。 */
    public static final int SLOT_SIZE = 128;
    /** 地皮间距：地皮之间留 64 格空隙。 */
    public static final int SLOT_SPACING = SLOT_SIZE + 64;
    /** 地皮底部 Y（dimension_type 的 min_y = -256、height = 512，容得下）。 */
    public static final int SLOT_MIN_Y = -64;

    private static ServerLevel level;
    /**
     * 已占用的槽位（升序）。分配时取<b>最小空闲号</b>，销毁后立刻可被新物理体复用 ——
     * 只用自增计数器的话，每撸掉一个体就永久漏掉一块地皮（= 8×8 个强制加载的区块）。
     */
    private static final java.util.TreeSet<Integer> USED_SLOTS = new java.util.TreeSet<>();
    /** 物理体 id → 地皮槽位。 */
    private static final Map<Long, Integer> SLOT_OF_BODY = new HashMap<>();

    /**
     * 投影维度里被改动过的区块（打包成 {@code ChunkPos.asLong}）。
     *
     * <p>由 {@code ProjectionChunkDirtyMixin} 挂在 {@code LevelChunk#setBlockState} 上标记 ——
     * 红石、活塞、机器这些在投影里跑的改动都走那条路径，于是"投影变了"能被我们感知到，
     * 每 tick 只回写这些脏区块（而不是扫 128³ 全区）。</p>
     */
    private static final java.util.Set<Long> DIRTY_CHUNKS = new java.util.HashSet<>();

    /** 每 tick 最多回写的区块数：红石连锁可能一次脏掉很多区块，限流避免卡顿。 */
    private static final int MAX_FLUSH_PER_TICK = 8;

    /**
     * 批量写入期间抑制脏标记：整批写方块会在投影里触发成百上千次 {@code setBlockState}，
     * 那些都不是"内容变化"，不该让 64 个区块排队等回写。
     * 抑制窗口结束后再统一刷邻居更新 —— 那一步产生的变化才需要回写。
     */
    private static boolean suppressDirty;

    private ProjectionManager() {
    }

    /**
     * 服务端启动时调用。<b>必须早于</b> {@code PhysicsBodyTracker.restore} ——
     * 存档里恢复出来的物理体要能立刻挂上投影。
     *
     * @return 投影维度是否可用
     */
    public static boolean init(MinecraftServer server) {
        level = server.getLevel(ResourceKey.create(Registries.DIMENSION, DIMENSION_ID));
        USED_SLOTS.clear();
        SLOT_OF_BODY.clear();
        if (level == null) {
            Polymech.LOGGER.error("[PolyMech] 找不到投影维度 {} —— 检查 data/{}/dimension/projection_world.json 是否存在",
                    DIMENSION_ID, Polymech.MOD_ID);
            return false;
        }
        Polymech.LOGGER.info("[PolyMech] 投影维度就绪：{}（地皮 {}³，间距 {}）",
                level.dimension().location(), SLOT_SIZE, SLOT_SPACING);
        return true;
    }

    public static boolean isReady() {
        return level != null;
    }

    /** 投影维度；未就绪返回 null。 */
    public static ServerLevel level() {
        return level;
    }

    /** 已分配的地皮数（诊断用）。 */
    public static int allocatedSlots() {
        return USED_SLOTS.size();
    }

    /** 当前占用中的最大槽位号 + 1（诊断"地皮是否被复用"用）。 */
    public static int slotCeiling() {
        return USED_SLOTS.isEmpty() ? 0 : USED_SLOTS.last() + 1;
    }

    /** 第 slot 块地皮的起点（最小角）。 */
    public static BlockPos startOf(int slot) {
        int half = SLOT_SIZE / 2;
        return new BlockPos(slot * SLOT_SPACING - half, SLOT_MIN_Y, -half);
    }

    /**
     * 给物理体分配一块地皮，并强制加载其区块。同一个体重复调用返回同一槽位。
     *
     * @return 槽位号；投影维度未就绪返回 -1
     */
    public static int allocate(long bodyId) {
        if (level == null) {
            return -1;
        }
        Integer existing = SLOT_OF_BODY.get(bodyId);
        if (existing != null) {
            return existing;
        }
        // 最小空闲槽位（0,1,2…里第一个没被占的）
        int slot = 0;
        for (int used : USED_SLOTS) {
            if (used == slot) {
                slot++;
            } else {
                break;
            }
        }
        USED_SLOTS.add(slot);
        SLOT_OF_BODY.put(bodyId, slot);
        setForced(slot, true);
        // 复用一块地皮之前先清干净。**这一步是防"幽灵方块"**：
        // 早期版本/崩溃留下的残留方块不在刚体缓存里 → 不渲染（看不见），
        // 但在投影维度里真实存在、**能导电** —— 表现就是"有什么看不见的东西在给活塞充能"。
        // 正常路径下地皮早在销毁时按清单清空了，这里会一路 hasOnlyAir 跳过，几乎零成本。
        int orphans = wipeSlot(slot);
        if (orphans > 0) {
            Polymech.LOGGER.warn("[PolyMech] 地皮 {} 复用前清掉 {} 个残留方块（孤儿方块可能导电但不渲染）",
                    slot, orphans);
        }
        return slot;
    }

    /** 物理体占用的槽位；没分配返回 -1。 */
    public static int slotOf(long bodyId) {
        Integer slot = SLOT_OF_BODY.get(bodyId);
        return slot == null ? -1 : slot;
    }

    /**
     * 恢复存档时按记录把地皮重新挂回该物理体。
     *
     * <p>槽位是<b>持久化</b>的（{@code PhysicsBodySavedData.Entry#slot}），这里只做登记与强制加载，
     * <b>绝不重新编号</b> —— 地皮里的方块实体状态存在维度 region 文件里，
     * 换一个槽位就等于把它丢了。</p>
     */
    public static void restoreSlot(long bodyId, int slot) {
        if (level == null || slot < 0) {
            return;
        }
        USED_SLOTS.add(slot);
        SLOT_OF_BODY.put(bodyId, slot);
        setForced(slot, true);
    }

    /** 读地皮上某个局部坐标的方块状态；投影维度未就绪返回 null。 */
    public static BlockState blockStateAt(int slot, int dx, int dy, int dz) {
        if (level == null || slot < 0) {
            return null;
        }
        return level.getBlockState(startOf(slot).offset(dx, dy, dz));
    }

    /** 地皮上某个局部坐标是否已有方块（O(1)，用来判断地皮内容是否还在）。 */
    public static boolean hasBlockAt(int slot, int dx, int dy, int dz) {
        if (level == null || slot < 0) {
            return false;
        }
        return !level.getBlockState(startOf(slot).offset(dx, dy, dz)).isAir();
    }

    /** 释放地皮（取消区块强制加载）。物理体被销毁时调用。 */
    public static void release(long bodyId) {
        Integer slot = SLOT_OF_BODY.remove(bodyId);
        if (slot != null) {
            USED_SLOTS.remove(slot);
            setForced(slot, false);
        }
    }

    /** 服务端停止：清空分配表（Level 引用交给服务端自己回收）。 */
    public static void reset() {
        level = null;
        USED_SLOTS.clear();
        SLOT_OF_BODY.clear();
        DIRTY_CHUNKS.clear();
    }

    // ==================== 方块与方块实体 ====================

    /**
     * 待写入地皮的一个方块：地皮内的局部坐标 + 方块状态 + 方块实体 NBT（没有则 null）。
     *
     * <p>NBT 用 {@code BlockEntity#saveWithFullMetadata} 从源世界抓取，写入时用
     * {@code BlockEntity#loadStatic} 还原并挂进地皮 —— 于是熔炉里烧着的东西、
     * 箱子里的物品、机器的内部状态都跟着结构一起走。</p>
     */
    public record BlockToWrite(int dx, int dy, int dz, BlockState state, CompoundTag beTag) {
    }

    /**
     * 把一批方块（含方块实体）写进第 slot 块地皮。
     *
     * <p>逐个 {@code setBlock + setBlockEntity}，用 {@code UPDATE_CLIENTS} 而非
     * {@code UPDATE_ALL}：整批写完之前不触发邻居更新，免得机器在半成品状态下先跑一轮
     * （半截漏斗网络、半截管道）。地皮所在区块已强制加载，所以这里不会卡加载。</p>
     *
     * @return 实际写入的方块数
     */
    public static int writeBlocks(int slot, java.util.List<BlockToWrite> blocks) {
        if (level == null || blocks == null || blocks.isEmpty()) {
            return 0;
        }
        BlockPos start = startOf(slot);
        int written = 0;
        suppressDirty = true; // 批量写入本身不算"内容变化"，别让 64 个区块排队等回写
        try {
            for (BlockToWrite b : blocks) {
                BlockPos pos = start.offset(b.dx(), b.dy(), b.dz());
                level.setBlock(pos, b.state(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
                CompoundTag tag = b.beTag();
                if (tag != null) {
                    BlockEntity be = BlockEntity.loadStatic(pos, b.state(), tag, level.registryAccess());
                    if (be != null) {
                        level.setBlockEntity(be);
                    }
                }
                written++;
            }
        } finally {
            suppressDirty = false;
        }
        // 整批静默写完之后，统一刷一遍邻居更新。**这一步不能省**：
        // 红石元件的 lit/powered 是"抓取那一刻的快照"（源世界里由区域外的电路供电），
        // 不给更新就永远不会被重算 —— 表现就是"没线连着还亮""敲掉火把灯不灭"。
        // 顺序也重要：先整批写完再刷，避免半成品结构被更新成瞬态（导线当场脱落之类）。
        // 这一步产生的变化会正常标脏，由每 tick 的回写送到客户端。
        for (BlockToWrite b : blocks) {
            BlockPos pos = start.offset(b.dx(), b.dy(), b.dz());
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                level.updateNeighborsAt(pos, state.getBlock());
            }
        }
        return written;
    }

    /**
     * 清空该地皮上"这批方块坐标"所在的位置（连同方块实体）。
     *
     * <p>只清已知坐标而不是扫 128³ 全区：写进去的就是这些位置，按清单清既准又快。
     * （若将来机器自己往地皮别处放了方块，需要改成区域清扫。）</p>
     */
    public static int clearBlocks(int slot, java.util.List<PhysicsBodySyncPacket.BlockEntry> blocks) {
        if (level == null || blocks == null) {
            return 0;
        }
        BlockPos start = startOf(slot);
        int cleared = 0;
        for (PhysicsBodySyncPacket.BlockEntry e : blocks) {
            BlockPos pos = start.offset(e.dx(), e.dy(), e.dz());
            level.removeBlockEntity(pos);
            level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 0);
            cleared++;
        }
        return cleared;
    }

    /**
     * 把整块地皮清成空气（含方块实体）。
     *
     * <p>用于清理"孤儿地皮"：早期版本（或崩溃）可能留下没被清掉的方块，
     * 而槽位现在会被新物理体复用 —— 不清的话新结构里会混进幽灵方块。
     * 128³ 逐格写，是<b>手动清理命令</b>，不要在常规路径上调用。</p>
     *
     * @return 被清掉的非空气方块数
     */
    public static int wipeSlot(int slot) {
        if (level == null || slot < 0) {
            return -1;
        }
        BlockPos start = startOf(slot);
        int minChunkX = start.getX() >> 4;
        int minChunkZ = start.getZ() >> 4;
        int span = SLOT_SIZE >> 4;
        int cleared = 0;
        boolean wasSuppressed = suppressDirty;
        suppressDirty = true; // 清理是内部维护，不是"内容变化"，不必让这 64 个区块排队回写
        try {
            for (int cx = minChunkX; cx < minChunkX + span; cx++) {
                for (int cz = minChunkZ; cz < minChunkZ + span; cz++) {
                    net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                    net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
                    for (int i = 0; i < sections.length; i++) {
                        int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                        if (baseY + 15 < start.getY() || baseY >= start.getY() + SLOT_SIZE) {
                            continue;
                        }
                        net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
                        if (section == null || section.hasOnlyAir()) {
                            continue; // ← 空段 O(1) 跳过：正常路径下 512 个段全走这里，几乎零成本
                        }
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    if (!section.getBlockState(x, y, z).isAir()) {
                                        level.setBlock(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z),
                                                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 0);
                                        cleared++;
                                    }
                                }
                            }
                        }
                    }
                    // 顺带清掉落在本槽位 Y 带里的方块实体（防止"方块没了、实体还在"）
                    for (BlockPos bePos : new java.util.ArrayList<>(chunk.getBlockEntities().keySet())) {
                        if (bePos.getY() >= start.getY() && bePos.getY() < start.getY() + SLOT_SIZE) {
                            level.removeBlockEntity(bePos);
                        }
                    }
                }
            }
        } finally {
            suppressDirty = wasSuppressed;
        }
        return cleared;
    }

    /**
     * 读回地皮上某个方块实体的 NBT（{@code drop} 把结构放回世界时用）。
     *
     * <p>用 {@code saveWithFullMetadata} 抓：机器在投影维度里 tick 出来的状态都在这里，
     * 只写回方块状态会把它丢掉。</p>
     *
     * @return NBT；该位置没有方块实体（或维度未就绪）返回 null
     */
    public static CompoundTag blockEntityTag(int slot, int dx, int dy, int dz) {
        if (level == null || slot < 0) {
            return null;
        }
        BlockPos pos = startOf(slot).offset(dx, dy, dz);
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.saveWithFullMetadata(level.registryAccess());
    }

    /**
     * 诊断：统计该地皮上"真实存在的非空气方块数"与"方块实体数"。
     *
     * <p>用区块 section 的 {@code hasOnlyAir()} 跳过空段，避免 128³ 全区逐格读。</p>
     */
    public static String describeSlot(int slot) {
        if (level == null) {
            return "投影维度未就绪";
        }
        BlockPos start = startOf(slot);
        int blockCount = 0;
        int beCount = 0;
        int minChunkX = start.getX() >> 4;
        int minChunkZ = start.getZ() >> 4;
        int span = SLOT_SIZE >> 4;
        for (int cx = minChunkX; cx < minChunkX + span; cx++) {
            for (int cz = minChunkZ; cz < minChunkZ + span; cz++) {
                net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(cx, cz);
                net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    net.minecraft.world.level.chunk.LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
                    if (baseY + 15 < start.getY() || baseY >= start.getY() + SLOT_SIZE) {
                        continue;
                    }
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (!section.getBlockState(x, y, z).isAir()) {
                                    blockCount++;
                                }
                            }
                        }
                    }
                }
                for (BlockPos bePos : chunk.getBlockEntities().keySet()) {
                    if (bePos.getX() >= start.getX() && bePos.getX() < start.getX() + SLOT_SIZE
                            && bePos.getZ() >= start.getZ() && bePos.getZ() < start.getZ() + SLOT_SIZE
                            && bePos.getY() >= start.getY() && bePos.getY() < start.getY() + SLOT_SIZE) {
                        beCount++;
                    }
                }
            }
        }
        return String.format("地皮 %d 起点 %s：非空气方块 %d，方块实体 %d",
                slot, start.toShortString(), blockCount, beCount);
    }

    /** 强制/取消强制加载一块地皮覆盖的全部区块（128 格 = 8 个区块）。 */
    private static void setForced(int slot, boolean forced) {
        if (level == null) {
            return;
        }
        BlockPos start = startOf(slot);
        int minChunkX = start.getX() >> 4;
        int minChunkZ = start.getZ() >> 4;
        int span = SLOT_SIZE >> 4;
        for (int cx = minChunkX; cx < minChunkX + span; cx++) {
            for (int cz = minChunkZ; cz < minChunkZ + span; cz++) {
                level.setChunkForced(cx, cz, forced);
            }
        }
    }

    /** 维度键（诊断用）。 */
    public static ResourceKey<Level> dimensionKey() {
        return ResourceKey.create(Registries.DIMENSION, DIMENSION_ID);
    }

    // ==================== 脏区块回写（投影 → 刚体缓存） ====================

    /** 投影维度里的方块被改动时（由 mixin 调用）标记该区块需要回写。 */
    public static void markDirty(net.minecraft.world.level.chunk.LevelChunk chunk) {
        if (level == null || suppressDirty || chunk.getLevel() != level) {
            return;
        }
        DIRTY_CHUNKS.add(chunk.getPos().toLong());
    }



    /**
     * 每服务端 tick：把脏区块里发生的变化回写到对应刚体的方块缓存。
     *
     * <p>这是"投影里发生的事能传到玩家眼前"的唯一通道 —— 红石灯亮灭、活塞推块、
     * 机器自改结构，全都要经过这里。</p>
     */
    public static void tick() {
        if (level == null) {
            return;
        }
        // 掉落物搬运：与脏区块无关，每 tick 都要跑
        relocateProjectionDrops();
        if (DIRTY_CHUNKS.isEmpty()) {
            return;
        }
        java.util.Iterator<Long> it = DIRTY_CHUNKS.iterator();
        java.util.Set<Long> flushing = new java.util.HashSet<>();
        while (it.hasNext() && flushing.size() < MAX_FLUSH_PER_TICK) {
            flushing.add(it.next());
            it.remove();
        }
        for (long key : flushing) {
            int cx = ChunkPos.getX(key);
            int cz = ChunkPos.getZ(key);
            int slot = slotOfChunk(cx, cz);
            if (slot < 0) {
                continue;
            }
            long bodyId = bodyOfSlot(slot);
            if (bodyId >= 0) {
                PhysicsBodyTracker.reconcileChunkFromProjection(bodyId, slot, cx, cz);
            }
        }
    }

    /**
     * 区块坐标 → 槽位号；不属于任何地皮返回 -1。
     *
     * <p>地皮沿 X 网格排布、间距 {@link #SLOT_SPACING}，所以第 s 块地皮覆盖的区块是
     * {@code X ∈ [12s-4, 12s+4)}、{@code Z ∈ [-4, 4)}（因为间距 192 = 12 区块、地皮宽 128 = 8 区块）。</p>
     */
    public static int slotOfChunk(int cx, int cz) {
        int per = SLOT_SPACING >> 4;        // 12 区块
        int half = (SLOT_SIZE >> 1) >> 4;   // 4 区块
        int span = SLOT_SIZE >> 4;          // 8 区块
        // 槽位只沿 X 排列，Z 带固定以 0 为中心 —— 所以槽位号只能从 X 推，
        // 再单独校验 Z 是否落在那条固定带里。（早先把 X/Z 推出来的槽位号要求相等，
        // 结果 slots ≥ 1 全部被判成"不属于任何地皮"，那些体的红石变化永远不回写。）
        int slot = Math.floorDiv(cx + half, per);
        int ox = cx + half - slot * per;
        int oz = cz + half;
        return (ox >= 0 && ox < span && oz >= 0 && oz < span) ? slot : -1;
    }

    /** 诊断：当前待回写的脏区块数。 */
    public static int dirtyChunkCount() {
        return DIRTY_CHUNKS.size();
    }

    /** 诊断：累计从投影维度搬到世界的掉落物数 / 经验球数 / 归属不到任何物理体、被直接销毁的垃圾数。 */
    private static int relocatedDrops;
    private static int relocatedOrbs;
    private static int discardedDrops;

    public static int relocatedDropCount() {
        return relocatedDrops;
    }

    /** 从投影维度搬到世界的经验球数（挖矿/烧炼给的经验会先落在投影里）。 */
    public static int relocatedOrbCount() {
        return relocatedOrbs;
    }

    public static int discardedDropCount() {
        return discardedDrops;
    }

    /**
     * 找出这个投影坐标该归属哪个物理体。
     *
     * <p>先按地皮网格正查；投掷器/发射器会把物品<b>射出地皮边界</b>（飞出 128³ 之外），
     * 那就退一步取 X 上<b>最近的地皮</b>（允许差 1 块），否则那些东西永远搬不走。</p>
     */
    private static long nearestBodyFor(BlockPos pos) {
        if (USED_SLOTS.isEmpty()) {
            return -1L;
        }
        int slot = slotOfProjectionPos(pos);
        if (USED_SLOTS.contains(slot)) {
            return bodyOfSlot(slot);
        }
        int best = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int used : USED_SLOTS) {
            int dist = Math.abs(used - slot);
            if (dist < bestDist) {
                bestDist = dist;
                best = used;
            }
        }
        return bestDist <= 1 ? bodyOfSlot(best) : -1L;
    }

    // ==================== 投影里的掉落物搬运 ====================

    /**
     * 把<b>投影维度里</b>产生的掉落物搬到物理体所在的世界。
     *
     * <p><b>为什么需要</b>：红石导线脱落、活塞砸掉火把、机器吐出物品 —— 这些逻辑都在投影维度里跑，
     * 原版会把掉落物生成在<b>那一格</b>。玩家在太空维度，既看不见也捡不到，那些东西就永远烂在隐藏维度里。</p>
     *
     * <p>做法：投影坐标 →（槽位 → 物理体）→ 用刚体姿态把方块中心变换到世界坐标 →
     * 在<b>物理体所在世界</b>的对应位置喷出来，并让调用方取消投影里的那次生成。
     * 这是 space/MPS 没做的一件事（它的 {@code MixinItemEntity} 只管重力）。</p>
     *
     * <p><b>覆盖范围</b>：走 {@code Block#popResource} 的掉落（绝大多数，含
     * {@code Block#dropResources}、{@code Containers#dropContents}）、直接
     * {@code level.addFreshEntity(new ItemEntity(...))} 的、以及<b>经验球</b>
     * （{@code ExperienceOrb#award}，见 {@link #relocateOrb}）。</p>
     *
     * @return true 表示已经搬到世界侧，调用方应取消投影里的生成
     */
    /**
     * 每 tick 扫一遍投影维度，把<b>所有</b>掉落物/经验球实体搬到世界侧。
     *
     * <p><b>为什么是"扫实体"而不是"拦生成调用"</b>：掉落的生成路径有好几条互不相干 ——
     * {@code Block#popResource}（导线脱落、{@code dropResources}、{@code Containers#dropContents}）、
     * {@code level.addFreshEntity}（<b>投掷器/发射器的 {@code DefaultDispenseItemBehavior.spawnItem}</b>、
     * 部分机器吐物）… 按路径逐个打补丁必然漏（第一版就漏掉了投掷器，所以看起来"完全没用"）。
     * 而投影维度对玩家不可见，<b>那里出现的任何掉落物都是泄漏</b> —— 直接按实体扫，一网打尽。</p>
     *
     * <p>搬不动的（找不到归属物理体）就留在原地，下 tick 再试；反正它会自己消失。</p>
     */
    private static void relocateProjectionDrops() {
        if (level == null) {
            return;
        }
        java.util.List<net.minecraft.world.entity.Entity> found = new java.util.ArrayList<>();
        for (net.minecraft.world.entity.Entity e : level.getEntities().getAll()) {
            if (e instanceof net.minecraft.world.entity.item.ItemEntity item && item.isAlive()) {
                found.add(item);
            } else if (e instanceof net.minecraft.world.entity.ExperienceOrb orb && orb.isAlive()) {
                // 经验球走的不是 popResource（Block#popExperience → ExperienceOrb#award 直接加实体），
                // 所以必须单独收一遍 —— 否则挖矿的经验就永远留在隐藏维度里。
                found.add(orb);
            }
        }
        for (net.minecraft.world.entity.Entity e : found) {
            if (e instanceof net.minecraft.world.entity.ExperienceOrb orb) {
                relocateOrb(orb);
                continue;
            }
            net.minecraft.world.entity.item.ItemEntity item = (net.minecraft.world.entity.item.ItemEntity) e;
            ItemStack stack = item.getItem().copy();
            if (stack.isEmpty()) {
                item.discard();
                continue;
            }
            // 带上原速度：投掷器/发射器吐出来的东西是"射出去"的，丢了速度就只会掉在原地
            if (relocateDrop(level, item.blockPosition(), stack, item.getDeltaMovement())) {
                item.discard(); // 已在世界侧生成，投影里的这份销毁
            } else if (nearestBodyFor(item.blockPosition()) < 0) {
                // **永久**无归属：隐藏维度里的垃圾（玩家永远看不到、拿不到），直接销毁 ——
                // 否则它会每 tick 被扫到一次，计数无限涨、还占着实体槽。
                item.discard();
                discardedDrops++;
            }
            // 有归属但这次没生成成功（目标区块没加载之类）：留在原地，下一 tick 重试
        }
    }

    public static boolean relocateDrop(Level projection, BlockPos pos, ItemStack stack) {
        return relocateDrop(projection, pos, stack, null);
    }

    /**
     * 把一个落在投影维度里的经验球搬到物理体所在的世界。
     *
     * <p>用 {@link net.minecraft.world.entity.Entity#teleportTo(net.minecraft.server.level.ServerLevel, double, double, double, java.util.Set, float, float)}
     * <b>整球搬走</b>，而不是"读值 → 销毁 → 用 {@code ExperienceOrb#award} 重发一份"：
     * 跨维度时 {@code teleportTo} 会走一遍 NBT 往返（{@code restoreFrom} → {@code load}），
     * 于是 {@code Value} 和 {@code Count} 都原样带走。重发的写法会踩到合并过的球 ——
     * 多个同值球合并后 {@code getValue()} 只返回单份值、{@code count} 才是份数，
     * 按 value 重发等于把多出来的经验直接抹掉。</p>
     *
     * <p>顺序很重要：<b>先确认目标区块已加载再搬</b>。反过来的话，区块没加载时球会凭空消失。</p>
     */
    private static void relocateOrb(net.minecraft.world.entity.ExperienceOrb orb) {
        long bodyId = nearestBodyFor(orb.blockPosition());
        if (bodyId < 0) {
            // 永久无归属：隐藏维度里的垃圾（玩家永远看不到、拿不到），直接销毁 ——
            // 否则它会每 tick 被扫到一次，计数无限涨。
            orb.discard();
            discardedDrops++;
            return;
        }
        int slot = slotOf(bodyId);
        ServerLevel target = PhysicsBodyTracker.levelOf(bodyId);
        if (slot < 0 || target == null) {
            return; // 体正在销毁/换维度：下一 tick 再试
        }
        BlockPos start = startOf(slot);
        double[] world = PhysicsBodyTracker.localToWorld(bodyId,
                orb.getBlockX() - start.getX(), orb.getBlockY() - start.getY(), orb.getBlockZ() - start.getZ());
        if (world == null) {
            return;
        }
        BlockPos at = BlockPos.containing(world[0], world[1], world[2]);
        if (!target.isLoaded(at)) {
            return; // 目标区块没加载：留着，下一 tick 再试
        }
        int value = orb.getValue();
        orb.teleportTo(target, world[0], world[1], world[2],
                java.util.Set.of(), orb.getYRot(), orb.getXRot());
        relocatedOrbs++;
        Polymech.LOGGER.debug("[PolyMech] 经验球搬运：投影 {} → 世界 {} 值 {}（体 {}，槽位 {}）",
                orb.blockPosition().toShortString(), at.toShortString(), value, bodyId, slot);
    }

    /**
     * @param motion 原掉落物的速度；null 表示"凭空喷一个"（用默认随机初速）。
     *               扫实体时会带上原速度，投掷器/发射器吐出来的东西才会照样飞出去。
     */
    public static boolean relocateDrop(Level projection, BlockPos pos, ItemStack stack,
                                       net.minecraft.world.phys.Vec3 motion) {
        if (!isProjectionLevel(projection) || stack.isEmpty()) {
            return false;
        }
        // 用"最近归属"而不是正查：物品可能已被射出地皮边界
        long bodyId = nearestBodyFor(pos);
        if (bodyId < 0) {
            return false;
        }
        int slot = slotOf(bodyId);
        if (slot < 0) {
            return false;
        }
        BlockPos start = startOf(slot);
        double[] world = PhysicsBodyTracker.localToWorld(bodyId,
                pos.getX() - start.getX(), pos.getY() - start.getY(), pos.getZ() - start.getZ());
        ServerLevel target = PhysicsBodyTracker.levelOf(bodyId);
        if (world == null || target == null) {
            return false;
        }
        BlockPos at = BlockPos.containing(world[0], world[1], world[2]);
        // 显式生成（不用 Block#popResource）：它的返回值是 void，**加没加成看不出来**。
        // 这里要的就是那个 boolean —— 目标区块没加载/生成被拒时能立刻在日志里看见。
        net.minecraft.world.entity.item.ItemEntity moved =
                new net.minecraft.world.entity.item.ItemEntity(target,
                        at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, stack);
        if (motion != null && motion.lengthSqr() >= 1.0e-6) {
            moved.setDeltaMovement(motion);
        }
        moved.setDefaultPickUpDelay();
        boolean added = target.addFreshEntity(moved);
        // DEBUG 级：位置/物品/成败都在里面，排查"东西去哪了"时开 debug 日志即可看到
        //（降级理由：正常游玩时一次掉落一行太吵；run/logs/debug.log 里仍可查）
        Polymech.LOGGER.debug("[PolyMech] 掉落物搬运：投影 {} → 世界 {} ×{} {} | 生成={} | 区块已加载={}（体 {}，槽位 {}）",
                pos.toShortString(), at.toShortString(), stack.getCount(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()),
                added, target.isLoaded(at), bodyId, slot);
        if (!added) {
            return false; // 没生成成功：不计数、不复位，留给调用方下一 tick 重试
        }
        relocatedDrops++;
        return true;
    }

    // ==================== 坐标映射（交互与容器校验用） ====================

    /** 该 Level 是不是投影维度。 */
    public static boolean isProjectionLevel(Level other) {
        return level != null && other == level;
    }

    /** 地皮局部坐标 → 投影维度里的世界坐标。 */
    public static BlockPos toProjection(int slot, int dx, int dy, int dz) {
        return startOf(slot).offset(dx, dy, dz);
    }

    /** 反向：投影维度里的世界坐标 → 槽位号。 */
    public static int slotOfProjectionPos(BlockPos pos) {
        int half = SLOT_SIZE / 2;
        return Math.floorDiv(pos.getX() + half, SLOT_SPACING);
    }

    /** 反向：槽位 → 物理体 id；没有返回 -1。 */
    public static long bodyOfSlot(int slot) {
        for (Map.Entry<Long, Integer> e : SLOT_OF_BODY.entrySet()) {
            if (e.getValue() == slot) {
                return e.getKey();
            }
        }
        return -1L;
    }

    /**
     * 投影维度里的容器，对"离它所属物理体足够近"的玩家视为仍然有效。
     *
     * <p><b>为什么必须改这个判定</b>：原版 {@code Container#stillValidBlockEntity} 比的是
     * "玩家到打开的那个方块的距离"。而投影方块待在隐藏维度、玩家在太空维度 ——
     * 距离判定必然失败，表现就是<b>箱子打开又立刻关上</b>（GUI 一闪而过）。
     * 这里改成按物理体在<b>世界里的位置</b>判定，语义上正好等价于"玩家够得着那个箱子"。</p>
     */
    public static boolean isProjectionContainerValid(BlockEntity be, net.minecraft.world.entity.player.Player player) {
        if (be == null || !isProjectionLevel(be.getLevel())
                || !(player instanceof net.minecraft.server.level.ServerPlayer sp)) {
            return false;
        }
        long bodyId = bodyOfSlot(slotOfProjectionPos(be.getBlockPos()));
        // 用物理体的方块包围盒最近距离，避免大结构远端被判成超距
        return bodyId >= 0 && PhysicsBodyTracker.isNear(bodyId, sp, 8.0);
    }
}
