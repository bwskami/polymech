package com.mss.polymech.client.gui;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class MultiblockSelectionScreen extends ModularUIScreen {

    public MultiblockSelectionScreen() {
        super(createModularUI(), Component.translatable("gui.poly_mech.multiblock_selection.title"));
    }

    private static ModularUI createModularUI() {
        var root = new UIElement();
        root.layout(l -> l.width(256).paddingAll(16).gapAll(8));
        root.addClass("panel_bg");

        root.addChildren(
                new Label().setText(Component.translatable("gui.poly_mech.multiblock_selection.title")),

                new Button().setText(Component.translatable("gui.poly_mech.multiblock_selection.large_chemical_reactor"))
                        .setOnClick(e -> selectMachine("large_chemical_reactor")),
                new Button().setText(Component.translatable("gui.poly_mech.multiblock_selection.implosion_compressor"))
                        .setOnClick(e -> selectMachine("implosion_compressor")),
                new Button().setText(Component.translatable("gui.poly_mech.multiblock_selection.pyrolyze_oven"))
                        .setOnClick(e -> selectMachine("pyrolyze_oven")),

                new UIElement().layout(l -> l.height(8)),

                new Button().setText(Component.translatable("gui.poly_mech.multiblock_selection.close"))
                        .setOnClick(e -> Minecraft.getInstance().setScreen(null))
        );

        return ModularUI.of(UI.of(root));
    }

    private static void selectMachine(String machineType) {
        Polymech.LOGGER.info("Selected multiblock machine: {}", machineType);
        Minecraft.getInstance().setScreen(null);
    }
}
