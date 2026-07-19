package com.mss.polymech.client.gui.block;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.FluidSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mss.polymech.block.entity.FluidTankBlockEntity;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

import java.util.Objects;

public class FluidTankUI {

    public static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        var be = (FluidTankBlockEntity) Objects.requireNonNull(holder.player.level())
                .getBlockEntity(holder.pos);

        var root = new UIElement();
        root.layout(l -> l.width(176).paddingAll(7).gapAll(4));
        root.addClass("panel_bg");

        root.addChildren(
                new Label().setText(Component.translatable("block.poly_mech.fluid_tank")),

                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4)).addChildren(
                        new FluidSlot().bind(be.getFluidHandler(), 0)
                                .layout(l -> l.width(36).height(54)),

                        new UIElement().layout(l -> l.gapAll(2)).addChildren(
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    var fluid = be.getFluidStack();
                                    return fluid.isEmpty() ? Component.literal("空") : fluid.getHoverName();
                                }).build()),
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    var fluid = be.getFluidStack();
                                    if (fluid.isEmpty()) return Component.literal("");
                                    return Component.literal(BuiltInRegistries.FLUID.getKey(fluid.getFluid()).toString());
                                }).build()),
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    int amount = be.getFluidAmount();
                                    int capacity = FluidTankBlockEntity.CAPACITY;
                                    int percent = capacity > 0 ? (int) ((float) amount / capacity * 100) : 0;
                                    if (amount >= 10000) return Component.literal(percent + "% (" + (amount / 1000) + "B)");
                                    return Component.literal(percent + "% (" + amount + "mB)");
                                }).build())
                        )
                ),

                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4)).addChildren(
                        new ItemSlot().bind(be.getBucketHandler(), 0),
                        new ItemSlot().bind(be.getBucketHandler(), 1)
                ),

                new InventorySlots()
        );

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }
}