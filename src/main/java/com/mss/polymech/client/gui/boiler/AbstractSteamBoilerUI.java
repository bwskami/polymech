package com.mss.polymech.client.gui.boiler;

import com.lowdragmc.lowdraglib2.gui.ColorPattern;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.lowdragmc.lowdraglib2.gui.ui.data.FillDirection;
import com.mss.polymech.machine.boiler.AbstractSteamBoilerBlockEntity;
import com.mss.polymech.network.MachineTogglePacket;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 蒸汽锅炉共享 UI 基类。
 * <p>
 * 大锅炉（6 槽）：左 3 槽 | 水位条 | 温度条+信息面板+蒸汽条 | 右 3 槽 | 开关按钮<br>
 * 小锅炉（3 槽）：左 3 槽 | 水位条 | 温度条+信息面板+蒸汽条 | 开关按钮
 * </p>
 */
public abstract class AbstractSteamBoilerUI {

    /** 获取锅炉标题的翻译键 */
    protected abstract String getTitleKey();

    /** 获取锅炉方块实体 */
    protected abstract AbstractSteamBoilerBlockEntity getBoiler(BlockUIMenuType.BlockUIHolder holder);

    public ModularUI createBoilerUI(BlockUIMenuType.BlockUIHolder holder) {
        var be = getBoiler(holder);
        var itemHandler = be.getItemStackHandler();
        boolean hasRightSlots = be.getInventorySize() > 3;

        // === 进度条 ===
        var tempBar = new ProgressBar();
        tempBar.label.setText(Component.empty());
        tempBar.progressBarStyle(s -> s.fillDirection(FillDirection.DOWN_TO_UP));
        tempBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.ORANGE.rectTexture())));
        tempBar.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) ->
                event.hoverTooltips = new HoverTooltips(List.of(
                        Component.literal("§e温度: " + be.getTemperature() + "K / 773K"),
                        Component.literal("§3产汽: " + be.getTotalSteamOutput() + " mB/t")
                ), null, null, null));

        var waterBar = new ProgressBar();
        waterBar.label.setText(Component.empty());
        waterBar.progressBarStyle(s -> s.fillDirection(FillDirection.DOWN_TO_UP));
        waterBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.LIGHT_BLUE.rectTexture())));
        waterBar.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) ->
                event.hoverTooltips = new HoverTooltips(List.of(
                        Component.literal("§b水位: " + be.getWaterAmount() + " / " + be.getWaterCapacity() + " mB")
                ), null, null, null));

        var steamBar = new ProgressBar();
        steamBar.label.setText(Component.empty());
        steamBar.progressBarStyle(s -> s.fillDirection(FillDirection.DOWN_TO_UP));
        steamBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.CYAN.rectTexture())));
        steamBar.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) ->
                event.hoverTooltips = new HoverTooltips(List.of(
                        Component.literal("§3蒸汽: " + be.getSteamAmount() + " / " + be.getSteamCapacity() + " mB"),
                        Component.literal("§7产汽速率: " + be.getTotalSteamOutput() + " mB/t")
                ), null, null, null));

        // === 开关机按钮（书签标签风格，在面板外面右下角） ===
        var toggleBtn = new Button()
                .setText(Component.translatable(be.isEnable() ? "gui.poly_mech.button.disable" : "gui.poly_mech.button.enable"))
                .setOnClick(e -> PacketDistributor.sendToServer(new MachineTogglePacket(holder.pos)))
                .buttonStyle(s -> s.baseTexture(Sprites.RECT_RD))
                .layout(l -> l.width(20).height(20));

        // === 主面板 ===
        var mainPanel = new UIElement();
        mainPanel.layout(l -> l.width(176).paddingAll(7).gapAll(4));
        mainPanel.addClass("panel_bg");

        var machineRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(2));

        // 左侧 3 槽
        machineRow.addChild(
                new UIElement().layout(l -> l.width(20).gapAll(4)).addChildren(
                        new ItemSlot().bind(itemHandler, 0),
                        new ItemSlot().bind(itemHandler, 1),
                        new ItemSlot().bind(itemHandler, 2)
                )
        );

        // 水位条
        machineRow.addChild(
                waterBar
                        .setMaxValue(100)
                        .bind(DataBindingBuilder.floatValS2C(() -> (float) be.getWaterLevel()).build())
                        .layout(l -> l.width(16).height(70))
        );

        // 中间区域：温度条 + 信息面板 + 蒸汽条
        machineRow.addChild(
                new UIElement().layout(l -> l.flex(1).flexDirection(FlexDirection.ROW).gapAll(2)).addChildren(
                        tempBar
                                .setMaxValue(100)
                                .bind(DataBindingBuilder.floatValS2C(() -> (float) be.getTemperaturePercent()).build())
                                .layout(l -> l.width(16).height(70)),
                        new UIElement().layout(l -> l.flex(1).gapAll(2).paddingAll(4)).addClass("panel_bg").addChildren(
                                new Label().setText(Component.translatable(getTitleKey())),
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    String status = be.isEnable() ? "§a运行中" : "§c已停止";
                                    return Component.literal(status);
                                }).build()),
                                new Label().bind(DataBindingBuilder.componentS2C(() ->
                                        Component.literal("§e温度: " + be.getTemperature() + "K")
                                ).build()),
                                new Label().bind(DataBindingBuilder.componentS2C(() ->
                                        Component.literal("§3效率: " + be.getTotalSteamOutput() + " mB/t")
                                ).build()),
                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                    int burnTicks = be.getFuelBurnTimeRemaining();
                                    return Component.literal("§6燃烧: " + (burnTicks / 20) + "s");
                                }).build())
                        ),
                        steamBar
                                .setMaxValue(100)
                                .bind(DataBindingBuilder.floatValS2C(() -> (float) be.getSteamLevel()).build())
                                .layout(l -> l.width(16).height(70))
                )
        );

        // 右侧 3 槽（仅大锅炉）
        if (hasRightSlots) {
            machineRow.addChild(
                    new UIElement().layout(l -> l.width(20).gapAll(4)).addChildren(
                            new ItemSlot().bind(itemHandler, 3),
                            new ItemSlot().bind(itemHandler, 4),
                            new ItemSlot().bind(itemHandler, 5)
                    )
            );
        }

        mainPanel.addChild(machineRow);
        mainPanel.addChild(new InventorySlots());

        // === 外层容器：面板 + 按钮 ===
        var wrapper = new UIElement();
        wrapper.layout(l -> l.width(196).flexDirection(FlexDirection.ROW));

        var btnContainer = new UIElement();
        btnContainer.layout(l -> l.width(20).flexDirection(FlexDirection.COLUMN));
        btnContainer.addChildren(
                new UIElement().layout(l -> l.flex(1)),
                toggleBtn
        );

        wrapper.addChildren(mainPanel, btnContainer);

        var root = new UIElement();
        root.layout(l -> l.paddingAll(7));
        root.addChild(wrapper);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }
}
