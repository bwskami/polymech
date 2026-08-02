package com.mss.polymech.client.gui.block;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.mss.polymech.machine.production.HorizontalSteamBoilerBlockEntity;
import com.mss.polymech.network.MachineTogglePacket;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;

public class HorizontalSteamBoilerUI {

    public static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        var be = (HorizontalSteamBoilerBlockEntity) Objects.requireNonNull(holder.player.level())
                .getBlockEntity(holder.pos);

        var itemHandler = be.getItemStackHandler();

        // 隐藏进度条默认 label
        var progressBar = new ProgressBar();
        progressBar.label.setText(Component.empty());

        // 隐藏温度进度条默认 label
        var tempBar = new ProgressBar();
        tempBar.label.setText(Component.empty());

        // 开关机按钮（书签标签风格，在面板外面右下角）
        var toggleBtn = new Button()
                .setText(Component.translatable(be.isEnable() ? "gui.poly_mech.button.disable" : "gui.poly_mech.button.enable"))
                .setOnClick(e -> PacketDistributor.sendToServer(new MachineTogglePacket(holder.pos)))
                .buttonStyle(s -> s.baseTexture(Sprites.RECT_RD))
                .layout(l -> l.width(20).height(20));

        // 主面板（带背景）
        var mainPanel = new UIElement();
        mainPanel.layout(l -> l.width(176).paddingAll(7).gapAll(4));
        mainPanel.addClass("panel_bg");

        mainPanel.addChildren(
                // 机器区域：左槽 | 中间(温度条+信息面板+进度条) | 右槽
                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4)).addChildren(
                        // 左侧：输入液体 + 燃料（纵向，无文字）
                        new UIElement().layout(l -> l.width(22).gapAll(14)).addChildren(
                                new ItemSlot().bind(itemHandler, 0),
                                new ItemSlot().bind(itemHandler, 1)
                        ),

                        // 中间区域：温度条+信息面板(横向) + 进度条(下方)
                        new UIElement().layout(l -> l.gapAll(2)).addChildren(
                                // 上行：温度条 + 信息面板
                                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(2)).addChildren(
                                        tempBar
                                                .setMaxValue(1000)
                                                .bind(DataBindingBuilder.floatValS2C(() -> (float) be.getTemperature()).build())
                                                .layout(l -> l.width(18).height(50)),
                                        new UIElement().layout(l -> l.width(72).gapAll(2).paddingAll(4)).addClass("panel_bg").addChildren(
                                                new Label().setText(Component.translatable("block.poly_mech.horizontal_steam_boiler")),
                                                new Label().bind(DataBindingBuilder.componentS2C(() -> {
                                                    String status = be.isEnable()
                                                            ? (be.getProgress() > 0 ? "§a工作中" : "§a运行中")
                                                            : "§c已停止";
                                                    return Component.literal(status);
                                                }).build()),
                                                new Label().bind(DataBindingBuilder.componentS2C(() ->
                                                        Component.literal("§e" + be.getTemperature() + "°C  §7" + be.getEfficiency() + "%")
                                                ).build())
                                        )
                                ),
                                // 下行：进度条（横跨温度条+信息面板宽度）
                                progressBar
                                        .setMaxValue(be.getMaxProgress() > 0 ? be.getMaxProgress() : 100)
                                        .bind(DataBindingBuilder.floatValS2C(() -> (float) be.getProgress()).build())
                                        .layout(l -> l.width(94).height(14))
                        ),

                        // 右侧：输出液体 + 灰烬（纵向，无文字）
                        new UIElement().layout(l -> l.width(22).gapAll(14)).addChildren(
                                new ItemSlot().bind(itemHandler, 2),
                                new ItemSlot().bind(itemHandler, 3)
                        )
                ),

                // 玩家物品栏
                new InventorySlots()
        );

        // 外层容器：固定宽度容纳面板+按钮
        var wrapper = new UIElement();
        wrapper.layout(l -> l.width(196).flexDirection(FlexDirection.ROW));

        // 按钮定位容器：COLUMN布局，用flex spacer把按钮推到底部
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
