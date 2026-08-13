package com.mss.polymech.client.gui.machine;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.FluidSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.ui.data.FillDirection;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.mss.polymech.client.gui.screen.SideConfigScreen;
import com.mss.polymech.machine.production.AbstractProcessingBlockEntity;
import com.mss.polymech.machine.production.AbstractTurbineGeneratorBlockEntity;
import com.mss.polymech.network.MachineTogglePacket;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 通用加工机器 UI（LDLib2）。
 * <p>
 * 布局根据方块实体声明自动生成：
 * 标题 → 输入槽 | 进度条 | 输出槽 | 储罐 → 状态行 → 玩家物品栏，
 * 面板外右下角为开关机按钮（沿用锅炉 UI 规范）。
 * </p>
 */
public class ProcessingMachineUI {

    public static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        var be = holder.player.level().getBlockEntity(holder.pos);
        if (!(be instanceof AbstractProcessingBlockEntity machine)) {
            // 兜底：空面板，避免打开异常
            var empty = new UIElement();
            empty.layout(l -> l.paddingAll(7));
            return ModularUI.of(UI.of(empty, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
        }

        var itemHandler = machine.getItemStackHandler();
        int[] inputs = machine.getInputSlots();
        int[] outputs = machine.getOutputSlots();
        var tanks = machine.getTanks();

        // === 进度条 ===
        var progressBar = new ProgressBar();
        progressBar.label.setText(Component.empty());
        progressBar.progressBarStyle(s -> s.fillDirection(FillDirection.LEFT_TO_RIGHT));
        progressBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.ORANGE.rectTexture())));
        progressBar.setMaxValue(100)
                .bind(DataBindingBuilder.floatValS2C(() -> (float) machine.getProgressPercent()).build())
                .layout(l -> l.width(40).height(16));

        // === 开关机按钮（书签标签风格，面板外右下角） ===
        var toggleBtn = new Button()
                .setText(Component.translatable(machine.isEnable() ? "gui.poly_mech.button.disable" : "gui.poly_mech.button.enable"))
                .setOnClick(e -> PacketDistributor.sendToServer(new MachineTogglePacket(holder.pos)))
                .buttonStyle(s -> s.baseTexture(Sprites.RECT_RD))
                .layout(l -> l.width(20).height(20));

        // === 面配置 Tab（Mekanism 风格：主面板左侧 26x18 tab） ===
        final var cfgPos = holder.pos.immutable();
        final var cfgConfig = machine.getSideConfig();
        var sideConfigTab = new Button()
                .setText(Component.literal("C").withStyle(ChatFormatting.RED))
                .setOnClick(e -> {
                    var mc = Minecraft.getInstance();
                    var curScreen = mc.screen;
                    mc.execute(() -> mc.setScreen(new SideConfigScreen(cfgPos, cfgConfig, curScreen)));
                })
                .buttonStyle(s -> s.baseTexture(Sprites.RECT_RD))
                .layout(l -> l.width(26).height(18));

        // === 主面板 ===
        var mainPanel = new UIElement();
        mainPanel.layout(l -> l.width(200).paddingAll(7).gapAll(4));
        mainPanel.addClass("panel_bg");

        mainPanel.addChild(new Label().setText(machine.getDisplayName()));

        var machineRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6));

        // 输入槽（竖排）
        if (inputs.length > 0) {
            var inputCol = new UIElement().layout(l -> l.width(20).gapAll(2));
            for (int slot : inputs) {
                inputCol.addChild(new ItemSlot().bind(itemHandler, slot));
            }
            machineRow.addChild(inputCol);
        }

        machineRow.addChild(progressBar);

        // 输出槽（竖排）
        if (outputs.length > 0) {
            var outputCol = new UIElement().layout(l -> l.width(20).gapAll(2));
            for (int slot : outputs) {
                outputCol.addChild(new ItemSlot().bind(itemHandler, slot));
            }
            machineRow.addChild(outputCol);
        }

        // 储罐（横排）
        if (tanks.length > 0) {
            var tankRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(2));
            for (int i = 0; i < tanks.length; i++) {
                final int index = i;
                tankRow.addChild(new FluidSlot().bind(tanks[index], 0)
                        .layout(l -> l.width(20).height(20)));
            }
            machineRow.addChild(tankRow);
        }

        mainPanel.addChild(machineRow);

        // 状态行
        mainPanel.addChild(new Label().bind(DataBindingBuilder.componentS2C(() -> statusLine(machine)).build()));

        mainPanel.addChild(new InventorySlots());

        // === 外层容器：左侧 Tab + 面板 + 右侧按钮 ===
        var wrapper = new UIElement();
        wrapper.layout(l -> l.width(226).flexDirection(FlexDirection.ROW));

        // 左侧 Tab 列（Mekanism: GuiSideConfigurationTab 在 (-26, 6)）
        var leftTabCol = new UIElement();
        leftTabCol.layout(l -> l.width(26).flexDirection(FlexDirection.COLUMN));
        leftTabCol.addChildren(
                new UIElement().layout(l -> l.height(6)),
                sideConfigTab
        );

        // 右侧按钮列
        var btnContainer = new UIElement();
        btnContainer.layout(l -> l.width(20).flexDirection(FlexDirection.COLUMN));
        btnContainer.addChildren(
                new UIElement().layout(l -> l.flex(1)),
                toggleBtn
        );

        wrapper.addChildren(leftTabCol, mainPanel, btnContainer);

        var root = new UIElement();
        root.layout(l -> l.paddingAll(7));
        root.addChild(wrapper);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }

    /** 状态行：发电机显示发电量，加工机器显示进度 */
    private static Component statusLine(AbstractProcessingBlockEntity machine) {
        if (!machine.isEnable()) {
            return Component.translatable("gui.poly_mech.status.stopped").withStyle(ChatFormatting.RED);
        }
        if (machine instanceof AbstractTurbineGeneratorBlockEntity gen) {
            return Component.translatable("gui.poly_mech.machine.generation", gen.getCurrentGeneration())
                    .withStyle(ChatFormatting.YELLOW);
        }
        return machine.isWorkingState()
                ? Component.translatable("gui.poly_mech.machine.progress", machine.getProgress(), machine.getMaxProgress())
                        .withStyle(ChatFormatting.GREEN)
                : Component.translatable("gui.poly_mech.status.idle").withStyle(ChatFormatting.GRAY);
    }
}
