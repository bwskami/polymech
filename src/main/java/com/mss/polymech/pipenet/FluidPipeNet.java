package com.mss.polymech.pipenet;

import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流体管网：一张连通的管道图。
 * <p>
 * 内部按"段"（同材质同尺寸连片区）组织：
 * <ul>
 *   <li>每 tick 先做段间转移（速率取两端吞吐的最小值 → 小管瓶颈效应）</li>
 *   <li>再把段内流体按吞吐预算轮转推送给外部端点</li>
 *   <li>无流体的管网 tick 成本近似为 0</li>
 * </ul>
 * 所有转移均遵循"先模拟后执行/精确扣减"，重建时流体按容量比例
 * 重新分配，任何路径上都不会凭空销毁流体。
 * </p>
 */
public class FluidPipeNet {

    /** 管道某一侧的交互面，用于每 tick 吞吐预算记账 */
    public record PipeFace(BlockPos pos, @Nullable Direction side) {}

    private final WorldPipeNet owner;
    private final Map<BlockPos, PipeSegment> segmentOf = new HashMap<>();
    private final List<PipeSegment> segments = new ArrayList<>();
    private final List<PipeSegment[]> edges = new ArrayList<>();
    /** 本 tick 内各交互面已使用的吞吐量 */
    private final Map<PipeFace, Integer> usedBudget = new HashMap<>();

    private long totalFluid = 0;
    /** 从存档载入的管网需要在首次 tick 时对照世界校验 */
    private boolean validated = false;

    FluidPipeNet(WorldPipeNet owner) {
        this.owner = owner;
    }

    // ==================== 构建 ====================

    void registerSegment(PipeSegment segment, Collection<BlockPos> pipes) {
        segments.add(segment);
        for (BlockPos pos : pipes) {
            segmentOf.put(pos.immutable(), segment);
        }
    }

    /** 段构建完成后，扫描段间邻接生成边（去重） */
    void buildEdges(ServerLevel level) {
        edges.clear();
        Map<PipeSegment, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < segments.size(); i++) index.put(segments.get(i), i);
        Set<Long> seen = new HashSet<>();
        for (PipeSegment segment : segments) {
            for (BlockPos pos : segment.getPipes()) {
                BlockState state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof PipeBlock)) continue;
                for (Direction dir : Direction.values()) {
                    if (state.getValue(PipeBlock.getProperty(dir)) == PipeBlock.PipeConnection.NONE) continue;
                    PipeSegment other = segmentOf.get(pos.relative(dir));
                    if (other == null || other == segment) continue;
                    int a = index.get(segment), b = index.get(other);
                    long key = a < b ? ((long) a << 32) | (b & 0xFFFFFFFFL) : ((long) b << 32) | (a & 0xFFFFFFFFL);
                    if (seen.add(key)) {
                        edges.add(new PipeSegment[]{segment, other});
                    }
                }
            }
        }
    }

    /**
     * 从世界中以 seed 为起点构建管网（BFS 消耗 pool 中的候选坐标）。
     */
    public static FluidPipeNet build(ServerLevel level, WorldPipeNet owner, BlockPos seed, Set<BlockPos> pool) {
        FluidPipeNet net = new FluidPipeNet(owner);

        // 1. BFS 连通域（依据方块连接属性，双向均须连接）
        Set<BlockPos> visited = new LinkedHashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed.immutable());
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (!visited.add(cur)) continue;
            pool.remove(cur);
            BlockState state = level.getBlockState(cur);
            if (!(state.getBlock() instanceof PipeBlock)) continue;
            for (Direction dir : Direction.values()) {
                if (state.getValue(PipeBlock.getProperty(dir)) == PipeBlock.PipeConnection.NONE) continue;
                BlockPos next = cur.relative(dir).immutable();
                if (visited.contains(next) || !pool.contains(next)) continue;
                BlockState nextState = level.getBlockState(next);
                if (nextState.getBlock() instanceof PipeBlock
                        && nextState.getValue(PipeBlock.getProperty(dir.getOpposite())) != PipeBlock.PipeConnection.NONE) {
                    queue.add(next);
                }
            }
        }

        // 2. 连通域内按 (材质, 尺寸) 再分片成段
        Set<BlockPos> assigned = new HashSet<>();
        for (BlockPos start : visited) {
            if (assigned.contains(start)) continue;
            BlockState startState = level.getBlockState(start);
            if (!(startState.getBlock() instanceof PipeBlock startPipe)) continue;

            PipeSegment segment = new PipeSegment(startPipe.getPipeMaterial(), startPipe.getPipeSize());
            List<BlockPos> members = new ArrayList<>();
            Set<BlockPos> seenLocal = new HashSet<>();
            ArrayDeque<BlockPos> segQueue = new ArrayDeque<>();
            segQueue.add(start);
            while (!segQueue.isEmpty()) {
                BlockPos cur = segQueue.poll();
                if (!seenLocal.add(cur)) continue;
                BlockState curState = level.getBlockState(cur);
                if (!(curState.getBlock() instanceof PipeBlock curPipe)) continue;
                if (curPipe.getPipeMaterial() != startPipe.getPipeMaterial()
                        || curPipe.getPipeSize() != startPipe.getPipeSize()) continue;
                members.add(cur);
                for (Direction dir : Direction.values()) {
                    if (curState.getValue(PipeBlock.getProperty(dir)) == PipeBlock.PipeConnection.NONE) continue;
                    BlockPos next = cur.relative(dir).immutable();
                    if (visited.contains(next) && !seenLocal.contains(next)) {
                        segQueue.add(next);
                    }
                }
            }
            for (BlockPos member : members) segment.addPipe(member);
            assigned.addAll(members);
            net.registerSegment(segment, members);
        }

        net.buildEdges(level);
        net.validated = true; // 直接由实时世界构建，无需校验
        return net;
    }

    // ==================== tick ====================

    public void tick(ServerLevel level) {
        // 吞吐预算每 tick 重置（即使无流体也要重置，保证外部 fill/drain 预算正确）
        usedBudget.clear();

        // 0. 抽取端点：从 EXTRACT 面主动抽取流体入段缓冲（空网也要执行，否则无法起步）
        for (PipeSegment segment : segments) {
            List<PipeSegment.Endpoint> endpoints = segment.getOrBuildEndpoints(level);
            for (PipeSegment.Endpoint endpoint : endpoints) {
                if (!endpoint.extract()) continue;
                extractFromEndpoint(segment, endpoint);
            }
        }

        if (totalFluid <= 0) return;

        // 1. 段间转移：从充盈率高的段流向低的段，速率取两端吞吐最小值
        for (PipeSegment[] edge : edges) {
            PipeSegment a = edge[0], b = edge[1];
            if (a.getBuffer().isEmpty() && b.getBuffer().isEmpty()) continue;
            if (!fluidCompatible(a, b)) continue;
            double ratioA = a.fillRatio(), ratioB = b.fillRatio();
            if (ratioA == ratioB) continue;
            PipeSegment from = ratioA > ratioB ? a : b;
            PipeSegment to = ratioA > ratioB ? b : a;
            if (from.getBuffer().isEmpty()) continue;
            long space = to.getCapacity() - to.getAmount();
            if (space <= 0) continue;
            int limit = Math.min(from.getThroughput(), to.getThroughput());
            int move = (int) Math.min(Math.min(from.getAmount(), space), limit);
            if (move <= 0) continue;
            transferBetween(from, to, move);
            owner.setDirty();
        }

        // 2. 端点分发：每段按 round-robin 把流体推给 CONNECTED 端点（EXTRACT 端点只抽不推）
        for (PipeSegment segment : segments) {
            if (segment.getBuffer().isEmpty()) continue;
            List<PipeSegment.Endpoint> endpoints = segment.getOrBuildEndpoints(level);
            int n = endpoints.size();
            if (n == 0) continue;
            int start = segment.nextRoundRobin();
            for (int i = 0; i < n && !segment.getBuffer().isEmpty(); i++) {
                PipeSegment.Endpoint endpoint = endpoints.get(Math.floorMod(start + i, n));
                if (endpoint.extract()) continue; // 抽取面不回推，避免流体在源设备与管道间空转
                PipeFace face = new PipeFace(endpoint.pipePos(), endpoint.side());
                int budget = segment.getThroughput() - usedBudget.getOrDefault(face, 0);
                if (budget <= 0) continue;
                int offer = (int) Math.min(segment.getAmount(), budget);
                if (offer <= 0) continue;
                FluidStack resource = new FluidStack(segment.getBuffer().getFluid(), offer);
                int accepted;
                try {
                    accepted = endpoint.handler().fill(resource, IFluidHandler.FluidAction.EXECUTE);
                } catch (Throwable t) {
                    // 第三方 handler 异常不应击穿管网 tick
                    accepted = 0;
                }
                if (accepted > 0) {
                    shrinkBuffer(segment, accepted);
                    usedBudget.merge(face, accepted, Integer::sum);
                    totalFluid -= accepted;
                    owner.setDirty();
                }
            }
        }
    }

    /** 从单个 EXTRACT 端点主动抽取流体入段缓冲（受面吞吐预算与段容量约束） */
    private void extractFromEndpoint(PipeSegment segment, PipeSegment.Endpoint endpoint) {
        PipeFace face = new PipeFace(endpoint.pipePos(), endpoint.side());
        int budget = segment.getThroughput() - usedBudget.getOrDefault(face, 0);
        if (budget <= 0) return;
        long space = segment.getCapacity() - segment.getAmount();
        if (space <= 0) return;

        FluidStack simulated;
        try {
            simulated = endpoint.handler().drain(budget, IFluidHandler.FluidAction.SIMULATE);
        } catch (Throwable t) {
            return; // 第三方 handler 异常不应击穿管网 tick
        }
        if (simulated.isEmpty()) return;
        if (!segment.getBuffer().isEmpty() && !FluidStack.matches(segment.getBuffer(), simulated)) return;

        int requested = (int) Math.min(Math.min(simulated.getAmount(), space), budget);
        if (requested <= 0) return;
        FluidStack drained;
        try {
            drained = endpoint.handler().drain(new FluidStack(simulated.getFluid(), requested), IFluidHandler.FluidAction.EXECUTE);
        } catch (Throwable t) {
            return;
        }
        if (drained.isEmpty()) return;

        if (segment.getBuffer().isEmpty()) {
            segment.setBuffer(new FluidStack(drained.getFluid(), drained.getAmount()));
        } else {
            segment.getBuffer().grow(drained.getAmount());
        }
        usedBudget.merge(face, drained.getAmount(), Integer::sum);
        totalFluid += drained.getAmount();
        owner.setDirty();
    }

    private static boolean fluidCompatible(PipeSegment a, PipeSegment b) {
        return a.getBuffer().isEmpty() || b.getBuffer().isEmpty() || FluidStack.matches(a.getBuffer(), b.getBuffer());
    }

    private static void transferBetween(PipeSegment from, PipeSegment to, int amount) {
        FluidStack fromBuffer = from.getBuffer();
        if (to.getBuffer().isEmpty()) {
            to.setBuffer(new FluidStack(fromBuffer.getFluid(), amount));
        } else {
            to.getBuffer().grow(amount);
        }
        fromBuffer.shrink(amount);
        if (fromBuffer.getAmount() <= 0) from.setBuffer(FluidStack.EMPTY);
    }

    private static void shrinkBuffer(PipeSegment segment, int amount) {
        FluidStack buffer = segment.getBuffer();
        buffer.shrink(amount);
        if (buffer.getAmount() <= 0) segment.setBuffer(FluidStack.EMPTY);
    }

    // ==================== 外部交互（能力层调用） ====================

    @Nullable
    public PipeSegment getSegmentAt(BlockPos pos) {
        return segmentOf.get(pos);
    }

    /**
     * 外部向管道注入流体（机器 fill 管道）。受边界管吞吐预算与段剩余容量约束。
     */
    public int fill(BlockPos pos, @Nullable Direction side, FluidStack resource, IFluidHandler.FluidAction action) {
        if (resource.isEmpty()) return 0;
        PipeSegment segment = segmentOf.get(pos);
        if (segment == null) return 0;
        if (!segment.getBuffer().isEmpty() && !FluidStack.matches(segment.getBuffer(), resource)) return 0;

        PipeFace face = new PipeFace(pos.immutable(), side);
        int budget = segment.getThroughput() - usedBudget.getOrDefault(face, 0);
        if (budget <= 0) return 0;
        long space = segment.getCapacity() - segment.getAmount();
        if (space <= 0) return 0;

        int amount = (int) Math.min(Math.min(resource.getAmount(), budget), space);
        if (amount <= 0) return 0;
        if (action.execute()) {
            if (segment.getBuffer().isEmpty()) {
                segment.setBuffer(new FluidStack(resource.getFluid(), amount));
            } else {
                segment.getBuffer().grow(amount);
            }
            usedBudget.merge(face, amount, Integer::sum);
            totalFluid += amount;
            owner.setDirty();
        }
        return amount;
    }

    /**
     * 外部从管道抽出流体（机器 drain 管道）。受边界管吞吐预算约束。
     */
    public FluidStack drain(BlockPos pos, @Nullable Direction side, int maxDrain, IFluidHandler.FluidAction action) {
        PipeSegment segment = segmentOf.get(pos);
        if (segment == null || maxDrain <= 0) return FluidStack.EMPTY;
        FluidStack buffer = segment.getBuffer();
        if (buffer.isEmpty()) return FluidStack.EMPTY;

        PipeFace face = new PipeFace(pos.immutable(), side);
        int budget = segment.getThroughput() - usedBudget.getOrDefault(face, 0);
        if (budget <= 0) return FluidStack.EMPTY;

        int amount = Math.min(Math.min(maxDrain, budget), buffer.getAmount());
        if (amount <= 0) return FluidStack.EMPTY;
        FluidStack drained = new FluidStack(buffer.getFluid(), amount);
        if (action.execute()) {
            shrinkBuffer(segment, amount);
            usedBudget.merge(face, amount, Integer::sum);
            totalFluid -= amount;
            owner.setDirty();
        }
        return drained;
    }

    /** 指定流体的抽取（流体类型不匹配时返回空） */
    public FluidStack drain(BlockPos pos, @Nullable Direction side, FluidStack resource, IFluidHandler.FluidAction action) {
        PipeSegment segment = segmentOf.get(pos);
        if (segment == null || resource.isEmpty()) return FluidStack.EMPTY;
        if (segment.getBuffer().isEmpty() || !FluidStack.matches(segment.getBuffer(), resource)) return FluidStack.EMPTY;
        return drain(pos, side, resource.getAmount(), action);
    }

    // ==================== 守恒重建支持 ====================

    /**
     * 守恒注入：把携带的流体按容量比例摊入各段。
     * <p>允许超出段容量（超容状态），只要求"不丢"；外部 fill 会被容量挡住，
     * 超额部分会随端点分发自然排空。</p>
     *
     * @return 实际注入量
     */
    public int injectFluid(Fluid fluid, int amount) {
        if (amount <= 0 || fluid == null) return 0;
        List<PipeSegment> candidates = new ArrayList<>();
        for (PipeSegment segment : segments) {
            if (segment.getBuffer().isEmpty() || segment.getBuffer().is(fluid)) {
                candidates.add(segment);
            }
        }
        if (candidates.isEmpty()) return 0;

        long totalCap = 0;
        for (PipeSegment segment : candidates) totalCap += Math.max(1, segment.getCapacity());

        int remaining = amount;
        for (int i = 0; i < candidates.size(); i++) {
            PipeSegment segment = candidates.get(i);
            int share = (i == candidates.size() - 1)
                    ? remaining
                    : (int) Math.min(remaining, (long) amount * Math.max(1, segment.getCapacity()) / Math.max(1, totalCap));
            if (share <= 0) continue;
            if (segment.getBuffer().isEmpty()) {
                segment.setBuffer(new FluidStack(fluid, share));
            } else {
                segment.getBuffer().grow(share);
            }
            remaining -= share;
        }
        int injected = amount - remaining;
        if (injected > 0) {
            totalFluid += injected;
            owner.setDirty();
        }
        return injected;
    }

    /** 清空全部段的缓冲，返回流体池（用于重建前的守恒转移） */
    public WorldPipeNet.FluidPool drainAll() {
        WorldPipeNet.FluidPool pool = new WorldPipeNet.FluidPool();
        for (PipeSegment segment : segments) {
            if (!segment.getBuffer().isEmpty()) {
                pool.add(segment.getBuffer().getFluid(), segment.getBuffer().getAmount());
                segment.setBuffer(FluidStack.EMPTY);
            }
        }
        totalFluid = 0;
        return pool;
    }

    /** 从存档载入后重算 totalFluid */
    void recomputeTotals() {
        totalFluid = 0;
        for (PipeSegment segment : segments) {
            totalFluid += segment.getAmount();
        }
    }

    // ==================== 查询 ====================

    public List<BlockPos> allPipes() {
        return new ArrayList<>(segmentOf.keySet());
    }

    public List<PipeSegment> getSegments() {
        return segments;
    }

    public long getCapacity() {
        long cap = 0;
        for (PipeSegment segment : segments) cap += segment.getCapacity();
        return cap;
    }

    public boolean isValidated() {
        return validated;
    }

    public void setValidated(boolean validated) {
        this.validated = validated;
    }

    public void markEndpointsDirty(BlockPos pos) {
        PipeSegment segment = segmentOf.get(pos);
        if (segment != null) segment.markEndpointsDirty();
    }
}
