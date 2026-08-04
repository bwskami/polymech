package com.mss.polymech.item;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.ModDataComponents;
import com.mss.polymech.client.gui.cell.FluidCellConfigUI;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
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
 * <p>
 * 每种单元种类有自己的最大容量（如小型250/通用1000/中型4000/超大型16000 mB），
 * 玩家可按住Shift右键打开GUI，把容量上限设置为0到种类最大容量之间的任意值；
 * 已有流体时上限不能低于已储存量。上限存储在capacity_limit数据组件中，
 * 未设置时单元以种类最大容量工作。
 * </p>
 */
public class FluidCellItem extends Item implements HeldItemUIMenuType.HeldItemUI {
    /** 通用流体单元的默认种类最大容量：1000 mB（1桶） */
    public static final int CAPACITY = FluidType.BUCKET_VOLUME;

    /** 本单元种类的最大容量（mB） */
    private final int maxCapacity;

    public FluidCellItem(Properties properties, int maxCapacity) {
        super(properties);
        this.maxCapacity = maxCapacity;
    }

    /** 本单元种类的最大容量（mB） */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /** 获取物品堆所属单元种类的最大容量；非单元物品返回0 */
    public static int getMaxCapacity(ItemStack stack) {
        return stack.getItem() instanceof FluidCellItem cell ? cell.getMaxCapacity() : 0;
    }

    /*
     * 获取单元的生效容量上限（mB）。
     * <p>
     * 玩家设置过capacity_limit组件时返回该值（夹取到[0, 种类最大容量]），
     * 未设置时返回种类最大容量。
     * </p>
     */
    public static int getCapacityLimit(ItemStack stack) {
        int max = getMaxCapacity(stack);
        Integer limit = stack.get(ModDataComponents.CAPACITY_LIMIT.get());
        return limit == null ? max : Mth.clamp(limit, 0, max);
    }

    /*
     * 设置单元的容量上限。达到或超过种类最大容量时移除组件（归一化为默认值）。
     * 下限校验由调用方（GUI/服务端包处理）负责。
     */
    public static void setCapacityLimit(ItemStack stack, int limit) {
        if (limit >= getMaxCapacity(stack)) {
            stack.remove(ModDataComponents.CAPACITY_LIMIT.get());
        } else {
            stack.set(ModDataComponents.CAPACITY_LIMIT.get(), Math.max(0, limit));
        }
    }

    /*
     * 构建装满指定流体的通用单元物品堆（用于创造标签页等）。
     *
     * @param fluid  流体
     * @param amount 流体数量（mB）
     * @return 装有流体的单元物品堆
     */
    public static ItemStack getFilledCellStack(Fluid fluid, int amount) {
        return getFilledCellStack(ModItems.UNIVERSAL_FLUID_CELL.get(), fluid, amount);
    }

    /*
     * 构建装满指定流体的指定种类单元物品堆。
     *
     * @param cell   单元物品
     * @param fluid  流体
     * @param amount 流体数量（mB）
     * @return 装有流体的单元物品堆
     */
    public static ItemStack getFilledCellStack(Item cell, Fluid fluid, int amount) {
        ItemStack stack = new ItemStack(cell);
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

    /*
     * Shift+右键打开容量上限设置GUI。
     */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                HeldItemUIMenuType.openUI(serverPlayer, hand);
            }
            return InteractionResultHolder.success(player.getItemInHand(hand));
        }
        return super.use(level, player, hand);
    }

    /*
     * 手持物品GUI（LDLib2 HeldItemUI）：容量上限设置界面。
     */
    @Override
    public ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return FluidCellConfigUI.create(holder);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        FluidStack fluid = getFluid(stack);
        int limit = getCapacityLimit(stack);
        if (fluid.isEmpty()) {
            tooltipComponents.add(Component.translatable("tooltip.poly_mech.fluid_cell.empty"));
        } else {
            tooltipComponents.add(Component.translatable("tooltip.poly_mech.fluid_cell.stored",
                    fluid.getFluidType().getDescription(fluid), fluid.getAmount(), limit));
        }
        // 玩家设置过低于种类最大值的上限时提示
        if (limit < maxCapacity) {
            tooltipComponents.add(Component.translatable("tooltip.poly_mech.fluid_cell.limit", limit, maxCapacity));
        }
        tooltipComponents.add(Component.translatable("tooltip.poly_mech.fluid_cell.config_hint"));
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return !getFluid(stack).isEmpty();
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        int limit = getCapacityLimit(stack);
        if (limit <= 0) return 13;
        return Math.round((float) getFluid(stack).getAmount() * 13F / limit);
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0x3E8BC3;
    }
}
