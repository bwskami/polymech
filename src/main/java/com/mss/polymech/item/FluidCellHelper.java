package com.mss.polymech.item;

import com.mss.polymech.ModDataComponents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 通用流体单元与储罐交互的工具类。
 * <p>
 * 用于机器槽位内的流体单元与机器储罐之间的双向流体转移：
 * <ul>
 *   <li>先尝试 储罐→单元（装填），再尝试 单元→储罐（排空）</li>
 *   <li>天然支持部分转移（如储罐只剩 600 mB 时装入 600 mB），不会吞流体</li>
 *   <li>单元支持不满上限的部分装填/排空（与桶不同），上限为种类容量或玩家设置的capacity_limit</li>
 * </ul>
 * </p>
 */
public final class FluidCellHelper {

    private FluidCellHelper() {}

    /** 判断物品堆是否为流体单元（任意种类） */
    public static boolean isFluidCell(ItemStack stack) {
        return stack.getItem() instanceof FluidCellItem;
    }

    /**
     * 流体单元与储罐之间的双向转移。
     * <p>
     * 优先尝试从储罐装填单元；若不可行再尝试把单元排空进储罐。
     * 支持部分转移，余下流体始终保留在原处（单元或储罐），不会丢失。
     * </p>
     *
     * @param cellStack 单元物品堆（不会被修改，内部始终操作其副本）
     * @param tank      目标储罐
     * @param execute   true 时实际执行转移；false 时仅模拟（不改动储罐，仅计算结果单元）
     * @return 转移后的单元物品堆（count==1 的新副本）；若没有发生任何转移则返回 null（储罐与单元均未被改动）
     */
    public static ItemStack processCellAgainstTank(ItemStack cellStack, IFluidHandler tank, boolean execute) {
        // 先尝试储罐→单元，再尝试单元→储罐
        ItemStack filled = fillCellFromTank(cellStack, tank, execute);
        if (filled != null) return filled;
        return drainCellIntoTank(cellStack, tank, execute);
    }

    /**
     * 储罐 → 单元：从储罐抽取流体装入单元。
     * <p>
     * 支持部分转移（如储罐只剩 600 mB 时装入 600 mB），剩余流体留在储罐。
     * </p>
     *
     * @param cellStack 单元物品堆（不会被修改）
     * @param tank      源储罐
     * @param execute   true 时实际执行；false 时仅模拟
     * @return 装填后的单元（count==1 新副本）；无法装填时返回 null
     */
    public static ItemStack fillCellFromTank(ItemStack cellStack, IFluidHandler tank, boolean execute) {
        FluidStack content = FluidCellItem.getFluid(cellStack);
        // 生效容量：玩家设置的容量上限（未设置时为种类最大容量）
        int space = FluidCellItem.getCapacityLimit(cellStack) - content.getAmount();
        if (space <= 0) return null;
        FluidStack available = tank.drain(space, IFluidHandler.FluidAction.SIMULATE);
        if (available.isEmpty()) return null;
        // 单元为空时接受任意流体；已有流体时只接受同种流体
        if (!content.isEmpty() && available.getFluid() != content.getFluid()) return null;
        int amount = available.getAmount();
        if (execute) {
            tank.drain(amount, IFluidHandler.FluidAction.EXECUTE);
        }
        FluidStack newContent;
        if (content.isEmpty()) {
            newContent = new FluidStack(available.getFluid(), amount);
        } else {
            newContent = content.copy();
            newContent.grow(amount);
        }
        return applyContent(cellStack.copyWithCount(1), newContent);
    }

    /**
     * 单元 → 储罐：把单元内流体排入储罐。
     * <p>
     * 支持部分转移（储罐空间不足时只排一部分，或单元本身未满），
     * 剩余流体留在单元内，不会丢失。
     * </p>
     *
     * @param cellStack 单元物品堆（不会被修改）
     * @param tank      目标储罐
     * @param execute   true 时实际执行；false 时仅模拟
     * @return 排空后的单元（count==1 新副本）；无法排入时返回 null
     */
    public static ItemStack drainCellIntoTank(ItemStack cellStack, IFluidHandler tank, boolean execute) {
        FluidStack content = FluidCellItem.getFluid(cellStack);
        if (content.isEmpty()) return null;
        int accepted = tank.fill(content, IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) return null;
        if (execute) {
            tank.fill(content, IFluidHandler.FluidAction.EXECUTE);
        }
        FluidStack newContent = content.copy();
        newContent.shrink(accepted);
        return applyContent(cellStack.copyWithCount(1), newContent);
    }

    /**
     * 将流体内容写回单元物品堆的数据组件；内容为空时移除组件。
     *
     * @param stack   单元物品堆副本（被直接修改）
     * @param content 新的流体内容
     * @return 修改后的同一物品堆
     */
    private static ItemStack applyContent(ItemStack stack, FluidStack content) {
        if (content.isEmpty()) {
            stack.remove(ModDataComponents.FLUID_CONTENT.get());
        } else {
            stack.set(ModDataComponents.FLUID_CONTENT.get(), SimpleFluidContent.copyOf(content));
        }
        return stack;
    }
}
