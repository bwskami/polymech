package com.mss.polymech.client.gui.cell;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.mss.polymech.item.FluidCellItem;
import com.mss.polymech.network.SetCellCapacityPacket;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 流体单元容量上限设置界面（Shift+右键手持单元打开）。
 * <p>
 * 数字输入框范围 [已储存流体量, 种类最大容量]：
 * 空单元可设 0 起，有液体的单元不能低于已储存量。
 * 确认后通过{@link SetCellCapacityPacket}提交服务端写入数据组件，
 * 并主动关闭界面。
 * </p>
 */
public final class FluidCellConfigUI {

    private FluidCellConfigUI() {}

    public static ModularUI create(HeldItemUIMenuType.HeldItemUIHolder holder) {
        ItemStack stack = holder.itemStack;
        int max = FluidCellItem.getMaxCapacity(stack);
        int stored = FluidCellItem.getFluid(stack).getAmount();
        int currentLimit = FluidCellItem.getCapacityLimit(stack);

        // === 数字输入框：范围 [已储存量, 种类最大容量] ===
        int minLimit = Math.max(0, stored);
        int maxLimit = Math.max(max, 1);
        var field = new TextField();
        field.setNumbersOnlyInt(minLimit, maxLimit);
        field.setText(String.valueOf(currentLimit));
        field.layout(l -> l.flex(1).height(16));

        // === 滑条：拖拽/点击轨道/滚轮快速调节，与输入框联动，始终显示 ===
        // 满装时范围退化为单点，用 +1 避免归一化除零（监听器内夹取回合法值）
        int sliderMax = maxLimit == minLimit ? minLimit + 1 : maxLimit;
        var slider = new Scroller.Horizontal();
        slider.setRange(minLimit, sliderMax);
        slider.setValue((float) currentLimit);
        slider.setOnValueChanged(v -> field.setText(
                String.valueOf((int) Mth.clamp(Math.round(v), minLimit, maxLimit))));
        slider.layout(l -> l.widthPercent(100));

        // === 确认按钮：提交服务端 ===
        var confirm = new Button()
                .setText(Component.translatable("gui.poly_mech.button.confirm"))
                .setOnClick(e -> {
                    String text = field.getRawText().replace(",", "").trim();
                    try {
                        int value = Integer.parseInt(text);
                        PacketDistributor.sendToServer(new SetCellCapacityPacket(holder.hand, value));
                        // 提交后主动关闭界面（数值未变化时组件不变，不能依赖自动关闭）
                        holder.player.closeContainer();
                    } catch (NumberFormatException ignored) {
                        // 非法输入忽略（输入框本身已限制为整数）
                    }
                })
                .buttonStyle(s -> s.baseTexture(Sprites.RECT_RD))
                .layout(l -> l.width(60).height(18));

        // === 取消按钮：直接关闭界面 ===
        var cancel = new Button()
                .setText(Component.translatable("gui.poly_mech.button.cancel"))
                .setOnClick(e -> holder.player.closeContainer())
                .buttonStyle(s -> s.baseTexture(Sprites.RECT_RD))
                .layout(l -> l.width(60).height(18));

        // === 主面板 ===
        var mainPanel = new UIElement();
        mainPanel.layout(l -> l.width(150).paddingAll(7).gapAll(4));
        mainPanel.addClass("panel_bg");
        mainPanel.addChildren(
                new Label().setText(Component.translatable("gui.poly_mech.fluid_cell.config_title")),
                new Label().setText(Component.translatable("gui.poly_mech.fluid_cell.stored", stored)),
                new Label().setText(Component.translatable("gui.poly_mech.fluid_cell.max_capacity", max)),
                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(4)).addChildren(
                        new Label().setText(Component.translatable("gui.poly_mech.fluid_cell.limit_label"))
                                .layout(l -> l.width(40).height(14)),
                        field
                )
        );
        mainPanel.addChild(slider);
        mainPanel.addChild(
                new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(4)).addChildren(confirm, cancel)
        );

        var root = new UIElement();
        root.layout(l -> l.paddingAll(7));
        root.addChild(mainPanel);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }
}
