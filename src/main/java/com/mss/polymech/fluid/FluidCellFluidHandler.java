package com.mss.polymech.fluid;

import com.mss.polymech.ModDataComponents;
import com.mss.polymech.item.FluidCellItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStackSimple;

/**
 * 通用流体单元的物品流体能力。
 * <p>
 * 在{@link FluidHandlerItemStackSimple}基础上使容量动态化：
 * 生效容量 = 玩家在GUI中设置的容量上限（capacity_limit组件），
 * 未设置时为单元种类的最大容量。fill与getTankCapacity均尊重该上限，
 * 因此设置了较小上限的单元不会被灌装超过上限的流体。
 * </p>
 */
public class FluidCellFluidHandler extends FluidHandlerItemStackSimple {

    /**
     * @param container 单元物品堆
     * @param capacity  单元种类的最大容量（构造时传入的硬上限）
     */
    public FluidCellFluidHandler(ItemStack container, int capacity) {
        super(ModDataComponents.FLUID_CONTENT, container, capacity);
    }

    /** 生效容量：玩家设置的容量上限（未设置时为种类最大容量） */
    protected int effectiveCapacity() {
        return FluidCellItem.getCapacityLimit(container);
    }

    @Override
    public int getTankCapacity(int tank) {
        return effectiveCapacity();
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (container.getCount() != 1 || resource.isEmpty() || !isFluidValid(0, resource)) {
            return 0;
        }

        FluidStack content = getFluid();
        if (!content.isEmpty() && !FluidStack.isSameFluidSameComponents(resource, content)) {
            return 0;
        }

        int filled = effectiveCapacity() - content.getAmount();
        if (resource.getAmount() < filled) {
            filled = resource.getAmount();
        }
        if (filled <= 0) {
            return 0;
        }

        if (action.execute()) {
            if (content.isEmpty()) {
                setFluid(resource.copyWithAmount(filled));
            } else {
                content.grow(filled);
                setFluid(content);
            }
        }
        return filled;
    }
}
