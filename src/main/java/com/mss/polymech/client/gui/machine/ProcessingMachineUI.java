package com.mss.polymech.client.gui.machine;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mss.polymech.client.gui.common.AbstractMachineUI;
import com.mss.polymech.machine.production.AbstractProcessingBlockEntity;
import net.minecraft.network.chat.Component;

/**
 * 通用加工机器 UI — 使用 {@link AbstractMachineUI} 的 {@code buildProcessingUI} 模板构建。
 * <p>
 * 职责：从 {@link BlockUIMenuType.BlockUIHolder} 提取数据，填充
 * {@link ProcessingUIConfig}，委托给基类模板方法。
 * 无需为每台加工机器单独创建 UI 子类。
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

        // 委托给基类模板方法
        return buildProcessingUI(holder, new ProcessingUIConfig(
                machine, itemHandler, inputs, outputs, tanks,
                machine.getDisplayName(), cfgPos
        ));
    }
}
