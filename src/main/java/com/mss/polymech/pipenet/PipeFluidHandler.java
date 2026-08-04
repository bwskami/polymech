package com.mss.polymech.pipenet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import com.mss.polymech.block.PipeBlock;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 管道方块的 IFluidHandler 能力包装。
 * <p>
 * 无 BlockEntity 的轻量对象：每次调用实时解析坐标所属的管网与段，
 * 把 fill/drain 转发给管网（由管网执行吞吐预算与容量约束）。
 * </p>
 */
public class PipeFluidHandler implements IFluidHandler {

    private final ServerLevel level;
    private final BlockPos pos;
    @Nullable
    private final Direction side;

    public PipeFluidHandler(ServerLevel level, BlockPos pos, @Nullable Direction side) {
        this.level = level;
        this.pos = pos.immutable();
        this.side = side;
    }

    @Nullable
    private FluidPipeNet net() {
        return WorldPipeNet.get(level).getOrCreateNetAt(pos);
    }

    @Nullable
    private PipeSegment segment() {
        FluidPipeNet net = net();
        return net == null ? null : net.getSegmentAt(pos);
    }

    /**
     * 面状态门控：未连接（NONE）的面不与外部做任何被动交互，
     * 只有扳手把该面切到已连接/抽取后才允许外部注入/抽取。
     * side 为 null（内部查询）时不受限。
     */
    private boolean faceOpen() {
        if (side == null) return true;
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof PipeBlock)) return false;
        return state.getValue(PipeBlock.getProperty(side)) != PipeBlock.PipeConnection.NONE;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        PipeSegment segment = segment();
        return segment == null ? FluidStack.EMPTY : segment.getBuffer().copy();
    }

    @Override
    public int getTankCapacity(int tank) {
        PipeSegment segment = segment();
        return segment == null ? 0 : (int) Math.min(Integer.MAX_VALUE, segment.getCapacity());
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        PipeSegment segment = segment();
        if (segment == null) return false;
        return segment.getBuffer().isEmpty() || FluidStack.matches(segment.getBuffer(), stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!faceOpen()) return 0;
        FluidPipeNet net = net();
        return net == null ? 0 : net.fill(pos, side, resource, action);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (!faceOpen()) return FluidStack.EMPTY;
        FluidPipeNet net = net();
        return net == null ? FluidStack.EMPTY : net.drain(pos, side, resource, action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (!faceOpen()) return FluidStack.EMPTY;
        FluidPipeNet net = net();
        return net == null ? FluidStack.EMPTY : net.drain(pos, side, maxDrain, action);
    }
}
