package com.mss.polymech.item;

import com.mss.polymech.ModDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.SimpleFluidContent;

import java.util.List;

/*
 * 通用流体单元物品。
 * <p>
 * 单一物品即可盛装任意流体：流体内容存储在fluid_content数据组件中，
 * 通过Capabilities.FluidHandler.ITEM物品能力与储罐、管道等交互。
 * 渲染使用neoforge:fluid_container模型加载器：底图(base)不染色，
 * 流体层(overlay)按所含流体的颜色染色。
 * </p>
 */
public class FluidCellItem extends Item {
    /** 单元容量：1000 mB（1桶） */
    public static final int CAPACITY = FluidType.BUCKET_VOLUME;

    public FluidCellItem(Properties properties) {
        super(properties);
    }

    /*
     * 构建装满指定流体的单元物品堆（用于创造标签页等）。
     *
     * @param fluid  流体
     * @param amount 流体数量（mB）
     * @return 装有流体的单元物品堆
     */
    public static ItemStack getFilledCellStack(Fluid fluid, int amount) {
        ItemStack stack = new ItemStack(ModItems.UNIVERSAL_FLUID_CELL.get());
        stack.set(ModDataComponents.FLUID_CONTENT.get(), SimpleFluidContent.copyOf(new FluidStack(fluid, amount)));
        return stack;
    }

    /*
     * 获取单元中存储的流体内容。
     *
     * @param stack 单元物品堆
     * @return 流体堆叠，为空时返回FluidStack.EMPTY
     */
    public static FluidStack getFluid(ItemStack stack) {
        SimpleFluidContent content = stack.get(ModDataComponents.FLUID_CONTENT.get());
        return content == null ? FluidStack.EMPTY : content.copy();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        FluidStack fluid = getFluid(stack);
        if (fluid.isEmpty()) {
            tooltipComponents.add(Component.translatable("tooltip.poly_mech.fluid_cell.empty"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.poly_mech.fluid_cell.stored",
                    fluid.getFluidType().getDescription(fluid), fluid.getAmount(), CAPACITY));
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return !getFluid(stack).isEmpty();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return Math.round((float) getFluid(stack).getAmount() * 13F / CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x3E8BC3;
    }
}
