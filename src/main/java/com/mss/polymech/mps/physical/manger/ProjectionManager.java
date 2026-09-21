package com.mss.polymech.mps.physical.manger;

import com.mss.polymech.Polymech;
import com.mss.polymech.mps.network.packet.SyncPhysicalBlockBreakProgress;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyBlockEntity;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyBlockUpdate;
import com.mss.polymech.mps.network.packet.SyncPhysicalBodyRemove;
import com.mss.polymech.mps.physical.helper.PhysicalRaycast;
import com.mss.polymech.mps.physical.physical_body.PhysicalBody;
import com.mss.polymech.mps.physical.physical_body.ServerPhysicalBody;
import com.mss.polymech.mps.physical.physical_world.PhysicalWorld;
import com.mss.polymech.mps.physical.physical_world.ServerPhysicalWorld;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockEvent.BreakEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 投影管理器 —— <b>与 {@code org.polaris2023.mps.physical.manger.ProjectionManager}
 * 同形</b>的自有实现（clean-room；见 {@code docs/mps-clone-plan.md} §11/§20）。
 *
 * <h2>它解决的根本问题</h2>
 * 物理体上的方块<b>不能存在于真实世界</b>（否则双重碰撞、原版交互乱套），
 * 但<b>又必须让原版逻辑照常跑</b>：箱子要能开、红石要能亮、破坏要走原版进度与掉落、
 * 放置要走原版 {@code canSurvive/canPlace}。
 * 同时满足只有一条路：把这些方块<b>镜像进一个专用维度</b>
 * （{@value #PROJECTION_WORLD}），在那边它们就是<b>真的方块 + 真的方块实体</b> ——
 * 于是所有原版代码路径<b>一行都不用改</b>。
 *
 * <h2>四套职责（施工图见 §11，这里按同一顺序排布）</h2>
 * <ol>
 *   <li><b>地皮槽位</b>：每来一个物理体分一块 128³ 的"地皮"，
 *       槽位沿 X 轴排开，<b>间距 192 = 128 + 64 的间隔</b>（防止相邻地皮的世界 AABB 贴在一起）。
 *       {@link Projection#getStart()} 就是"{@code slot*192 - 64}"。
 *       槽位的 {@code nextSlot} 落在 {@link ProjectionSlotData} 存档里 ——
 *       <b>不复用已释放的槽位号</b>，因为客户端可能还留着旧体的镜像。</li>
 *   <li><b>方块搬运</b>：{@link #copyBlock} 把真实世界的方块复制进地皮；
 *       {@link #readProjectionData} 反过来把地皮读进物理体的方块网格
 *       （体的网格才是渲染与碰撞用的那份）。</li>
 *   <li><b>破坏/放置</b>：{@link #removeBlock}（连同光照更新与邻块通知）、
 *       {@link #destroyBlock}（走原版掉落与工具损耗）。</li>
 *   <li><b>交互转发</b>：{@link #handleInteraction} 三态（攻击/停手/使用），
 *       以及"地皮改动 → 攒脏 → {@link #tick} 批量回写客户端"的管线。</li>
 * </ol>
 *
 * <h2>三个关键设计（别改）</h2>
 * <ul>
 *   <li><b>脏块攒批</b>（{@link #dirtyChunks} + {@link #tick()}）：
 *       刷一个 64³ 子块很贵，而红石/活塞一 tick 可能改上百格。
 *       所以只记"哪些子块脏了"，每 tick 统一 flushing；
 *       且 flush 后还要判断"这块地皮是不是全空了"→ 空则整体删除
 *       （这是"拆光一艘船它会自己消失"的实现）。</li>
 *   <li><b>{@link #packDirtyKey} 的位布局</b>：{@code slot<<8 | chunkX<<2 | chunkY<<1 | chunkZ}。
 *       子块坐标只可能是 0/1（2×2×2），所以各占 1 位；槽位占高位。
 *       这不是省内存，而是让 {@link #tick} 能<b>无分支地</b>拆回四元组。</li>
 *   <li><b>交互用服务端自己的射线</b>（{@link #handleInteraction} 里重新 cast）：
 *       客户端报的命中点可伪造，而"玩家看向哪"服务端本来就有。</li>
 * </ul>
 *
 * <p><b>与项目的既有实现的关系</b>：本项目另有一套
 * {@code com.mss.polymech.physics.ProjectionManager}（同为 647 行，地皮几何逐位相同）。
 * 按用户拍定的方案 A，这里<b>照抄</b> MPS 这一套；S6 必须二选一，
 * 两套不能同时跑（见 §20）。</p>
 */
public final class ProjectionManager {

    /** 投影维度 id —— 与 {@code data/poly_mech/dimension/projection_world.json} 一致。 */
    public static final ResourceLocation PROJECTION_WORLD =
            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "projection_world");
    /** 客户端交互动作：攻击（持续）。 */
    public static final byte INTERACTION_ATTACK = 0;
    /** 客户端交互动作：停手 —— 原版没有对应包，靠它清裂纹与进度。 */
    public static final byte INTERACTION_ATTACK_STOP = 1;
    /** 客户端交互动作：使用。 */
    public static final byte INTERACTION_USE = 2;
    /** 创造模式破坏节流（tick）—— 与原版 {@code destroyDelay} 对齐，防连点瞬拆。 */
    private static final int CREATIVE_DESTROY_DELAY = 5;

    /** 投影维度；未 {@link #init} 前为 null。 */
    public static ServerLevel projectionLevel;
    private static int nextSlot;
    private static final Map<PhysicalBody, Projection> projectionMap = new HashMap<>();
    /** 待回写客户端的 64³ 子块（键见 {@link #packDirtyKey}）。 */
    private static final Set<Long> dirtyChunks = new HashSet<>();
    /** 每个玩家当前的挖掘进度（生存模式按硬度挖）。 */
    private static final Map<UUID, BreakProgress> breakProgress = new HashMap<>();
    /** 每个玩家上一次攻击的 tick（同一 tick 只算一次）。 */
    private static final Map<UUID, Long> attackThrottle = new HashMap<>();

    private ProjectionManager() {
    }

    /** 取投影维度并恢复槽位分配游标；维度缺失时<b>直接抛</b>（而不是降级跑）。 */
    public static void init(MinecraftServer minecraftServer) {
        projectionLevel = minecraftServer.getLevel(ResourceKey.create(Registries.DIMENSION, PROJECTION_WORLD));
        if (projectionLevel == null) {
            throw new IllegalStateException("Missing dimension " + PROJECTION_WORLD);
        }
        nextSlot = ProjectionSlotData.get(projectionLevel).getNextSlot();
    }

    /** 给一个体分配新槽位（自增，不复用）。 */
    public static Projection createNewProjection(ServerPhysicalBody serverPhysicalBody) {
        Projection projection = new Projection(nextSlot);
        nextSlot++;
        if (projectionLevel != null) {
            ProjectionSlotData.get(projectionLevel).setNextSlot(nextSlot);
        }
        projectionMap.put(serverPhysicalBody, projection);
        setProjectionForced(projection, true);
        return projection;
    }

    /** 读档路径：沿用存档里的槽位号，并把游标推到它之后。 */
    public static Projection createNewProjection(ServerPhysicalBody serverPhysicalBody, int slot) {
        Projection projection = new Projection(slot);
        if (slot >= nextSlot) {
            nextSlot = slot + 1;
            if (projectionLevel != null) {
                ProjectionSlotData.get(projectionLevel).setNextSlot(nextSlot);
            }
        }
        projectionMap.put(serverPhysicalBody, projection);
        setProjectionForced(projection, true);
        return projection;
    }

    /**
     * 强制加载/卸载地皮所在的 8×8 个区块。
     *
     * <p>必须强制加载：投影维度没有玩家，区块不会被自然加载；
     * 而船上的方块实体（熔炉、机器）要在<b>无人处也继续工作</b>。
     * 8×8 是因为 128³ 地皮 = 8×8 个 16×16 的区块柱。</p>
     */
    private static void setProjectionForced(Projection projection, boolean forced) {
        if (projectionLevel == null) {
            return;
        }
        BlockPos start = projection.getStart();
        int minChunkX = start.getX() >> 4;
        int minChunkZ = start.getZ() >> 4;
        int chunkSpan = 8;
        for (int cx = minChunkX; cx < minChunkX + chunkSpan; cx++) {
            for (int cz = minChunkZ; cz < minChunkZ + chunkSpan; cz++) {
                projectionLevel.setChunkForced(cx, cz, forced);
            }
        }
    }

    /**
     * 世界坐标 → 槽位号；不属于任何地皮返回 -1。
     *
     * <p>地皮沿 X 排开（{@code slot*192 - 64}），Y ∈ [−64, 64]，
     * Z ∈ [−64, 64]。判定是"落在哪一段 + 是否真的在那一段的 128 宽以内"
     * —— 后半个条件不能省：段间有 64 的空隙。</p>
     */
    public static int getSlot(BlockPos blockPos) {
        int width = 128;
        int spacing = width + 64;
        int half = width / 2;
        if (blockPos.getY() < -64 || blockPos.getY() > -64 + width
                || blockPos.getZ() < -half || blockPos.getZ() > half) {
            return -1;
        }
        int slot = Math.floorDiv(blockPos.getX() + half, spacing);
        if (slot < 0) {
            return -1;
        }
        return blockPos.getX() >= slot * spacing - half && blockPos.getX() <= slot * spacing + half ? slot : -1;
    }

    public static PhysicalBody getPhysicalBody(int slot) {
        for (Map.Entry<PhysicalBody, Projection> entry : projectionMap.entrySet()) {
            if (entry.getValue().getSlot() == slot) {
                return entry.getKey();
            }
        }
        return null;
    }

    public static PhysicalBody getPhysicalBody(BlockPos blockPos) {
        int slot = getSlot(blockPos);
        return slot < 0 ? null : getPhysicalBody(slot);
    }

    public static Projection getProjection(int slot) {
        for (Projection projection : projectionMap.values()) {
            if (projection.getSlot() == slot) {
                return projection;
            }
        }
        return null;
    }

    /**
     * 把真实世界的方阵复制进投影地皮。
     *
     * <h2>为什么这么长（照 space 0.1.3）</h2>
     * 它要同时处理四件事，缺一件都会出问题：
     * <ol>
     *   <li><b>裁剪到地皮范围内</b>：源区域可能比地皮大，超出部分直接丢弃
     *       （{@code min/max} 夹取 + Y 再夹到两边的建筑高度限制）。</li>
     *   <li><b>先清目标区的方块实体</b>：地形覆盖过去后，
     *       旧的方块实体若不先移除，会变成"没有对应方块的幽灵方块实体"。</li>
     *   <li><b>整段对齐时走 section 拷贝</b>（{@code fullSection} 分支）：
     *       偏移是 16 的整数倍且整段都在范围内时，直接
     *       {@code new LevelChunkSection(states.copy(), biomes)} 换掉整段 ——
     *       比逐格设快几个数量级。这是本项目最热的一次性操作。</li>
     *   <li><b>方块实体单独搬</b>：状态拷贝不会带走方块实体，
     *       所以最后要把源区块的 BlockEntity 存成 NBT、改坐标、在地皮上重建。</li>
     * </ol>
     * 源区块用 {@link #sourceChunk} 走缓存 —— 一次复制会反复取同一批区块。
     */
    public static void copyBlock(ServerLevel level, BlockPos start, BlockPos end, BlockPos goal,
                                Projection projection) {
        if (level.dimension().location().equals(PROJECTION_WORLD)) {
            return;
        }
        BlockPos sourceMin = min(start, end);
        BlockPos sourceMax = max(start, end);
        BlockPos projectionMin = min(projection.getStart(), projection.getEnd());
        BlockPos projectionMax = max(projection.getStart(), projection.getEnd());
        int offsetX = goal.getX() - sourceMin.getX();
        int offsetY = goal.getY() - sourceMin.getY();
        int offsetZ = goal.getZ() - sourceMin.getZ();
        int minX = Math.max(sourceMin.getX(), projectionMin.getX() - offsetX);
        int minY = Math.max(sourceMin.getY(), projectionMin.getY() - offsetY);
        int minZ = Math.max(sourceMin.getZ(), projectionMin.getZ() - offsetZ);
        int maxX = Math.min(sourceMax.getX(), projectionMax.getX() - offsetX);
        int maxY = Math.min(sourceMax.getY(), projectionMax.getY() - offsetY);
        int maxZ = Math.min(sourceMax.getZ(), projectionMax.getZ() - offsetZ);
        minY = Math.max(minY, Math.max(level.getMinBuildHeight(), projectionLevel.getMinBuildHeight()));
        maxY = Math.min(maxY, Math.min(level.getMaxBuildHeight(), projectionLevel.getMaxBuildHeight()) - 1);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return;
        }

        int destMinX = minX + offsetX;
        int destMinY = minY + offsetY;
        int destMinZ = minZ + offsetZ;
        int destMaxX = maxX + offsetX;
        int destMaxY = maxY + offsetY;
        int destMaxZ = maxZ + offsetZ;
        Map<Long, LevelChunk> sourceChunkCache = new HashMap<>();

        // ① 先清目标区的方块实体（见方法注释）
        for (int chunkX = destMinX >> 4; chunkX <= destMaxX >> 4; chunkX++) {
            for (int chunkZ = destMinZ >> 4; chunkZ <= destMaxZ >> 4; chunkZ++) {
                LevelChunk destChunk = projectionLevel.getChunk(chunkX, chunkZ);
                for (BlockPos bePos : new ArrayList<>(destChunk.getBlockEntities().keySet())) {
                    if (bePos.getX() >= destMinX && bePos.getX() <= destMaxX
                            && bePos.getY() >= destMinY && bePos.getY() <= destMaxY
                            && bePos.getZ() >= destMinZ && bePos.getZ() <= destMaxZ) {
                        destChunk.removeBlockEntity(bePos);
                    }
                }
            }
        }

        boolean aligned = Math.floorMod(offsetX, 16) == 0 && Math.floorMod(offsetY, 16) == 0
                && Math.floorMod(offsetZ, 16) == 0;

        // ② 搬方块状态（整段对齐走 section 拷贝）
        for (int chunkX = destMinX >> 4; chunkX <= destMaxX >> 4; chunkX++) {
            for (int chunkZ = destMinZ >> 4; chunkZ <= destMaxZ >> 4; chunkZ++) {
                LevelChunk destChunk = projectionLevel.getChunk(chunkX, chunkZ);
                boolean changed = false;

                for (int sectionY = destMinY >> 4; sectionY <= destMaxY >> 4; sectionY++) {
                    int sectionIndex = destChunk.getSectionIndex(sectionY << 4);
                    LevelChunkSection destSection = destChunk.getSections()[sectionIndex];
                    int dMinX = Math.max(destMinX, chunkX << 4);
                    int dMinY = Math.max(destMinY, sectionY << 4);
                    int dMinZ = Math.max(destMinZ, chunkZ << 4);
                    int dMaxX = Math.min(destMaxX, (chunkX << 4) + 15);
                    int dMaxY = Math.min(destMaxY, (sectionY << 4) + 15);
                    int dMaxZ = Math.min(destMaxZ, (chunkZ << 4) + 15);
                    boolean fullSection = aligned
                            && dMinX == chunkX << 4 && dMinY == sectionY << 4 && dMinZ == chunkZ << 4
                            && dMaxX == (chunkX << 4) + 15 && dMaxY == (sectionY << 4) + 15
                            && dMaxZ == (chunkZ << 4) + 15;

                    if (fullSection) {
                        LevelChunk sourceChunk = sourceChunk(level, sourceChunkCache,
                                dMinX - offsetX >> 4, dMinZ - offsetZ >> 4);
                        LevelChunkSection sourceSection = sourceChunk.getSections()[
                                sourceChunk.getSectionIndex(dMinY - offsetY)];
                        if (sourceSection == null) {
                            if (destSection != null) {
                                // 源段不存在（超出世界）→ 清空目标段，但保留生物群系
                                destChunk.getSections()[sectionIndex] = new LevelChunkSection(
                                        destSection.getStates().recreate(), destSection.getBiomes());
                                changed = true;
                            }
                        } else {
                            destChunk.getSections()[sectionIndex] = new LevelChunkSection(
                                    sourceSection.getStates().copy(),
                                    destSection != null ? destSection.getBiomes() : sourceSection.getBiomes());
                            changed = true;
                        }
                    } else if (destSection != null) {
                        for (int dy = dMinY; dy <= dMaxY; dy++) {
                            for (int dz = dMinZ; dz <= dMaxZ; dz++) {
                                for (int dx = dMinX; dx <= dMaxX; dx++) {
                                    LevelChunk sourceChunk = sourceChunk(level, sourceChunkCache,
                                            dx - offsetX >> 4, dz - offsetZ >> 4);
                                    LevelChunkSection sourceSection = sourceChunk.getSections()[
                                            sourceChunk.getSectionIndex(dy - offsetY)];
                                    BlockState state = sourceSection == null
                                            ? Blocks.AIR.defaultBlockState()
                                            : sourceSection.getBlockState(dx - offsetX & 15,
                                            dy - offsetY & 15, dz - offsetZ & 15);
                                    destSection.setBlockState(dx & 15, dy & 15, dz & 15, state, false);
                                }
                            }
                        }
                        changed = true;
                    }
                }

                if (changed) {
                    destChunk.setUnsaved(true);
                }
            }
        }

        // ③ 搬方块实体（状态拷贝不带走它们，见方法注释）
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                LevelChunk sourceChunk = sourceChunk(level, sourceChunkCache, chunkX, chunkZ);
                for (Map.Entry<BlockPos, BlockEntity> entry : sourceChunk.getBlockEntities().entrySet()) {
                    BlockPos sourcePos = entry.getKey();
                    if (sourcePos.getX() >= minX && sourcePos.getX() <= maxX
                            && sourcePos.getY() >= minY && sourcePos.getY() <= maxY
                            && sourcePos.getZ() >= minZ && sourcePos.getZ() <= maxZ) {
                        BlockPos destPos = sourcePos.offset(offsetX, offsetY, offsetZ);
                        CompoundTag tag = entry.getValue().saveWithFullMetadata(level.registryAccess());
                        tag.putInt("x", destPos.getX());
                        tag.putInt("y", destPos.getY());
                        tag.putInt("z", destPos.getZ());
                        BlockState destState = projectionLevel.getBlockState(destPos);
                        BlockEntity destBlockEntity = BlockEntity.loadStatic(destPos, destState, tag,
                                projectionLevel.registryAccess());
                        if (destBlockEntity != null) {
                            LevelChunk destChunk = projectionLevel.getChunk(destPos.getX() >> 4,
                                    destPos.getZ() >> 4);
                            destChunk.addAndRegisterBlockEntity(destBlockEntity);
                            destChunk.setUnsaved(true);
                        }
                    }
                }
            }
        }
    }

    /**
     * 把一段局部坐标的方块写进地皮（创建、放置时用）。
     *
     * <p>局部坐标以<b>地皮中心为原点</b>（{@code ±64} 以内），
     * 所以要 {@code offset(half, half, half)} 换到地皮坐标。
     * 越界直接抛 —— 这是编程错误（体只允许 128³），不是运行时状况。</p>
     *
     * <p>实现上<b>整块重写</b>：8×8 个 64³ 子块全部逐格设成
     * "范围内给目标方块、范围外给空气"。不这么做的话，
     * 上一次放置留下的方块会残留在这次的范围之外。</p>
     */
    public static void writeProjectionBlocks(Projection projection, BlockPos localStart, BlockPos localEnd,
                                             BlockState blockState) {
        if (projectionLevel == null) {
            throw new IllegalStateException("Projection world is not initialized");
        }
        int half = 64;
        BlockPos localMin = min(localStart, localEnd);
        BlockPos localMax = max(localStart, localEnd);
        if (localMin.getX() < -half || localMin.getY() < -half || localMin.getZ() < -half
                || localMax.getX() >= half || localMax.getY() >= half || localMax.getZ() >= half) {
            throw new IllegalArgumentException("Projection block range exceeds physical body bounds");
        }
        BlockPos projectionStart = projection.getStart();
        BlockPos projectionOrigin = projectionStart.offset(half, half, half);
        BlockPos fillMin = projectionOrigin.offset(localMin);
        BlockPos fillMax = projectionOrigin.offset(localMax);
        BlockState air = Blocks.AIR.defaultBlockState();
        int chunkSpan = 8;

        for (int chunkOffsetX = 0; chunkOffsetX < chunkSpan; chunkOffsetX++) {
            int chunkX = (projectionStart.getX() >> 4) + chunkOffsetX;
            for (int chunkOffsetZ = 0; chunkOffsetZ < chunkSpan; chunkOffsetZ++) {
                int chunkZ = (projectionStart.getZ() >> 4) + chunkOffsetZ;
                LevelChunk chunk = projectionLevel.getChunk(chunkX, chunkZ);

                // 清目标 Y 段的方块实体（重建范围内的一切方块实体由后续读取重新放）
                for (BlockPos blockEntityPos : new ArrayList<>(chunk.getBlockEntities().keySet())) {
                    if (blockEntityPos.getY() >= projectionStart.getY()
                            && blockEntityPos.getY() < projectionStart.getY() + 128) {
                        chunk.removeBlockEntity(blockEntityPos);
                    }
                }

                for (int sectionOffsetY = 0; sectionOffsetY < chunkSpan; sectionOffsetY++) {
                    int baseX = chunkX << 4;
                    int baseY = projectionStart.getY() + sectionOffsetY * 16;
                    int baseZ = chunkZ << 4;
                    int sectionIndex = chunk.getSectionIndex(baseY);
                    LevelChunkSection section = chunk.getSections()[sectionIndex];
                    if (section == null) {
                        throw new IllegalStateException("Missing projection chunk section");
                    }

                    for (int x = 0; x < 16; x++) {
                        int worldX = baseX + x;
                        for (int y = 0; y < 16; y++) {
                            int worldY = baseY + y;
                            for (int z = 0; z < 16; z++) {
                                int worldZ = baseZ + z;
                                section.setBlockState(x, y, z,
                                        worldX >= fillMin.getX() && worldX <= fillMax.getX()
                                                && worldY >= fillMin.getY() && worldY <= fillMax.getY()
                                                && worldZ >= fillMin.getZ() && worldZ <= fillMax.getZ()
                                                ? blockState : air,
                                        false);
                            }
                        }
                    }
                }
                chunk.setUnsaved(true);
            }
        }
    }

    /**
     * 把真实世界一段区域挖成空气 —— <b>连同光照更新与邻块通知</b>。
     *
     * <h2>四个必须做的收尾（照 space 0.1.3）</h2>
     * <ol>
     *   <li><b>{@code lightEngine.checkBlock}</b>：不重算光照，挖出来的洞会是黑的；</li>
     *   <li><b>{@code level.updateNeighborsAt}</b>：不通知邻块，
     *       沙子不会塌、红石不会更新；</li>
     *   <li><b>整段分支用 {@code states.recreate()}</b> 换掉整段（快），
     *       但换之前要逐格把<b>原本非空气</b>的坐标记下来补光照与通知 ——
     *       否则就漏了第 1、2 步；</li>
     *   <li><b>给区块内的玩家重发整块包</b>：只改服务端内存，客户端看不到变化。</li>
     * </ol>
     *
     * <p>另外把脏块标给 {@code PhysicalChunkManager}，
     * 让"地形被挖掉"也走一遍物理侧的回写管线。</p>
     */
    public static void removeBlock(ServerLevel level, BlockPos start, BlockPos end) {
        BlockPos sourceMin = min(start, end);
        BlockPos sourceMax = max(start, end);
        int minX = sourceMin.getX();
        int minY = Math.max(sourceMin.getY(), level.getMinBuildHeight());
        int minZ = sourceMin.getZ();
        int maxX = sourceMax.getX();
        int maxY = Math.min(sourceMax.getY(), level.getMaxBuildHeight() - 1);
        int maxZ = sourceMax.getZ();
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return;
        }

        BlockState air = Blocks.AIR.defaultBlockState();
        LevelLightEngine lightEngine = level.getLightEngine();
        Set<Long> removedPositions = new HashSet<>();
        Set<LevelChunk> changedChunks = new HashSet<>();

        // 清方块实体
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                boolean removedBlockEntity = false;
                for (BlockPos bePos : new ArrayList<>(chunk.getBlockEntities().keySet())) {
                    if (bePos.getX() >= minX && bePos.getX() <= maxX
                            && bePos.getY() >= minY && bePos.getY() <= maxY
                            && bePos.getZ() >= minZ && bePos.getZ() <= maxZ) {
                        chunk.removeBlockEntity(bePos);
                        removedBlockEntity = true;
                    }
                }
                if (removedBlockEntity) {
                    changedChunks.add(chunk);
                }
            }
        }

        // 挖方块 + 记下"原本非空气"的坐标（用于补光照与邻块通知）
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                boolean changed = false;

                for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
                    int sectionIndex = chunk.getSectionIndex(sectionY << 4);
                    LevelChunkSection oldSection = chunk.getSections()[sectionIndex];
                    if (oldSection == null || oldSection.hasOnlyAir()) {
                        continue;
                    }
                    int dMinX = Math.max(minX, chunkX << 4);
                    int dMinY = Math.max(minY, sectionY << 4);
                    int dMinZ = Math.max(minZ, chunkZ << 4);
                    int dMaxX = Math.min(maxX, (chunkX << 4) + 15);
                    int dMaxY = Math.min(maxY, (sectionY << 4) + 15);
                    int dMaxZ = Math.min(maxZ, (chunkZ << 4) + 15);
                    boolean fullSection = dMinX == chunkX << 4 && dMinY == sectionY << 4 && dMinZ == chunkZ << 4
                            && dMaxX == (chunkX << 4) + 15 && dMaxY == (sectionY << 4) + 15
                            && dMaxZ == (chunkZ << 4) + 15;

                    if (fullSection) {
                        chunk.getSections()[sectionIndex] = new LevelChunkSection(
                                oldSection.getStates().recreate(), oldSection.getBiomes());
                        changed = true;
                        // 整段换掉不会自己补光照/通知，必须逐格扫描补上
                        for (int dy = dMinY; dy <= dMaxY; dy++) {
                            for (int dz = dMinZ; dz <= dMaxZ; dz++) {
                                for (int dx = dMinX; dx <= dMaxX; dx++) {
                                    if (!oldSection.getBlockState(dx & 15, dy & 15, dz & 15).isAir()) {
                                        lightEngine.checkBlock(new BlockPos(dx, dy, dz));
                                        removedPositions.add(BlockPos.asLong(dx, dy, dz));
                                    }
                                }
                            }
                        }
                    } else {
                        for (int dy = dMinY; dy <= dMaxY; dy++) {
                            for (int dz = dMinZ; dz <= dMaxZ; dz++) {
                                for (int dx = dMinX; dx <= dMaxX; dx++) {
                                    BlockState previous = oldSection.setBlockState(dx & 15, dy & 15, dz & 15,
                                            air, false);
                                    if (!previous.isAir()) {
                                        lightEngine.checkBlock(new BlockPos(dx, dy, dz));
                                        removedPositions.add(BlockPos.asLong(dx, dy, dz));
                                    }
                                }
                            }
                        }
                        changed = true;
                    }
                }

                if (changed) {
                    chunk.setUnsaved(true);
                    changedChunks.add(chunk);
                    ServerPhysicalWorld physicalWorld = ServerPhysicalWorld.getPhysicalWorld(level);
                    if (physicalWorld != null) {
                        physicalWorld.getChunkManager().markDirty(chunk.getPos().getWorldPosition());
                    }
                }
            }
        }

        for (long packed : removedPositions) {
            level.updateNeighborsAt(BlockPos.of(packed), air.getBlock());
        }

        // 只改服务端内存客户端看不到变化，必须重发整块包
        for (LevelChunk chunk : changedChunks) {
            ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), null, null);
            for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) {
                player.connection.send(packet);
            }
        }
    }

    private static LevelChunk sourceChunk(ServerLevel level, Map<Long, LevelChunk> cache, int chunkX, int chunkZ) {
        return cache.computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> level.getChunk(chunkX, chunkZ));
    }

    /**
     * 脏块键：{@code slot<<8 | chunkX<<2 | chunkY<<1 | chunkZ}。
     * 子块坐标只可能是 0/1，各占 1 位（见类注释）。
     */
    private static long packDirtyKey(int slot, int chunkX, int chunkY, int chunkZ) {
        return (long) slot << 8 | (long) (chunkX << 2) | (long) (chunkY << 1) | (long) chunkZ;
    }

    /**
     * 某个地皮位置被改动 → 标脏所属 64³ 子块。
     *
     * <p>除了自己那一格，还要检查<b>六个邻居</b>：改动会跨子块边界影响相邻子块
     * （比如放在边界上的红石元件连到隔壁子块的机器）。
     * 邻居有方块实体时才标 —— 没有方块实体的子块不需要重发方块实体包。</p>
     */
    public static void onBlockUpload(BlockPos pos) {
        int slot = getSlot(pos);
        if (slot < 0 || getPhysicalBody(slot) == null) {
            return;
        }
        Projection projection = getProjection(slot);
        if (projection == null) {
            return;
        }
        BlockPos start = projection.getStart();
        int offsetX = pos.getX() - start.getX();
        int offsetY = pos.getY() - start.getY();
        int offsetZ = pos.getZ() - start.getZ();
        if (offsetX < 0 || offsetX >= 128 || offsetY < 0 || offsetY >= 128 || offsetZ < 0 || offsetZ >= 128) {
            return;
        }
        int chunkX = offsetX / 64;
        int chunkY = offsetY / 64;
        int chunkZ = offsetZ / 64;
        dirtyChunks.add(packDirtyKey(slot, chunkX, chunkY, chunkZ));

        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            int nX = neighbor.getX() - start.getX();
            int nY = neighbor.getY() - start.getY();
            int nZ = neighbor.getZ() - start.getZ();
            if (nX >= 0 && nX < 128 && nY >= 0 && nY < 128 && nZ >= 0 && nZ < 128) {
                int nChunkX = nX / 64;
                int nChunkY = nY / 64;
                int nChunkZ = nZ / 64;
                if ((nChunkX != chunkX || nChunkY != chunkY || nChunkZ != chunkZ)
                        && projectionLevel != null
                        && projectionLevel.getBlockEntity(neighbor) != null) {
                    dirtyChunks.add(packDirtyKey(slot, nChunkX, nChunkY, nChunkZ));
                }
            }
        }
    }

    /**
     * 每 tick 把攒下的脏子块批量回写客户端（由 {@code PhysicsServerTick} 驱动）。
     *
     * <p>flush 后还要检查"这块地皮是不是全空了"：船被拆光时应当<b>自己消失</b>
     * （连同刚体与地皮），而不是留一个空的物理体继续参与物理。
     * 判定放在 flush 之后、且只对<b>本轮真的被碰过的</b>体做
     * —— 每 tick 遍历所有体去扫 8×8×8 个段太贵。</p>
     */
    public static void tick() {
        if (dirtyChunks.isEmpty() || projectionLevel == null) {
            return;
        }
        Set<Long> flushing = new HashSet<>(dirtyChunks);
        dirtyChunks.clear();
        Set<PhysicalBody> touched = new HashSet<>();

        for (long key : flushing) {
            int slot = (int) (key >> 8);
            int chunkX = (int) (key >> 2 & 1L);
            int chunkY = (int) (key >> 1 & 1L);
            int chunkZ = (int) (key & 1L);
            PhysicalBody physicalBody = getPhysicalBody(slot);
            if (physicalBody == null) {
                continue;
            }
            Projection projection = getProjection(slot);
            if (projection == null) {
                continue;
            }
            uploadChunk(physicalBody, projection, chunkX, chunkY, chunkZ);
            touched.add(physicalBody);
        }

        for (PhysicalBody physicalBody : touched) {
            Projection projection = projectionMap.get(physicalBody);
            if (projection != null && isProjectionAllAir(projection)) {
                removeEmptyPhysicalBody(physicalBody);
            }
        }
    }

    /** 地皮的 128³ 是否已全是空气（逐段查 {@code hasOnlyAir}，不逐格）。 */
    private static boolean isProjectionAllAir(Projection projection) {
        BlockPos start = projection.getStart();
        int minY = start.getY();
        int maxY = minY + 128;
        int chunkSpan = 8;
        int minChunkX = start.getX() >> 4;
        int minChunkZ = start.getZ() >> 4;

        for (int cx = minChunkX; cx < minChunkX + chunkSpan; cx++) {
            for (int cz = minChunkZ; cz < minChunkZ + chunkSpan; cz++) {
                LevelChunk chunk = projectionLevel.getChunk(cx, cz);
                for (int y = minY; y < maxY; y += 16) {
                    int sectionIndex = chunk.getSectionIndex(y);
                    if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) {
                        LevelChunkSection section = chunk.getSections()[sectionIndex];
                        if (section != null && !section.hasOnlyAir()) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static void removeEmptyPhysicalBody(PhysicalBody physicalBody) {
        removePhysicalBody(physicalBody, false);
    }

    /** 玩家/逻辑主动删除物理体（连地皮一起清）。 */
    public static boolean removePhysicalBody(PhysicalBody physicalBody) {
        return removePhysicalBody(physicalBody, true);
    }

    /**
     * 删体的统一路径。
     *
     * <p>{@code clearProjection} 区分两种来源：
     * 主动删除要清地皮（否则方块留在投影维度里变成垃圾）；
     * "地皮已经全空"触发的删除<b>不必再清</b>（本来就是空的，清一遍纯浪费）。</p>
     *
     * <p>顺序：先从物理世界摘掉（成功才继续）→ 通知客户端删镜像 →
     * 摘掉投影映射与脏块记录 → （可选）清地皮 → 解除区块强制加载。
     * 脏块记录必须一起清：不清的话 {@link #tick} 还会为已经不存在的体去 upload。</p>
     */
    private static boolean removePhysicalBody(PhysicalBody physicalBody, boolean clearProjection) {
        PhysicalWorld physicalWorld = physicalBody.getPhysicalWorld();
        if (physicalWorld == null || !physicalWorld.removePhysicalBody(physicalBody)) {
            return false;
        }
        ServerLevel targetLevel = levelOf(physicalBody.getLevel());
        if (targetLevel != null) {
            PacketDistributor.sendToPlayersInDimension(targetLevel, new SyncPhysicalBodyRemove(physicalBody));
        }

        Projection projection = projectionMap.remove(physicalBody);
        if (projection != null) {
            dirtyChunks.removeIf(key -> key >>> 8 == (long) projection.getSlot());
            if (clearProjection && projectionLevel != null) {
                BlockPos start = projection.getStart();
                int last = 127;
                removeBlock(projectionLevel, start, start.offset(last, last, last));
            }
            setProjectionForced(projection, false);
        }
        return true;
    }

    /** 把 2×2×2 个子块全部推一遍（创建/读档后用）。 */
    public static void uploadAllChunks(PhysicalBody physicalBody, Projection projection) {
        int chunkCount = 2;
        for (int x = 0; x < chunkCount; x++) {
            for (int y = 0; y < chunkCount; y++) {
                for (int z = 0; z < chunkCount; z++) {
                    uploadChunk(physicalBody, projection, x, y, z);
                }
            }
        }
    }

    /**
     * 推一个 64³ 子块给该维度的所有玩家：状态包 + 方块实体包。
     *
     * <p>{@code chunkOffset = 1} 把地皮子块坐标 0/1 映射成<b>体的局部子块</b> −1/0
     * ——即"体的中心在子块边界上"，所以 2×2×2 正好覆盖 ±64。
     * 两个包都发给"该维度全部玩家"而不是按距离筛：
     * 体可能在深空（没有区块/玩家列表），按距离筛会漏发。</p>
     */
    private static void uploadChunk(PhysicalBody physicalBody, Projection projection,
                                    int chunkX, int chunkY, int chunkZ) {
        List<SyncPhysicalBodyBlockEntity.Entry> blockEntityEntries =
                readChunk(physicalBody, projection, chunkX, chunkY, chunkZ);
        int chunkOffset = 1;
        int centeredX = chunkX - chunkOffset;
        int centeredY = chunkY - chunkOffset;
        int centeredZ = chunkZ - chunkOffset;
        ServerLevel targetLevel = levelOf(physicalBody.getLevel());
        if (targetLevel == null) {
            return;
        }
        SyncPhysicalBodyBlockUpdate packet = new SyncPhysicalBodyBlockUpdate(physicalBody.getUuid(),
                new Vector3i(centeredX, centeredY, centeredZ),
                physicalBody.getBlockStateChunkInt(centeredX, centeredY, centeredZ));
        SyncPhysicalBodyBlockEntity blockEntityPacket = new SyncPhysicalBodyBlockEntity(physicalBody.getUuid(),
                new Vector3i(centeredX, centeredY, centeredZ), blockEntityEntries);

        for (ServerPlayer player : targetLevel.players()) {
            PacketDistributor.sendToPlayer(player, packet);
            PacketDistributor.sendToPlayer(player, blockEntityPacket);
        }
    }

    /** 读档/创建后：把整个地皮读回体的方块网格（2×2×2 个子块）。 */
    public static void readProjectionData(ServerPhysicalBody physicalBody) {
        Projection projection = physicalBody.getProjection();
        int chunkCount = 2;
        for (int x = 0; x < chunkCount; x++) {
            for (int y = 0; y < chunkCount; y++) {
                for (int z = 0; z < chunkCount; z++) {
                    readChunk(physicalBody, projection, x, y, z);
                }
            }
        }
    }

    /**
     * 把一个 64³ 子块从地皮读进体的方块网格，并把方块实体搬进体、收集它们的 NBT。
     *
     * <p>{@code Block.getId} 存的是<b>状态 id</b>（全局调色板），
     * 体内部用 {@code int[][][]} 保存就是为了这个：紧凑且能直接进网络包。</p>
     *
     * <p>方块实体的局部坐标 = 地皮坐标 − 地皮中心（{@code origin}），
     * 与 {@code writeProjectionBlocks} 的 {@code offset(half,half,half)} 互为逆运算
     * —— 这两个偏移<b>必须一致</b>，否则方块实体会错位。</p>
     *
     * @return 该子块的方块实体列表（供网络包用）
     */
    private static List<SyncPhysicalBodyBlockEntity.Entry> readChunk(
            PhysicalBody physicalBody, Projection projection, int chunkX, int chunkY, int chunkZ) {
        BlockPos start = projection.getStart();
        int[][][] data = new int[64][64][64];
        int baseX = start.getX() + chunkX * 64;
        int baseY = start.getY() + chunkY * 64;
        int baseZ = start.getZ() + chunkZ * 64;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                for (int z = 0; z < 64; z++) {
                    cursor.set(baseX + x, baseY + y, baseZ + z);
                    data[x][y][z] = Block.getId(projectionLevel.getBlockState(cursor));
                }
            }
        }

        int chunkOffset = 1;
        int centeredX = chunkX - chunkOffset;
        int centeredY = chunkY - chunkOffset;
        int centeredZ = chunkZ - chunkOffset;
        physicalBody.setBlockStateChunk(centeredX, centeredY, centeredZ, data);

        int half = 64;
        int originX = start.getX() + half;
        int originY = start.getY() + half;
        int originZ = start.getZ() + half;
        int lastX = baseX + 64 - 1;
        int lastY = baseY + 64 - 1;
        int lastZ = baseZ + 64 - 1;
        physicalBody.clearBlockEntities(
                new BlockPos(baseX - originX, baseY - originY, baseZ - originZ),
                new BlockPos(lastX - originX, lastY - originY, lastZ - originZ));

        List<SyncPhysicalBodyBlockEntity.Entry> blockEntityEntries = new ArrayList<>();
        for (int cx = baseX >> 4; cx <= lastX >> 4; cx++) {
            for (int cz = baseZ >> 4; cz <= lastZ >> 4; cz++) {
                LevelChunk chunk = projectionLevel.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos bePos = entry.getKey();
                    if (bePos.getX() >= baseX && bePos.getX() <= lastX
                            && bePos.getY() >= baseY && bePos.getY() <= lastY
                            && bePos.getZ() >= baseZ && bePos.getZ() <= lastZ) {
                        BlockPos localPos = bePos.offset(-originX, -originY, -originZ);
                        physicalBody.putBlockEntity(localPos, entry.getValue());
                        CompoundTag tag = entry.getValue().saveWithFullMetadata(projectionLevel.registryAccess());
                        tag.putInt("x", localPos.getX());
                        tag.putInt("y", localPos.getY());
                        tag.putInt("z", localPos.getZ());
                        blockEntityEntries.add(new SyncPhysicalBodyBlockEntity.Entry(localPos, tag));
                    }
                }
            }
        }
        return blockEntityEntries;
    }

    /**
     * 处理客户端的交互动作（服务端权威）。
     *
     * <h2>三态</h2>
     * <ul>
     *   <li><b>{@code ATTACK_STOP}</b>：清掉该玩家的挖掘进度并返回 true。
     *       <b>不检查任何前提</b> —— 停手必须永远有效，哪怕体已经没了。</li>
     *   <li><b>{@code ATTACK} / {@code USE}</b>：用玩家<b>自己的</b>眼位与视线
     *       重新打一次射线（不信客户端报的命中点）；命中的体必须就是它声明的那个，
     *       且相交点必须落在这块地皮内，否则拒绝。</li>
     * </ul>
     *
     * <p>命中点要经过两次换算才进原版逻辑：
     * 局部命中点 {@code + 地皮中心} = 地皮坐标；
     * 再构造一个 {@link BlockHitResult}（用<b>地皮坐标</b>与<b>局部法线</b>）
     * 交给原版的破坏/使用路径 —— 于是 {@code onDestroyedByPlayer}、{@code canSurvive}、
     * 容器开关全都按原版语义跑，一行都不用改。</p>
     */
    public static boolean handleInteraction(ServerPlayer player, UUID physicalBodyId, byte action,
                                           InteractionHand hand) {
        if (action == INTERACTION_ATTACK_STOP) {
            breakProgress.remove(player.getUUID());
            return true;
        }
        if (action != INTERACTION_ATTACK && action != INTERACTION_USE) {
            return false;
        }
        if (projectionLevel == null) {
            return false;
        }
        ServerPhysicalWorld physicalWorld = ServerPhysicalWorld.getPhysicalWorld(player.serverLevel());
        if (physicalWorld == null) {
            return false;
        }
        if (!(physicalWorld.getPhysicalBody(physicalBodyId) instanceof ServerPhysicalBody physicalBody)
                || !player.level().dimension().location().equals(physicalBody.getLevel())) {
            return false;
        }
        Projection projection = physicalBody.getProjection();
        if (projection == null) {
            return false;
        }

        // 服务端自己重算射线（见类注释：不信客户端的命中点）
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F);
        PhysicalRaycast.Hit hit = PhysicalRaycast.cast(physicalWorld,
                new Vector3d(eye.x, eye.y, eye.z), new Vector3d(view.x, view.y, view.z),
                player.blockInteractionRange());
        if (hit == null || !hit.physicalBody().getUuid().equals(physicalBodyId)) {
            return false;
        }

        BlockPos projectionOrigin = projectionOrigin(projection);
        BlockPos projectionPos = projectionOrigin.offset(hit.localBlockPos());
        if (!containsProjectionPosition(projection, projectionPos)) {
            return false;
        }

        Vec3 projectionHitLocation = new Vec3(
                projectionOrigin.getX() + hit.localLocation().x,
                projectionOrigin.getY() + hit.localLocation().y,
                projectionOrigin.getZ() + hit.localLocation().z);
        BlockHitResult blockHit = new BlockHitResult(projectionHitLocation, hit.localFace(), projectionPos, false);

        return switch (action) {
            case INTERACTION_ATTACK -> continueDestroy(player, physicalBody, projection, projectionPos,
                    physicalBlockCenter(physicalBody, hit.localBlockPos()));
            case INTERACTION_USE -> useBlock(player, physicalBody, projection, hand, blockHit);
            default -> false;
        };
    }

    /**
     * 持续挖掘的推进。
     *
     * <p>四个分支各自的存在理由：
     * <ol>
     *   <li><b>空气 → false</b>：方块已经被挖掉了，不推进（也不发包）。</li>
     *   <li><b>同一 tick 重复 → 直接 true</b>：连点会一 tick 来好几次，
     *       不节流等于瞬间挖穿。</li>
     *   <li><b>创造模式</b>：不走进度，直接破坏；但要用
     *       {@link #CREATIVE_DESTROY_DELAY} 节流（对齐原版 {@code destroyDelay}），
     *       否则按住左键会瞬拆一串。</li>
     *   <li><b>生存模式</b>：按 {@code state.getDestroyProgress}
     *       累加进度；<b>换目标或停顿超过 2 tick 就重新开始</b>
     *       （原版手感）。满了才真破坏，并向客户端发 {@code -1} 终止信号。</li>
     * </ol>
     * 累计进度只在<b>确实在挖同一格</b>时保留，所以换目标必须重置 ——
     * 否则"对着石头挖一半再对准泥土"会立刻挖穿。</p>
     */
    private static boolean continueDestroy(ServerPlayer player, PhysicalBody physicalBody, Projection projection,
                                          BlockPos pos, Vector3d worldLocation) {
        BlockState state = projectionLevel.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        GameType gameType = player.gameMode.getGameModeForPlayer();
        long tick = projectionLevel.getServer().getTickCount();
        Long lastAttack = attackThrottle.get(player.getUUID());
        if (lastAttack != null && tick == lastAttack) {
            return true;
        }
        if (gameType.isCreative()) {
            if (lastAttack != null && tick - lastAttack < CREATIVE_DESTROY_DELAY) {
                return true;
            }
            boolean destroyed = destroyBlock(player, pos, state, gameType, worldLocation);
            if (destroyed) {
                attackThrottle.put(player.getUUID(), tick);
            }
            return destroyed;
        }

        attackThrottle.put(player.getUUID(), tick);
        BreakProgress progress = breakProgress.get(player.getUUID());
        if (progress == null || !progress.pos.equals(pos) || tick - progress.lastTick > 2L) {
            progress = new BreakProgress(pos, 0.0F, tick);
            breakProgress.put(player.getUUID(), progress);
            state.attack(projectionLevel, pos, player);
        }
        progress.value += state.getDestroyProgress(player, projectionLevel, pos);
        progress.lastTick = tick;
        if (progress.value < 1.0F) {
            syncBreakProgress(player, physicalBody, projection, pos, progress.value);
            return true;
        }
        breakProgress.remove(player.getUUID());
        boolean destroyed = destroyBlock(player, pos, state, gameType, worldLocation);
        syncBreakProgress(player, physicalBody, projection, pos, -1.0F);
        return destroyed;
    }

    /**
     * 在地皮上破坏一格 —— <b>完全走原版的破坏链</b>。
     *
     * <p>顺序是原版 {@code ServerPlayerGameMode#destroyBlock} 的等价物：
     * 先发 {@link BreakEvent}（其它模组可取消）→ 权限检查 →
     * {@code playerWillDestroy} → 工具损耗（{@code mineBlock}）→
     * {@code onDestroyedByPlayer}（掉落在这里生成）→
     * {@code block.destroy} → 仅在非创造且能收获时 {@code playerDestroy}
     * （创造模式不掉落）→ 工具用坏时发事件 → 移除方块实体 →
     * {@link #onBlockUpload} 标脏（改动要回写客户端）→ 放破坏音效。</p>
     */
    private static boolean destroyBlock(ServerPlayer player, BlockPos pos, BlockState state, GameType gameType,
                                       Vector3d worldLocation) {
        BreakEvent event = CommonHooks.fireBlockBreak(projectionLevel, gameType, player, pos, state);
        if (event.isCanceled()) {
            return false;
        }
        Block block = state.getBlock();
        if (block instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
            return false;
        }
        if (player.blockActionRestricted(projectionLevel, pos, gameType)) {
            return false;
        }

        BlockEntity blockEntity = projectionLevel.getBlockEntity(pos);
        BlockState destroyedState = block.playerWillDestroy(projectionLevel, pos, state, player);
        ItemStack tool = player.getMainHandItem();
        ItemStack originalTool = tool.copy();
        boolean canHarvest = destroyedState.canHarvestBlock(projectionLevel, pos, player);
        boolean creative = gameType.isCreative();
        if (!creative) {
            tool.mineBlock(projectionLevel, destroyedState, pos, player);
        }

        boolean removed = destroyedState.onDestroyedByPlayer(projectionLevel, pos, player,
                !creative && canHarvest, projectionLevel.getFluidState(pos));
        if (removed) {
            destroyedState.getBlock().destroy(projectionLevel, pos, destroyedState);
        }
        if (!creative && canHarvest && removed) {
            block.playerDestroy(projectionLevel, player, pos, destroyedState, blockEntity, originalTool);
        }
        if (tool.isEmpty() && !originalTool.isEmpty()) {
            EventHooks.onPlayerDestroyItem(player, originalTool, InteractionHand.MAIN_HAND);
        }
        if (removed) {
            if (blockEntity != null) {
                projectionLevel.removeBlockEntity(pos);
            }
            onBlockUpload(pos);
            playBlockSound(player, worldLocation,
                    destroyedState.getSoundType(projectionLevel, pos, player), true);
        }
        return removed;
    }

    /**
     * 在地皮上使用方块/物品。
     *
     * <h2>为什么要临时改玩家的朝向（照 space 0.1.3）</h2>
     * 原版放置逻辑会读玩家的 {@code yRot/xRot} 来决定朝向（台阶、楼梯、告示牌…）。
     * 但玩家看到的是<b>船体局部坐标系</b>里的朝向 —— 若船的朝向与世界不同，
     * 直接用玩家的世界朝向放置，方块会转向错误的一侧。
     * 所以先用 {@link #applyLocalRotation} 把玩家朝向换算进体的局部系、跑完原版逻辑、
     * 再用 {@link RotationState} <b>原样还原</b>（{@code finally} 里还原，
     * 保证异常也不留下被改过的朝向）。
     *
     * <h2>三个"变化点"分别要标脏</h2>
     * 点击格、放置格、以及"放置音效的候选格"（用来判断这一下到底放没放成）。
     * 少标任何一个，客户端看到的就是旧方块。</p>
     */
    private static boolean useBlock(ServerPlayer player, PhysicalBody physicalBody, Projection projection,
                                   InteractionHand hand, BlockHitResult hit) {
        BlockPos clickedPos = hit.getBlockPos();
        BlockPos placementPos = clickedPos.relative(hit.getDirection());
        BlockState clickedState = projectionLevel.getBlockState(clickedPos);
        BlockState placementState = containsProjectionPosition(projection, placementPos)
                ? projectionLevel.getBlockState(placementPos) : null;
        RotationState backup = RotationState.capture(player);
        applyLocalRotation(player, physicalBody);

        PlacementSoundCandidate placementSound;
        boolean used;
        try {
            placementSound = getPlacementSoundCandidate(player, hand, hit);
            if (placementSound != null && !containsProjectionPosition(projection, placementSound.pos)) {
                return false;
            }
            used = useBlockOnProjection(player, projection, hand, hit);
        } finally {
            backup.restore(player);
        }

        if (used && projectionLevel.getBlockState(clickedPos) != clickedState) {
            onBlockUpload(clickedPos);
        }
        if (used && placementState != null && projectionLevel.getBlockState(placementPos) != placementState) {
            onBlockUpload(placementPos);
        }
        if (used && placementSound != null) {
            playPlacementSound(player, physicalBody, projection, placementSound);
        }
        return used;
    }

    /**
     * 把玩家的世界朝向换算成"体局部坐标系里的朝向"并写回玩家。
     *
     * <p>用体旋转的<b>共轭</b>把视线向量转进局部系，再反解出 yaw/pitch
     * （注意原版约定：视线 = {@code (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))}）。
     * {@code setYHeadRot}/{@code setYBodyRot} 也要一起设 —— 否则身体与头朝向不一致，
     * 放置时读到的朝向又不对了。</p>
     */
    private static void applyLocalRotation(ServerPlayer player, PhysicalBody physicalBody) {
        Vector3d localView = new Vector3d(player.getViewVector(1.0F).toVector3f());
        new Quaterniond(physicalBody.getRotation()).conjugate().transform(localView).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-localView.x, localView.z));
        float pitch = (float) Math.toDegrees(Math.asin(-Math.max(-1.0, Math.min(1.0, localView.y))));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
    }

    /**
     * 预测"这一下会放在哪一格"。
     *
     * <p>用原版的 {@link BlockPlaceContext} 算而不是自己推 —— 它包含
     * "点击面被占用时往外挤一格"等原版规则。拿到位置后先记下当时的状态，
     * 之后比对状态是否变化就知道有没有放成（{@link #playPlacementSound}）。</p>
     */
    private static PlacementSoundCandidate getPlacementSoundCandidate(ServerPlayer player, InteractionHand hand,
                                                                     BlockHitResult hit) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem)) {
            return null;
        }
        BlockPos pos = new BlockPlaceContext(new UseOnContext(projectionLevel, player, hand, stack, hit))
                .getClickedPos();
        return new PlacementSoundCandidate(pos, projectionLevel.getBlockState(pos));
    }

    /** 只在"确实放下了方块"时播放置音效（比对状态是否变化）。 */
    private static void playPlacementSound(ServerPlayer player, PhysicalBody physicalBody, Projection projection,
                                          PlacementSoundCandidate candidate) {
        BlockState placedState = projectionLevel.getBlockState(candidate.pos);
        if (placedState != candidate.previousState && !placedState.isAir()) {
            playBlockSound(player, physicalBlockCenter(physicalBody, toLocal(projection, candidate.pos)),
                    placedState.getSoundType(projectionLevel, candidate.pos, player), false);
        }
    }

    /**
     * 在地皮上真正执行"使用"—— <b>逐级回退的原版交互链</b>。
     *
     * <p>顺序照原版 {@code ServerPlayerGameMode#useItemOn}：
     * 先 {@code onItemUseFirst}（模组钩子）→ 潜行绕过判断 →
     * {@code state.useItemOn}（方块自己的交互：拉杆、按钮、开箱）→
     * {@code useWithoutItem}（空手交互）→ 最后才把物品 {@code useOn}（放置）。
     * 每一级 {@code consumesAction()} 就停 —— 这就是"原版优先级"。</p>
     *
     * <p>两个反作弊/一致性细节：创造模式要把物品数量<b>还原</b>（放方块会减 1），
     * 以及最后 {@code inventoryMenu.sendAllDataToRemote()} 同步背包
     * —— 否则客户端显示的物品数量与服务端不一致。</p>
     */
    private static boolean useBlockOnProjection(ServerPlayer player, Projection projection, InteractionHand hand,
                                               BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        BlockState state = projectionLevel.getBlockState(pos);
        ItemStack stack = player.getItemInHand(hand);
        if (!state.getBlock().isEnabled(projectionLevel.enabledFeatures())
                || player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
            return false;
        }

        UseOnContext context = new UseOnContext(projectionLevel, player, hand, stack, hit);
        if (stack.onItemUseFirst(context).consumesAction()) {
            return true;
        }

        boolean hasItems = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        boolean bypassUse = player.isSecondaryUseActive() && hasItems
                && (!player.getMainHandItem().doesSneakBypassUse(projectionLevel, pos, player)
                || !player.getOffhandItem().doesSneakBypassUse(projectionLevel, pos, player));
        ItemStack before = stack.copy();
        if (!bypassUse) {
            ItemInteractionResult blockResult = state.useItemOn(stack, projectionLevel, player, hand, hit);
            if (blockResult.consumesAction()) {
                CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(player, pos, before);
                return true;
            }
            if (blockResult == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    && hand == InteractionHand.MAIN_HAND
                    && state.useWithoutItem(projectionLevel, player, hit).consumesAction()) {
                return true;
            }
        }

        if (stack.isEmpty() || player.getCooldowns().isOnCooldown(stack.getItem())) {
            return false;
        }
        if (!containsProjectionPosition(projection, pos.relative(hit.getDirection()))) {
            return false;
        }
        int count = stack.getCount();
        InteractionResult itemResult = stack.useOn(context);
        if (player.hasInfiniteMaterials()) {
            // 创造模式放方块也会减 1，这里还原（见方法注释）
            stack.setCount(count);
        }
        if (itemResult.consumesAction()) {
            CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(player, pos, before);
        }
        player.inventoryMenu.sendAllDataToRemote();
        return itemResult.consumesAction();
    }

    /**
     * 在<b>世界坐标</b>播方块音效。
     *
     * <p>投影维度里的音效没人听得见（那边没有玩家），所以必须换算到船所在维度的
     * 世界位置再播。{@code null} 作为音源 = 不绑定实体（与方块无关）。</p>
     */
    private static void playBlockSound(ServerPlayer player, Vector3d worldLocation, SoundType soundType,
                                      boolean breakSound) {
        player.serverLevel().playSound(null, worldLocation.x, worldLocation.y, worldLocation.z,
                breakSound ? soundType.getBreakSound() : soundType.getPlaceSound(),
                SoundSource.BLOCKS, (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
    }

    /**
     * 把进度发给<b>单个</b>玩家（只有他在挖）。
     * {@code -1} 是终止信号（见 {@code SyncPhysicalBlockBreakProgress}）。
     */
    private static void syncBreakProgress(ServerPlayer player, PhysicalBody physicalBody, Projection projection,
                                         BlockPos projectionPos, float progress) {
        PacketDistributor.sendToPlayer(player, new SyncPhysicalBlockBreakProgress(
                physicalBody.getUuid(), toLocal(projection, projectionPos), progress));
    }

    /** 地皮中心（局部坐标原点）。 */
    private static BlockPos projectionOrigin(Projection projection) {
        int half = 64;
        return projection.getStart().offset(half, half, half);
    }

    /** 投影坐标是否落在这块 128³ 地皮内。 */
    private static boolean containsProjectionPosition(Projection projection, BlockPos pos) {
        BlockPos start = projection.getStart();
        return pos.getX() >= start.getX() && pos.getX() < start.getX() + 128
                && pos.getY() >= start.getY() && pos.getY() < start.getY() + 128
                && pos.getZ() >= start.getZ() && pos.getZ() < start.getZ() + 128;
    }

    /** 投影坐标 → 体局部坐标。 */
    private static BlockPos toLocal(Projection projection, BlockPos projectionPos) {
        return projectionPos.subtract(projectionOrigin(projection));
    }

    /** 体局部格中心 → 世界坐标（体旋转 + 平移）。 */
    private static Vector3d physicalBlockCenter(PhysicalBody physicalBody, BlockPos localPos) {
        Vector3d center = new Vector3d(localPos.getX() + 0.5, localPos.getY() + 0.5, localPos.getZ() + 0.5);
        physicalBody.getRotation().transform(center);
        return center.add(physicalBody.getPos());
    }

    /** 维度 id → ServerLevel（经投影维度的 server，避免依赖调用方手上有没有 server）。 */
    private static ServerLevel levelOf(ResourceLocation dimension) {
        return dimension != null && projectionLevel != null
                ? projectionLevel.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension))
                : null;
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ()));
    }

    /** 一个玩家当前的挖掘进度（格 + 累计值 + 上次 tick）。 */
    private static final class BreakProgress {
        private final BlockPos pos;
        private float value;
        private long lastTick;

        private BreakProgress(BlockPos pos, float value, long lastTick) {
            this.pos = pos;
            this.value = value;
            this.lastTick = lastTick;
        }
    }

    /** 放置音效的候选（位置 + 放置前的状态，用于判断是否真的放上了）。 */
    private static record PlacementSoundCandidate(BlockPos pos, BlockState previousState) {
    }

    /**
     * 一块地皮。
     *
     * <p>几何是<b>定死的</b>：槽位 {@code slot} 的地皮从 {@code (slot*192 − 64, −64, −64)}
     * 到 {@code (slot*192 + 64, 64, 64)} —— 边长 128、间隔 64。
     * 所以"地皮的世界 AABB"不需要存，随时能算出来。</p>
     */
    public static final class Projection {
        private final int slot;

        public Projection(int slot) {
            this.slot = slot;
        }

        public BlockPos getStart() {
            return new BlockPos(this.slot * 192 - 64, -64, -64);
        }

        public BlockPos getEnd() {
            return new BlockPos(this.slot * 192 + 64, 64, 64);
        }

        public int getSlot() {
            return this.slot;
        }
    }

    /**
     * 槽位分配游标（存档）。
     *
     * <p>存的是 {@code nextSlot} 而不是"已用集合"：槽位<b>只增不减</b>
     * （见 {@link #createNewProjection}），所以一个整数就够。
     * 读档时用它继续往下分，保证新体不会撞上老体的地皮
     * —— 即使那些老体已经不在世界里。</p>
     */
    private static class ProjectionSlotData extends SavedData {
        private int nextSlot = 0;

        private static final SavedData.Factory<ProjectionSlotData> FACTORY =
                new SavedData.Factory<>(ProjectionSlotData::new, ProjectionSlotData::load);

        public static ProjectionSlotData get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(FACTORY, "projection_slots");
        }

        public static ProjectionSlotData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
            ProjectionSlotData data = new ProjectionSlotData();
            data.nextSlot = tag.getInt("nextSlot");
            return data;
        }

        public int getNextSlot() {
            return this.nextSlot;
        }

        public void setNextSlot(int nextSlot) {
            this.nextSlot = nextSlot;
            this.setDirty();
        }

        @Override
        public @NotNull CompoundTag save(@NotNull CompoundTag compoundTag,
                                        @NotNull net.minecraft.core.HolderLookup.Provider provider) {
            compoundTag.putInt("nextSlot", this.nextSlot);
            return compoundTag;
        }
    }

    /** 玩家的四个朝向字段快照：使用方块时临时改朝向后<b>原样还原</b>（见 {@link #useBlock}）。 */
    private static record RotationState(float yRot, float xRot, float yHeadRot, float yBodyRot) {
        static RotationState capture(ServerPlayer player) {
            return new RotationState(player.getYRot(), player.getXRot(), player.getYHeadRot(), player.yBodyRot);
        }

        void restore(ServerPlayer player) {
            player.setYRot(this.yRot);
            player.setXRot(this.xRot);
            player.setYHeadRot(this.yHeadRot);
            player.setYBodyRot(this.yBodyRot);
        }
    }
}
