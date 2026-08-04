package com.mss.polymech.pipenet;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 世界级管道管网管理器（每个 ServerLevel 一个，随存档持久化）。
 * <p>
 * 设计要点（性能优先）：
 * <ul>
 *   <li>管道方块不挂 BlockEntity，所有数据存在管网对象里</li>
 *   <li>惰性组网：能力首次访问才构建；结构变化才重建</li>
 *   <li>活跃优化：无流体的管网 tick 直接跳过</li>
 *   <li>客户端零同步：网络数据不下发</li>
 * </ul>
 * 守恒保证：任何结构变化（放置/拆除/扳手断连/重载校验）都先收集旧网流体，
 * 再按容量比例重新注入新网；仅在"管网彻底消失且无法重分配"的极端情况下
 * 以源方块形式泼洒回世界，全程不销毁流体。
 * </p>
 */
@EventBusSubscriber(modid = Polymech.MOD_ID)
public class WorldPipeNet extends SavedData {

    private static final Map<ServerLevel, WorldPipeNet> INSTANCES = new WeakHashMap<>();
    private static final String DATA_NAME = "poly_mech_pipe_net";

    private final ServerLevel level;
    /** 坐标 → 所属管网 索引（O(1) 能力查询） */
    private final Map<BlockPos, FluidPipeNet> pipeIndex = new HashMap<>();
    private final List<FluidPipeNet> nets = new ArrayList<>();

    public WorldPipeNet(ServerLevel level) {
        this.level = level;
    }

    public static WorldPipeNet get(ServerLevel level) {
        WorldPipeNet cached = INSTANCES.get(level);
        if (cached != null) return cached;
        WorldPipeNet instance = level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(() -> new WorldPipeNet(level), (tag, registries) -> load(tag, level)),
                DATA_NAME);
        INSTANCES.put(level, instance);
        return instance;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Pre event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;
        for (ServerLevel level : server.getAllLevels()) {
            WorldPipeNet.get(level).tick();
        }
    }

    // ==================== 每 tick 驱动 ====================

    public void tick() {
        // 快照遍历：校验重建可能增删 nets
        for (FluidPipeNet net : List.copyOf(nets)) {
            if (!net.isValidated()) {
                validateNet(net);
            }
            net.tick(level);
        }
    }

    // ==================== 结构变化钩子 ====================

    /** 新管道放置：与相邻管网合并（携带所有旧网流体） */
    public void onPipePlaced(BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof PipeBlock)) return;
        if (pipeIndex.containsKey(pos)) return; // 已入网（防御）

        Set<FluidPipeNet> affected = new LinkedHashSet<>();
        for (Direction dir : Direction.values()) {
            if (isConnected(level, pos, dir)) {
                FluidPipeNet neighborNet = pipeIndex.get(pos.relative(dir));
                if (neighborNet != null) affected.add(neighborNet);
            }
        }

        FluidPool pool = new FluidPool();
        Set<BlockPos> candidates = new HashSet<>();
        candidates.add(pos.immutable());
        for (FluidPipeNet net : affected) {
            pool.addAll(net.drainAll());
            candidates.addAll(net.allPipes());
            unregister(net);
        }
        rebuild(candidates, pool, pos);
    }

    /** 管道移除：拆分剩余管网，流体按容量比例守恒分配 */
    public void onPipeRemoved(BlockPos pos) {
        FluidPipeNet net = pipeIndex.get(pos);
        if (net == null) return;
        FluidPool pool = net.drainAll();
        Set<BlockPos> candidates = new HashSet<>(net.allPipes());
        candidates.remove(pos);
        unregister(net);
        rebuild(candidates, pool, pos);
    }

    /** 扳手切换连接：受影响的管网按当前连通性重新聚类（可能合并也可能分裂） */
    public void onConnectionsChanged(BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof PipeBlock)) return;

        Set<FluidPipeNet> affected = new LinkedHashSet<>();
        FluidPipeNet own = pipeIndex.get(pos);
        if (own != null) affected.add(own);
        for (Direction dir : Direction.values()) {
            if (isConnected(level, pos, dir)) {
                FluidPipeNet neighborNet = pipeIndex.get(pos.relative(dir));
                if (neighborNet != null) affected.add(neighborNet);
            }
        }

        FluidPool pool = new FluidPool();
        Set<BlockPos> candidates = new HashSet<>();
        for (FluidPipeNet net : affected) {
            pool.addAll(net.drainAll());
            candidates.addAll(net.allPipes());
            unregister(net);
        }
        if (candidates.isEmpty()) {
            candidates.add(pos.immutable());
        }
        rebuild(candidates, pool, pos);
    }

    /**
     * 批量铺设结束后的统一重建：批量铺设期间每根新管放置时面还是 NONE，
     * {@link #onPipePlaced} 只能为每根管各建一个单管孤立网；接线完成后必须把
     * 所有涉及的网（每根新管自己的网 + 与之相邻的旧网）按当前连通性整体重新聚类，
     * 否则管网会碎片化，流体只能走通起点附近一两根管的距离。
     */
    public void onBatchConnectionsChanged(Collection<BlockPos> positions) {
        Set<FluidPipeNet> affected = new LinkedHashSet<>();
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos pos : positions) {
            BlockPos immutable = pos.immutable();
            if (!(level.getBlockState(immutable).getBlock() instanceof PipeBlock)) continue;
            candidates.add(immutable);
            FluidPipeNet own = pipeIndex.get(immutable);
            if (own != null) affected.add(own);
            for (Direction dir : Direction.values()) {
                if (isConnected(level, immutable, dir)) {
                    FluidPipeNet neighborNet = pipeIndex.get(immutable.relative(dir));
                    if (neighborNet != null) affected.add(neighborNet);
                }
            }
        }

        FluidPool pool = new FluidPool();
        for (FluidPipeNet net : affected) {
            pool.addAll(net.drainAll());
            candidates.addAll(net.allPipes());
            unregister(net);
        }
        if (candidates.isEmpty()) return;
        rebuild(candidates, pool, candidates.iterator().next());
    }

    /** 邻接方块变化：失效端点缓存 */
    public void onNeighborChanged(BlockPos pos) {
        FluidPipeNet net = pipeIndex.get(pos);
        if (net != null) {
            net.markEndpointsDirty(pos);
        }
    }

    /** 能力查询入口：取坐标所属管网，不存在则惰性构建 */
    @org.jetbrains.annotations.Nullable
    public FluidPipeNet getOrCreateNetAt(BlockPos pos) {
        FluidPipeNet net = pipeIndex.get(pos);
        if (net != null) return net;
        if (!(level.getBlockState(pos).getBlock() instanceof PipeBlock)) return null;
        // 惰性组网：先无限制泛洪找出整个连通域（历史管道视为空管，无流体可携带）
        Set<BlockPos> cluster = floodFillUnrestricted(pos);
        rebuild(cluster, new FluidPool(), pos);
        return pipeIndex.get(pos);
    }

    /** 无限制泛洪：按当前连接属性收集 pos 所在的整个管道连通域 */
    private Set<BlockPos> floodFillUnrestricted(BlockPos start) {
        Set<BlockPos> visited = new LinkedHashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        queue.add(start.immutable());
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (!visited.add(cur)) continue;
            BlockState state = level.getBlockState(cur);
            if (!(state.getBlock() instanceof PipeBlock)) continue;
            for (Direction dir : Direction.values()) {
                if (state.getValue(PipeBlock.getProperty(dir)) == PipeBlock.PipeConnection.NONE) continue;
                BlockPos next = cur.relative(dir).immutable();
                if (!visited.contains(next) && isConnected(level, cur, dir)) {
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    // ==================== 核心重建 ====================

    /**
     * 通用重建：把候选坐标按当前连通性聚类成新管网，并把携带流体按容量比例注入。
     */
    private void rebuild(Collection<BlockPos> candidates, FluidPool pool, BlockPos spillAnchor) {
        if (candidates.isEmpty()) {
            spill(pool, spillAnchor);
            return;
        }
        Set<BlockPos> remaining = new HashSet<>();
        for (BlockPos pos : candidates) {
            BlockPos immutable = pos.immutable();
            if (level.getBlockState(immutable).getBlock() instanceof PipeBlock) {
                remaining.add(immutable);
            }
        }

        List<FluidPipeNet> newNets = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPos seed = remaining.iterator().next();
            FluidPipeNet net = FluidPipeNet.build(level, this, seed, remaining);
            if (net.allPipes().isEmpty()) {
                remaining.remove(seed); // 防御：避免死循环
                continue;
            }
            register(net);
            newNets.add(net);
        }
        injectCarried(newNets, pool, spillAnchor);
        setDirty();
    }

    /** 按容量比例把携带流体分配到新管网；无法注入的部分泼洒回世界（不销毁） */
    private void injectCarried(List<FluidPipeNet> newNets, FluidPool pool, BlockPos spillAnchor) {
        if (pool.isEmpty()) return;
        if (newNets.isEmpty()) {
            spill(pool, spillAnchor);
            return;
        }
        long totalCapacity = 0;
        for (FluidPipeNet net : newNets) totalCapacity += Math.max(1, net.getCapacity());

        for (Map.Entry<Fluid, Integer> entry : pool.entries()) {
            Fluid fluid = entry.getKey();
            int total = entry.getValue();
            int remaining = total;

            // 第一轮：按容量比例分配（比例基数用原始总量，避免逐次扣减导致后段份额失真）
            long distributed = 0;
            for (int i = 0; i < newNets.size() && remaining > 0; i++) {
                FluidPipeNet net = newNets.get(i);
                int share = (i == newNets.size() - 1)
                        ? remaining
                        : (int) Math.min(remaining, (long) total * Math.max(1, net.getCapacity()) / Math.max(1, totalCapacity));
                int injected = net.injectFluid(fluid, share);
                distributed += injected;
                remaining -= injected;
            }
            // 第二轮：残余量兜底扫一遍（某些段被其他流体占据时）
            for (int i = 0; i < newNets.size() && remaining > 0; i++) {
                remaining -= newNets.get(i).injectFluid(fluid, remaining);
            }
            // 最终兜底：无法重分配时泼洒回世界
            if (remaining > 0) {
                spillFluid(level, fluid, remaining, spillAnchor);
            }
        }
    }

    // ==================== 载入校验 ====================

    /**
     * 存档载入的管网首次 tick 时对照世界校验（防外部编辑导致的状态不一致）。
     * <p>区块未加载的坐标不参与判定；仅当全部坐标已加载且确有不一致时才重建，
     * 避免把未加载区块的管道误判为"被拆除"而丢失流体。</p>
     */
    private void validateNet(FluidPipeNet net) {
        boolean allLoaded = true;
        boolean mismatch = false;
        outer:
        for (PipeSegment segment : net.getSegments()) {
            for (BlockPos pos : segment.getPipes()) {
                if (!level.isLoaded(pos)) {
                    allLoaded = false;
                    continue;
                }
                Block block = level.getBlockState(pos).getBlock();
                if (!(block instanceof PipeBlock pipe)
                        || pipe.getPipeMaterial() != segment.getMaterial()
                        || pipe.getPipeSize() != segment.getSize()) {
                    mismatch = true;
                    break outer;
                }
            }
        }
        if (!mismatch) {
            net.setValidated(true);
            return;
        }
        if (!allLoaded) return; // 等待区块加载后再判定

        // 确有差异：守恒重建
        FluidPool pool = net.drainAll();
        BlockPos anchor = net.allPipes().isEmpty() ? BlockPos.ZERO : net.allPipes().get(0);
        List<BlockPos> candidates = new ArrayList<>();
        for (PipeSegment segment : net.getSegments()) {
            for (BlockPos pos : segment.getPipes()) {
                if (level.getBlockState(pos).getBlock() instanceof PipeBlock) {
                    candidates.add(pos);
                }
            }
        }
        unregister(net);
        rebuild(candidates, pool, anchor);
    }

    // ==================== 注册/索引 ====================

    private void register(FluidPipeNet net) {
        nets.add(net);
        for (BlockPos pos : net.allPipes()) {
            pipeIndex.put(pos.immutable(), net);
        }
    }

    private void unregister(FluidPipeNet net) {
        nets.remove(net);
        for (BlockPos pos : net.allPipes()) {
            pipeIndex.remove(pos, net);
        }
    }

    /** 判断 from 沿 dir 是否与邻接管道构成有效连接（双向属性均须为已连接） */
    public static boolean isConnected(ServerLevel level, BlockPos from, Direction dir) {
        BlockState state = level.getBlockState(from);
        if (!(state.getBlock() instanceof PipeBlock)) return false;
        if (state.getValue(PipeBlock.getProperty(dir)) != PipeBlock.PipeConnection.CONNECTED) return false;
        BlockPos to = from.relative(dir);
        BlockState neighborState = level.getBlockState(to);
        return neighborState.getBlock() instanceof PipeBlock
                && neighborState.getValue(PipeBlock.getProperty(dir.getOpposite())) == PipeBlock.PipeConnection.CONNECTED;
    }

    // ==================== 泼洒兜底 ====================

    private void spill(FluidPool pool, BlockPos anchor) {
        for (Map.Entry<Fluid, Integer> entry : pool.entries()) {
            spillFluid(level, entry.getKey(), entry.getValue(), anchor);
        }
    }

    /**
     * 最终兜底：无法保留在管网中的流体以源方块形式放回世界。
     * 仅在"管网彻底消失/无法重分配"的极端路径触发，宁可泼洒也不销毁。
     */
    private static void spillFluid(ServerLevel level, Fluid fluid, int amount, BlockPos anchor) {
        if (amount <= 0 || fluid == null) return;
        BlockState sourceState;
        try {
            sourceState = fluid.defaultFluidState().createLegacyBlock();
        } catch (Throwable t) {
            sourceState = null;
        }
        if (sourceState == null || sourceState.isAir()) {
            Polymech.LOGGER.warn("[PipeNet] 无法泼洒 {}mB 的 {}（无源方块形态），流体被迫丢弃",
                    amount, net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid));
            return;
        }
        BlockPos base = anchor != null ? anchor : BlockPos.ZERO;
        BlockPos[] candidates = new BlockPos[]{
                base, base.above(), base.above().above(),
                base.north(), base.south(), base.east(), base.west()
        };
        int fullBuckets = amount / 1000;
        int spilled = 0;
        for (int i = 0; i < fullBuckets; i++) {
            boolean placed = false;
            for (BlockPos candidate : candidates) {
                if (level.isLoaded(candidate) && level.isEmptyBlock(candidate)) {
                    level.setBlock(candidate, sourceState, Block.UPDATE_ALL);
                    placed = true;
                    break;
                }
            }
            if (!placed) break;
            spilled += 1000;
        }
        if (spilled < amount) {
            Polymech.LOGGER.warn("[PipeNet] 泼洒不完全：{}mB 的 {} 中 {}mB 已放回世界，余量丢弃",
                    amount, net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid), spilled);
        }
    }

    // ==================== 序列化 ====================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag netsTag = new ListTag();
        for (FluidPipeNet net : nets) {
            CompoundTag netTag = new CompoundTag();
            ListTag segmentsTag = new ListTag();
            for (PipeSegment segment : net.getSegments()) {
                CompoundTag segmentTag = new CompoundTag();
                long[] longs = new long[segment.getPipes().size()];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = segment.getPipes().get(i).asLong();
                }
                segmentTag.putLongArray("pipes", longs);
                segmentTag.putString("material", segment.getMaterial().getName());
                segmentTag.putString("size", segment.getSize().getName());
                if (!segment.getBuffer().isEmpty()) {
                    segmentTag.put("fluid", segment.getBuffer().save(registries));
                }
                segmentsTag.add(segmentTag);
            }
            netTag.put("segments", segmentsTag);
            netsTag.add(netTag);
        }
        tag.put("nets", netsTag);
        return tag;
    }

    /**
     * 载入：不依赖世界方块状态（此时区块可能未加载），直接按序列化数据重建段；
     * 首次 tick 时再对照世界校验（{@link #validateNet}）。
     */
    private static WorldPipeNet load(CompoundTag tag, ServerLevel level) {
        WorldPipeNet world = new WorldPipeNet(level);
        FluidPool orphanPool = new FluidPool();
        BlockPos orphanAnchor = null;

        ListTag netsTag = tag.getList("nets", Tag.TAG_COMPOUND);
        for (int i = 0; i < netsTag.size(); i++) {
            CompoundTag netTag = netsTag.getCompound(i);
            ListTag segmentsTag = netTag.getList("segments", Tag.TAG_COMPOUND);
            FluidPipeNet net = new FluidPipeNet(world);
            boolean anySegment = false;
            for (int j = 0; j < segmentsTag.size(); j++) {
                CompoundTag segmentTag = segmentsTag.getCompound(j);
                long[] longs = segmentTag.getLongArray("pipes");
                PipeMaterial material = PipeMaterial.byName(segmentTag.getString("material"));
                PipeBlock.PipeSize size = PipeBlock.PipeSize.byName(segmentTag.getString("size"));
                FluidStack fluid = segmentTag.contains("fluid")
                        ? FluidStack.parseOptional(level.registryAccess(), segmentTag.getCompound("fluid"))
                        : FluidStack.EMPTY;

                if (material == null || size == null || longs.length == 0) {
                    // 数据异常：段无法还原，但流体必须保留 → 进孤儿池
                    if (!fluid.isEmpty()) {
                        orphanPool.add(fluid.getFluid(), fluid.getAmount());
                        if (orphanAnchor == null && longs.length > 0) {
                            orphanAnchor = BlockPos.of(longs[0]);
                        }
                    }
                    continue;
                }

                PipeSegment segment = new PipeSegment(material, size);
                for (long packed : longs) {
                    segment.addPipe(BlockPos.of(packed));
                }
                if (!fluid.isEmpty()) {
                    segment.setBuffer(fluid);
                }
                net.registerSegment(segment, segment.getPipes());
                anySegment = true;
            }
            if (anySegment) {
                net.recomputeTotals();
                net.setValidated(false); // 等待首次 tick 校验
                world.register(net);
            }
        }
        // 孤儿流体：尝试放回世界（极端路径）
        if (!orphanPool.isEmpty()) {
            world.spill(orphanPool, orphanAnchor != null ? orphanAnchor : BlockPos.ZERO);
        }
        return world;
    }

    // ==================== 流体池 ====================

    /** 重建过程中临时承载多种流体的容器（守恒中转） */
    public static class FluidPool {
        private final Map<Fluid, Integer> contents = new LinkedHashMap<>();

        public void add(Fluid fluid, int amount) {
            if (fluid == null || amount <= 0) return;
            contents.merge(fluid, amount, Integer::sum);
        }

        public void addAll(FluidStack stack) {
            if (!stack.isEmpty()) {
                add(stack.getFluid(), stack.getAmount());
            }
        }

        public void addAll(FluidPool other) {
            for (Map.Entry<Fluid, Integer> entry : other.contents.entrySet()) {
                add(entry.getKey(), entry.getValue());
            }
        }

        public boolean isEmpty() {
            return contents.isEmpty();
        }

        public Set<Map.Entry<Fluid, Integer>> entries() {
            return contents.entrySet();
        }
    }
}
