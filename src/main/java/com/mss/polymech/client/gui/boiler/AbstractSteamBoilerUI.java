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
import com.lowdragmc.lowdraglib2.gui.ui.data.FillDirection;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.mss.polymech.client.gui.common.AbstractMachineUI;
import com.mss.polymech.machine.boiler.AbstractSteamBoilerBlockEntity;
import com.mss.polymech.network.MachineTogglePacket;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

/**
 * 蒸汽锅炉共享 UI 基类 — 继承 {@link AbstractMachineUI} 复用通用组件。
 * <p>
 * 通用组件（来自基类）：
 * <ul>
 *   <li>电源键（右侧书签）— {@link #createPowerButton}</li>
 *   <li>面配置 tab（左侧书签）— {@link #createSideConfigTab}</li>
 *   <li>悬浮面配置面板 — {@link #addSideConfigComponents}</li>
 * </ul>
 * <p>
 * 大锅炉（6 槽）：左 3 槽 | 水位条 | 温度条+信息面板+蒸汽条 | 右 3 槽<br>
 * 小锅炉（3 槽）：左 3 槽 | 水位条 | 温度条+信息面板+蒸汽条
 * </p>
 */
public abstract class AbstractSteamBoilerUI extends AbstractMachineUI {

    /** 获取锅炉标题的翻译键 */
    protected abstract String getTitleKey();

    /** 获取锅炉方块实体 */
    protected abstract AbstractSteamBoilerBlockEntity getBoiler(BlockUIMenuType.BlockUIHolder holder);

    /** 信息面板小字号标签（6px，默认 8px） */
    private static Label smallLabel() {
        Label label = new Label();
        label.textStyle(s -> s.fontSize(6.0f));
        return label;
    }

    public ModularUI createBoilerUI(BlockUIMenuType.BlockUIHolder holder) {
        var be = getBoiler(holder);
        var itemHandler = be.getItemStackHandler();
        boolean hasRightSlots = be.getInventorySize() > 3;
        final var cfgPos = holder.pos.immutable();

        // === 进度条 ===
        var tempBar = new ProgressBar();
        tempBar.label.setText(Component.empty());
        tempBar.progressBarStyle(s -> s.fillDirection(FillDirection.DOWN_TO_UP));
        tempBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.ORANGE.rectTexture())));
        tempBar.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) ->
                event.hoverTooltips = new HoverTooltips(List.of(
                        Component.translatable("gui.poly_mech.boiler.tooltip.temperature", be.getTemperature(), 773)
                                .withStyle(ChatFormatting.YELLOW),
                        Component.translatable("gui.poly_mech.boiler.tooltip.steam_output", be.getTotalSteamOutput())
                                .withStyle(ChatFormatting.DARK_AQUA)
                ), null, null, null));

        var waterBar = new ProgressBar();
        waterBar.label.setText(Component.empty());
        waterBar.progressBarStyle(s -> s.fillDirection(FillDirection.DOWN_TO_UP));
        waterBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.LIGHT_BLUE.rectTexture())));
        waterBar.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) ->
                event.hoverTooltips = new HoverTooltips(List.of(
                        Component.translatable("gui.poly_mech.boiler.tooltip.water_level", be.getWaterAmount(), be.getWaterCapacity())
                                .withStyle(ChatFormatting.AQUA)
                ), null, null, null));

        var steamBar = new ProgressBar();
        steamBar.label.setText(Component.empty());
        steamBar.progressBarStyle(s -> s.fillDirection(FillDirection.DOWN_TO_UP));
        steamBar.bar(b -> b.style(st -> st.backgroundTexture(ColorPattern.CYAN.rectTexture())));
        steamBar.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) ->
                event.hoverTooltips = new HoverTooltips(List.of(
                        Component.translatable("gui.poly_mech.boiler.tooltip.steam", be.getSteamAmount(), be.getSteamCapacity())
                                .withStyle(ChatFormatting.DARK_AQUA),
                        Component.translatable("gui.poly_mech.boiler.tooltip.steam_rate", be.getTotalSteamOutput())
                                .withStyle(ChatFormatting.GRAY)
                ), null, null, null));

        // ===== 基类通用组件：电源键 + 面配置 tab + 悬浮面板 =====
        // 电源键（右侧书签）— 使用基类默认位置 (176, 137)
        Button powerBtn = createPowerButton(cfgPos, be.isEnable(), (pos, enabled) -> {
            PacketDistributor.sendToServer(new MachineTogglePacket(pos));
        });

        // 面配置 tab（左侧书签）— 大机器不需要
        // Button sideConfigTab = createSideConfigTab(null);

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
                        new UIElement().layout(l -> l.flex(1).height(70).gapAll(2).paddingAll(4)).addClass("panel_bg").setOverflowVisible(false).addChildren(
                                smallLabel().setText(Component.translatable(getTitleKey())),
                                smallLabel().bind(DataBindingBuilder.componentS2C(() -> {
                                    boolean running = be.isEnable();
                                    return Component.translatable(running ? "gui.poly_mech.status.running" : "gui.poly_mech.status.stopped")
                                            .withStyle(running ? ChatFormatting.GREEN : ChatFormatting.RED);
                                }).build()),
                                smallLabel().bind(DataBindingBuilder.componentS2C(() ->
                                        Component.translatable("gui.poly_mech.boiler.temperature", be.getTemperature())
                                                .withStyle(ChatFormatting.YELLOW)
                                ).build()),
                                smallLabel().bind(DataBindingBuilder.componentS2C(() ->
                                        Component.translatable("gui.poly_mech.boiler.efficiency", be.getTotalSteamOutput())
                                                .withStyle(ChatFormatting.DARK_AQUA)
                                ).build()),
                                smallLabel().bind(DataBindingBuilder.componentS2C(() -> {
                                    int burnTicks = be.getFuelBurnTimeRemaining();
                                    return Component.translatable("gui.poly_mech.boiler.burn_time", burnTicks / 20)
                                            .withStyle(ChatFormatting.GOLD);
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

        // === 根容器：主面板 + 书签按钮（绝对定位叠加）+ 悬浮面板 ===
        var root = new UIElement();
        root.layout(l -> l.width(176).height(166));
        root.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_BASE).setSprite(0, 0, 195, 136).setBorder(4)));
        root.addChild(mainPanel);

        // 大机器不需要面配置，仅添加电源键
        root.addChild(powerBtn);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }
}
