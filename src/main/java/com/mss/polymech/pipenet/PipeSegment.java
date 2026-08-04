package com.mss.polymech.pipenet;

import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * 管道段：同材质 + 同尺寸的连通管道合并而成的逻辑单元。
 * <p>
 * 段内流体视为共享缓冲池（瞬时均摊，不做逐格模拟），
 * 容量随管道数量线性增长，吞吐由尺寸基准流速 × 材质倍率决定。
 * 段是管网 tick 的最小单位，长串同型管道只产生一个段节点。
 * </p>
 */
public class PipeSegment {

    /** 段边界上的外部流体端点（邻接的机器/储罐 IFluidHandler）；extract=true 表示管道主动抽取 */
    public record Endpoint(BlockPos pipePos, Direction side, IFluidHandler handler, boolean extract) {}

    private final PipeMaterial material;
    private final PipeBlock.PipeSize size;
    private final List<BlockPos> pipes = new ArrayList<>();

    /** 段共享流体缓冲（单一流体；守恒重建时可短暂超出容量） */
    private FluidStack buffer = FluidStack.EMPTY;

    /** 段总容量（mB） */
    private long capacity;
    /** 段吞吐（mB/t），所有边界交互的速率上限 */
    private int throughput;

    /** 端点缓存（邻接方块变化时失效重建） */
    private boolean endpointsDirty = true;
    private final List<Endpoint> endpoints = new ArrayList<>();
    /** round-robin 轮转索引，保证多端点均分 */
    private int rrIndex = 0;

    public PipeSegment(PipeMaterial material, PipeBlock.PipeSize size) {
        this.material = material;
        this.size = size;
    }

    void addPipe(BlockPos pos) {
        pipes.add(pos.immutable());
        recompute();
    }

    /** 管道数量变化后重算容量与吞吐（吞吐 = 尺寸基准 × 材质乘数，统一入口） */
    void recompute() {
        this.capacity = Math.min(Integer.MAX_VALUE, (long) pipes.size() * size.getCapacityPerPipe());
        this.throughput = size.getThroughput(material);
    }

    public PipeMaterial getMaterial() { return material; }
    public PipeBlock.PipeSize getSize() { return size; }
    public List<BlockPos> getPipes() { return pipes; }
    public long getCapacity() { return capacity; }
    public int getThroughput() { return throughput; }

    public FluidStack getBuffer() { return buffer; }

    public void setBuffer(FluidStack buffer) {
        this.buffer = buffer == null ? FluidStack.EMPTY : buffer;
    }

    public long getAmount() {
        return buffer.isEmpty() ? 0 : buffer.getAmount();
    }

    /** 充盈率（可 >1，表示守恒重建后的超容状态） */
    public double fillRatio() {
        return capacity <= 0 ? 0 : (double) getAmount() / capacity;
    }

    public void markEndpointsDirty() {
        this.endpointsDirty = true;
    }

    public int nextRoundRobin() {
        return rrIndex++;
    }

    /**
     * 惰性构建/刷新本段的外部端点列表。
     * <p>
     * 规则：邻接管道永远不是端点；未连接（NONE）的面不与外部交互；
     * CONNECTED 面记为推送端点（管道主动向邻接输出）；
     * EXTRACT 面记为抽取端点（管道主动从邻接抽取，不向其推送）。
     * </p>
     */
    public List<Endpoint> getOrBuildEndpoints(ServerLevel level) {
        if (!endpointsDirty) return endpoints;
        endpoints.clear();
        for (BlockPos pos : pipes) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof PipeBlock)) continue;
            for (Direction dir : Direction.values()) {
                PipeBlock.PipeConnection conn = state.getValue(PipeBlock.getProperty(dir));
                if (conn == PipeBlock.PipeConnection.NONE) continue; // 未连接：不交互
                BlockPos neighborPos = pos.relative(dir);
                if (level.getBlockState(neighborPos).getBlock() instanceof PipeBlock) continue; // 管道不是端点
                IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, neighborPos, dir.getOpposite());
                if (handler != null) {
                    endpoints.add(new Endpoint(pos.immutable(), dir, handler, conn == PipeBlock.PipeConnection.EXTRACT));
                }
            }
        }
        endpointsDirty = false;
        return endpoints;
    }
}
