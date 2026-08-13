package com.mss.polymech.client.gui.battery;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.inventory.InventorySlots;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ProgressBar;
import com.lowdragmc.lowdraglib2.gui.ui.data.FillDirection;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.mss.polymech.Polymech;
import com.mss.polymech.client.gui.common.AbstractMachineUI;
import com.mss.polymech.machine.production.BatteryBlockEntity;
import com.mss.polymech.machine.production.CreativeBatteryBlockEntity;
import com.mss.polymech.network.BatteryTogglePacket;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 蓄电池 UI — 像素级复刻 Mekanism EnergyCube GUI（176x166）。
 * <p>
 * 继承 {@link AbstractMachineUI} 以复用通用机器 UI 组件：
 * <ul>
 *   <li>电源键（右侧书签）— 继承自基类</li>
 *   <li>面配置 tab（左侧书签）— 继承自基类</li>
 *   <li>悬浮面配置面板 — 继承自基类</li>
 * </ul>
 * </p>
 * <p>
 * 布局对照（Mekanism GuiEnergyCube）：
 * <pre>
 *   (8,6)    标题
 *   (55,18)  能量 gauge 66x50（gauge_normal 九宫格背景）
 *   (176,137) 电源键书签（基类组件）
 *   (-26,6)  面配置 tab（基类组件）
 *   (-26,137) 能量 tab 26x26（蓄电池特有）
 *   (8,72)   "物品栏" 文字
 *   (8,84)   玩家背包 3x9 + 快捷栏
 * </pre>
 * </p>
 */
public class BatteryUI extends AbstractMachineUI {

    // ==================== 蓄电池特有素材 ====================

    private static final ResourceLocation TEX_GAUGE_NORMAL = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/gauge_normal.png");
    private static final ResourceLocation TEX_GAUGE_WIDE = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/gauge_wide.png");
    private static final ResourceLocation TEX_ENERGY = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/energy_fill.png");
    private static final ResourceLocation TEX_ENERGY_INFO = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/tab_energy_info.png");
    private static final ResourceLocation TEX_SLOT = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/slot_normal.png");

    // ==================== 构建 ====================

    public static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        BatteryBlockEntity be = (BatteryBlockEntity) Objects.requireNonNull(holder.player.level())
                .getBlockEntity(holder.pos);
        boolean isCreative = be instanceof CreativeBatteryBlockEntity;
        final var cfgPos = holder.pos.immutable();
        final var cfgConfig = be.getSideConfig();

        // ===== 根窗口 176x166：LDLib2 bordered_background 九宫格背景 =====
        var root = new UIElement();
        root.layout(l -> l.width(176).height(166));
        root.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_BASE).setSprite(0, 0, 195, 136).setBorder(4)));

        // ===== 标题 (8,6) =====
        var title = new Label()
                .setText(Component.translatable(
                        isCreative ? "block.poly_mech.creative_battery" : "block.poly_mech.battery"))
                .textStyle(s -> s.textColor(TITLE_COLOR))
                .layout(l -> l.width(90).positionType(TaffyPosition.ABSOLUTE).left(8).top(6));
        root.addChild(title);

        // ===== 能量 gauge (55,18) 66x50 =====
        var gaugePanel = new UIElement()
                .layout(l -> l.width(66).height(50).positionType(TaffyPosition.ABSOLUTE).left(55).top(18));
        gaugePanel.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_GAUGE_NORMAL).setSprite(0, 0, 5, 5).setBorder(2)));
        root.addChild(gaugePanel);

        // 能量填充：相对于gaugePanel内部 (1,1) 64x48
        var energyBar = new ProgressBar();
        energyBar.label.setText(Component.empty());
        energyBar.progressBarStyle(s -> s.fillDirection(FillDirection.DOWN_TO_UP).interpolate(true).interpolateStep(0.03f));
        energyBar.setRange(0, 100);
        energyBar.barContainer(c -> c.layout(l -> l.paddingAll(0))
                .style(s -> s.backgroundTexture(IGuiTexture.EMPTY)));
        energyBar.barBackground.style(s -> s.backgroundTexture(IGuiTexture.EMPTY));
        energyBar.bar(b -> b.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_ENERGY).setSprite(0, 0, 16, 16))));
        energyBar.bind(DataBindingBuilder.floatValS2C(
                () -> isCreative ? 100f : (float) be.getEnergyPercent()).build());
        energyBar.layout(l -> l.width(64).height(48).positionType(TaffyPosition.ABSOLUTE).left(1).top(1));
        gaugePanel.addChild(energyBar);

        // gauge_wide 覆盖层：相对于gaugePanel内部 (1,1)
        var gaugeOverlay = new UIElement()
                .layout(l -> l.width(64).height(48).positionType(TaffyPosition.ABSOLUTE).left(1).top(1));
        gaugeOverlay.style(s -> s.backgroundTexture(SpriteTexture.of(TEX_GAUGE_WIDE)));
        gaugeOverlay.setAllowHitTest(false);
        gaugeOverlay.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
            List<Component> tips = new ArrayList<>();
            if (isCreative) {
                tips.add(Component.translatable("gui.poly_mech.battery.energy",
                        "\u221E", "\u221E").withStyle(ChatFormatting.LIGHT_PURPLE));
            } else {
                tips.add(Component.translatable("gui.poly_mech.battery.energy",
                                be.getEnergyStored(), be.getMaxEnergy())
                        .withStyle(ChatFormatting.GREEN));
                tips.add(Component.translatable("gui.poly_mech.battery.voltage",
                                be.getVoltageTier().getName(), be.getRatedVoltage())
                        .withStyle(ChatFormatting.YELLOW));
                tips.add(Component.translatable("gui.poly_mech.battery.grid_voltage",
                                be.getCurrentGridVoltage())
                        .withStyle(ChatFormatting.GOLD));
            }
            event.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });
        gaugePanel.addChild(gaugeOverlay);

        // ===== 背包文字 (8,72) =====
        var invLabel = new Label()
                .setText(Component.translatable("container.inventory"))
                .textStyle(s -> s.textColor(TITLE_COLOR))
                .layout(l -> l.width(90).positionType(TaffyPosition.ABSOLUTE).left(8).top(72));
        root.addChild(invLabel);

        // ===== 玩家背包 (8,84)：3x9 槽 + 快捷栏 =====
        var invSlots = new InventorySlots();
        invSlots.layout(l -> l.width(162).height(76).positionType(TaffyPosition.ABSOLUTE).left(8).top(84));
        root.addChild(invSlots);

        // ===== 左侧能量 tab (-26,137) 26x26：点击切换 FE/J/EU 单位（蓄电池特有） =====
        final EnergyUnit[] unitState = {EnergyUnit.FE};
        Button energyTab = new Button();
        energyTab.noText();
        energyTab.buttonStyle(s -> s.baseTexture(
                        SpriteTexture.of(TEX_ENERGY_INFO).setSprite(0, 0, 26, 26))
                .hoverTexture(SpriteTexture.of(TEX_ENERGY_INFO).setSprite(0, 0, 26, 26))
                .pressedTexture(SpriteTexture.of(TEX_ENERGY_INFO).setSprite(0, 0, 26, 26).setColor(PRESSED_TINT)));
        energyTab.layout(l -> l.width(26).height(26).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(-26).top(137));
        // 左键：下一单位；右键：上一单位
        energyTab.setOnClick(e -> unitState[0] = unitState[0].next());
        energyTab.addEventListener(UIEvents.MOUSE_DOWN, (UIEvent event) -> {
            if (event.button == 1) {
                unitState[0] = unitState[0].previous();
            }
        });
        energyTab.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
            List<Component> tips = new ArrayList<>();
            EnergyUnit unit = unitState[0];
            if (isCreative) {
                tips.add(Component.literal("\u221E " + unit.suffix).withStyle(ChatFormatting.LIGHT_PURPLE));
                tips.add(Component.literal("\u221E " + unit.suffix + "/t").withStyle(ChatFormatting.GREEN));
            } else {
                tips.add(Component.translatable("gui.poly_mech.battery.energy_stored",
                                formatNumber(be.getEnergyStored() * unit.conversion), unit.suffix)
                        .withStyle(ChatFormatting.GREEN));
                tips.add(Component.translatable("gui.poly_mech.battery.input_rate_u",
                                formatNumber(be.getLastInputRate() * unit.conversion), unit.suffix)
                        .withStyle(ChatFormatting.AQUA));
                tips.add(Component.translatable("gui.poly_mech.battery.output_rate_u",
                                formatNumber(be.getLastOutputRate() * unit.conversion), unit.suffix)
                        .withStyle(ChatFormatting.GOLD));
            }
            tips.add(Component.translatable("gui.poly_mech.battery.energy_tab", unit.suffix)
                    .withStyle(ChatFormatting.GRAY));
            event.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });
        root.addChild(energyTab);

        // ===== 基类通用组件：电源键 + 面配置 tab + 悬浮面板 =====
        // 电源键（右侧书签）
        Button powerBtn = createPowerButton(cfgPos, be.isEnabled(), (pos, enabled) -> {
            PacketDistributor.sendToServer(
                    new BatteryTogglePacket(pos, BatteryTogglePacket.Action.TOGGLE_ENABLE));
        });
        root.addChild(powerBtn);

        // 面配置 tab（左侧书签）+ 悬浮面板
        // 注意：必须在所有其他子元素之后添加，以保证 z-order 最上层
        UIElement[] panelRef = {null};
        Button sideConfigTab = createSideConfigTab(panelRef);
        addSideConfigComponents(root, cfgPos, cfgConfig, sideConfigTab);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }

    // ==================== 辅助方法 ====================

    /** 能量单位：FE 为基准，J = FE×2.5，EU = FE÷4（Mek 换算比例） */
    private enum EnergyUnit {
        FE(1.0, "FE"),
        J(2.5, "J"),
        EU(0.25, "EU");

        private final double conversion;
        private final String suffix;

        EnergyUnit(double conversion, String suffix) {
            this.conversion = conversion;
            this.suffix = suffix;
        }

        EnergyUnit next() {
            return values()[(ordinal() + 1) % values().length];
        }

        EnergyUnit previous() {
            return values()[(ordinal() + values().length - 1) % values().length];
        }
    }

    /** 数字格式化：整数不带小数，否则保留 1 位 */
    private static String formatNumber(double v) {
        if (Double.isInfinite(v)) {
            return "\u221E";
        }
        return v == Math.floor(v) ? String.format("%.0f", v) : String.format("%.1f", v);
    }
}
