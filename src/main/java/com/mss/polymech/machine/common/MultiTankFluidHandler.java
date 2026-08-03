package com.mss.polymech.machine.common;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 多储罐组合 IFluidHandler：将多个独立储罐拼接为一个对外能力视图。
 * <p>
 * 用于侧面方块能力代理——Block 定义每个位置可暴露多个逻辑储罐索引，
 * 能力层用本类将其组合为单个 handler。填充按顺序尝试各储罐；
 * 抽取不混合流体，仅抽取与首个命中储罐相同的流体。
 * </p>
 *
 * @param handlers 参与组合的储罐处理器数组
 */
public record MultiTankFluidHandler(IFluidHandler[] handlers) implements IFluidHandler {

    @Override
    public int getTanks() {
        int total = 0;
        for (IFluidHandler handler : handlers) {
            total += handler.getTanks();
        }
        return total;
    }

    /** 将全局储罐索引映射为 {handler下标, 局部储罐下标}，越界返回 null */
    private int[] locate(int tank) {
        int index = tank;
        for (int i = 0; i < handlers.length; i++) {
            int count = handlers[i].getTanks();
            if (index < count) return new int[]{i, index};
            index -= count;
        }
        return null;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        int[] loc = locate(tank);
        if (loc == null) return FluidStack.EMPTY;
        return handlers[loc[0]].getFluidInTank(loc[1]);
    }

    @Override
    public int getTankCapacity(int tank) {
        int[] loc = locate(tank);
        if (loc == null) return 0;
        return handlers[loc[0]].getTankCapacity(loc[1]);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        int[] loc = locate(tank);
        if (loc == null) return false;
        return handlers[loc[0]].isFluidValid(loc[1], stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return 0;
        int remaining = resource.getAmount();
        for (IFluidHandler handler : handlers) {
            if (remaining <= 0) break;
            int filled = handler.fill(resource.copyWithAmount(remaining), action);
            remaining -= filled;
        }
        return resource.getAmount() - remaining;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) return FluidStack.EMPTY;
        // 先用 SIMULATE 规划每个储罐的抽取量，再按需 EXECUTE，保证原子性
        int remaining = resource.getAmount();
        int[] amounts = new int[handlers.length];
        for (int i = 0; i < handlers.length && remaining > 0; i++) {
            FluidStack drained = handlers[i].drain(resource.copyWithAmount(remaining), FluidAction.SIMULATE);
            amounts[i] = drained.getAmount();
            remaining -= drained.getAmount();
        }
        int total = resource.getAmount() - remaining;
        if (total == 0) return FluidStack.EMPTY;
        if (action.execute()) {
            for (int i = 0; i < handlers.length; i++) {
                if (amounts[i] > 0) {
                    handlers[i].drain(resource.copyWithAmount(amounts[i]), FluidAction.EXECUTE);
                }
            }
        }
        return resource.copyWithAmount(total);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) return FluidStack.EMPTY;
        // 不混合流体：遇到与首个命中储罐不同的流体即停止
        FluidStack result = FluidStack.EMPTY;
        int remaining = maxDrain;
        int[] amounts = new int[handlers.length];
        for (int i = 0; i < handlers.length && remaining > 0; i++) {
            FluidStack drained = handlers[i].drain(remaining, FluidAction.SIMULATE);
            if (drained.isEmpty()) continue;
            if (result.isEmpty()) {
                result = drained;
            } else if (!FluidStack.isSameFluidSameComponents(result, drained)) {
                break;
            }
            amounts[i] = drained.getAmount();
            remaining -= drained.getAmount();
        }
        int total = maxDrain - remaining;
        if (result.isEmpty() || total <= 0) return FluidStack.EMPTY;
        if (action.execute()) {
            for (int i = 0; i < handlers.length; i++) {
                if (amounts[i] > 0) {
                    handlers[i].drain(amounts[i], FluidAction.EXECUTE);
                }
            }
        }
        return result.copyWithAmount(total);
    }
}
