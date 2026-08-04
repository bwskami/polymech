package com.mss.polymech.fluid;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 按 {@link TankIO} 限制方向的流体处理器包装器（参考 GregTech 的能力 IO 门控）。
 * <p>
 * 将内部储罐以指定 IO 模式暴露给外部（世界能力、管道、手持容器交互等）：
 * <ul>
 *   <li>{@code fill}：仅当 {@link TankIO#canFill()} 时放行，否则一律返回 0</li>
 *   <li>{@code drain}：仅当 {@link TankIO#canDrain()} 时放行，否则一律返回空</li>
 * </ul>
 * 只读查询方法（getTanks/getTankCapacity/getFluidInTank/isFluidValid）始终透传，
 * GUI 显示与信息查询不受影响。机器内部逻辑应直接操作原始储罐，不受包装限制。
 * </p>
 */
public class IOFluidHandler implements IFluidHandler {

    private final IFluidHandler delegate;
    private final TankIO io;

    public IOFluidHandler(IFluidHandler delegate, TankIO io) {
        this.delegate = delegate;
        this.io = io;
    }

    /** 获取被包装的原始处理器（机器内部逻辑使用） */
    public IFluidHandler getDelegate() {
        return delegate;
    }

    /** 本包装器的 IO 模式 */
    public TankIO getIO() {
        return io;
    }

    @Override
    public int getTanks() {
        return delegate.getTanks();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return delegate.getFluidInTank(tank);
    }

    @Override
    public int getTankCapacity(int tank) {
        return delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return delegate.isFluidValid(tank, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!io.canFill()) return 0;
        return delegate.fill(resource, action);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (!io.canDrain()) return FluidStack.EMPTY;
        return delegate.drain(resource, action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (!io.canDrain()) return FluidStack.EMPTY;
        return delegate.drain(maxDrain, action);
    }
}
