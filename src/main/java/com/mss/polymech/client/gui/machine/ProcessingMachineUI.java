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
import com.lowdragmc.lowdraglib2.gui.ui.data.FillDirection;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.mss.polymech.client.gui.common.AbstractMachineUI;
import com.mss.polymech.machine.production.AbstractProcessingBlockEntity;
import com.mss.polymech.machine.production.AbstractTurbineGeneratorBlockEntity;
import com.mss.polymech.network.MachineTogglePacket;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 通用加工机器 UI — 继承 {@link AbstractMachineUI} 复用通用组件。
 * <p>
 * 通用组件（来自基类）：
 * <ul>
 *   <li>电源键（右侧书签）— {@link #createPowerButton}</li>
 *   <li>面配置 tab（左侧书签）— {@link #createSideConfigTab}</li>
 *   <li>悬浮面配置面板 — {@link #addSideConfigComponents}</li>
 * </ul>
 * <p>
 * 布局根据方块实体声明自动生成：
 * 标题 → 输入槽 | 进度条 | 输出槽 | 储罐 → 状态行 → 玩家物品栏
 * </p>
 */
public class ProcessingMachineUI extends AbstractMachineUI {

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
        final var cfgPos = holder.pos.immutable();

        // ===== 根窗口 176x166：主面板 + 九宫格背景 =====
        var root = new UIElement();
        root.layout(l -> l.width(176).height(166));
        root.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_BASE).setSprite(0, 0, 195, 136).setBorder(4)));

        // === 标题 ===
        root.addChild(new Label().setText(machine.getDisplayName())
                .textStyle(s -> s.textColor(TITLE_COLOR))
                .layout(l -> l.width(160).positionType(TaffyPosition.ABSOLUTE).left(8).top(6)));

        // === 进度条 ===
        var progressBar = new ProgressBar();
        progressBar.label.setText(Component.empty());
        progressBar.progressBarStyle(s -> s.fillDirection(FillDirection.LEFT_TO_RIGHT));
        progressBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.ORANGE.rectTexture())));
        progressBar.setMaxValue(100)
                .bind(DataBindingBuilder.floatValS2C(() -> (float) machine.getProgressPercent()).build())
                .layout(l -> l.width(40).height(16));

        // === 主面板内容 ===
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

        root.addChild(machineRow.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(8).top(20)));

        // 状态行
        var statusLabel = new Label();
        statusLabel.textStyle(s -> s.textColor(TITLE_COLOR));
        statusLabel.bind(DataBindingBuilder.componentS2C(() -> statusLine(machine)).build());
        statusLabel.layout(l -> l.width(160).positionType(TaffyPosition.ABSOLUTE).left(8).top(66));
        root.addChild(statusLabel);

        // 玩家背包
        root.addChild(new InventorySlots()
                .layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(8).top(84)));

        // ===== 基类通用组件：电源键（大机器不需要面配置）=====
        Button powerBtn = createPowerButton(cfgPos, machine.isEnable(), (pos, enabled) -> {
            PacketDistributor.sendToServer(new MachineTogglePacket(pos));
        });
        root.addChild(powerBtn);

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
