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
 * 布局对照（Mekanism GuiEnergyCube）：
 * <pre>
 *   (8,6)    标题
 *   (17,35)  放电槽位样式按钮（模式切换，MINUS 图标）
 *   (143,35) 充电槽位样式按钮（启用开关，PLUS 图标）
 *   (55,18)  能量 gauge 66x50（gauge_normal 九宫格背景）
 *   (56,19)  能量填充 64x48（energy 纹理自底向上，DOWN_TO_UP）
 *   (56,19)  gauge_wide 覆盖层
 *   (-26,6)  侧配置 tab 26x26（holder_left + configuration 图标）
 *   (-26,137) 能量 tab 26x26（energy_info，点击切换 FE/J/EU 单位）
 *   (8,72)   "物品栏" 文字
 *   (8,84)   玩家背包 3x9 + 快捷栏（槽位透明）
 * </pre>
 * 全部坐标与素材均取自 Mekanism 1.21.x 原版 GUI。
 * </p>
 */
public class BatteryUI {

    // ==================== 素材（Mekanism 原版贴图，已复制到本模组） ====================

    private static final ResourceLocation TEX_BASE = ResourceLocation.fromNamespaceAndPath("ldlib2", "textures/gui/bordered_background.png");
    private static final ResourceLocation TEX_GAUGE_NORMAL = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/gauge_normal.png");
    private static final ResourceLocation TEX_GAUGE_WIDE = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/gauge_wide.png");
    private static final ResourceLocation TEX_ENERGY = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/energy_fill.png");
    private static final ResourceLocation TEX_HOLDER_LEFT = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/holder_left.png");
    private static final ResourceLocation TEX_HOLDER_RIGHT = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/holder_right.png");
    private static final ResourceLocation TEX_POWER_ON = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/on.png");
    private static final ResourceLocation TEX_POWER_OFF = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/off.png");
    private static final ResourceLocation TEX_CONFIGURATION = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/configuration.png");
    private static final ResourceLocation TEX_ENERGY_INFO = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/tab_energy_info.png");
    private static final ResourceLocation TEX_SLOT = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/slot_normal.png");
    private static final ResourceLocation TEX_SLOT_PLUS = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/battery/slot_overlay_plus.png");
    /** Mekanism 标题文字颜色（深灰） */
    private static final int TITLE_COLOR = 0xFF404040;
    /** 按钮按下时轻微变暗的 tint */
    private static final int PRESSED_TINT = 0xFFE0E0E0;

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

        // ===== 右侧电源键书签 tab (176,137) 26x26：holder_right + on/off 图标 =====
        // 普通电池可开关；创造电池始终启用（图标固定 on，点击无效果）
        final boolean[] enabledState = {be.isEnabled()};
        {
            Button enableBtn = new Button();
            enableBtn.noText();
            enableBtn.buttonStyle(s -> s.baseTexture(
                            SpriteTexture.of(TEX_HOLDER_RIGHT).setSprite(0, 0, 26, 9).setBorder(4))
                    .hoverTexture(SpriteTexture.of(TEX_HOLDER_RIGHT).setSprite(0, 0, 26, 9).setBorder(4))
                    .pressedTexture(SpriteTexture.of(TEX_HOLDER_RIGHT).setSprite(0, 0, 26, 9).setBorder(4)
                            .setColor(PRESSED_TINT)));
            enableBtn.layout(l -> l.width(26).height(26).paddingAll(0)
                    .positionType(TaffyPosition.ABSOLUTE).left(176).top(137));
            enableBtn.setOnClick(e -> {
                PacketDistributor.sendToServer(
                        new BatteryTogglePacket(cfgPos, BatteryTogglePacket.Action.TOGGLE_ENABLE));
                // 切换本地状态并更新图标
                enabledState[0] = !enabledState[0];
                updatePowerIcon(enableBtn, enabledState[0]);
            });
            enableBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
                List<Component> tips = new ArrayList<>();
                if (isCreative) {
                    tips.add(Component.translatable("gui.poly_mech.battery.creative_always_on")
                            .withStyle(ChatFormatting.LIGHT_PURPLE));
                } else {
                    tips.add(Component.translatable("gui.poly_mech.button." +
                                    (enabledState[0] ? "disable" : "enable"))
                            .withStyle(enabledState[0] ? ChatFormatting.RED : ChatFormatting.GREEN));
                    tips.add(Component.translatable("gui.poly_mech.battery.tooltip_enable")
                            .withStyle(ChatFormatting.GRAY));
                }
                event.hoverTooltips = new HoverTooltips(tips, null, null, null);
            });
            // 电源图标 16x16 在相对位置 (3,5)，根据启用状态切换 on/off
            final var powerIcon = new UIElement()
                    .layout(l -> l.width(16).height(16).positionType(TaffyPosition.ABSOLUTE).left(3).top(5))
                    .style(s -> s.backgroundTexture(SpriteTexture.of(
                            enabledState[0] ? TEX_POWER_ON : TEX_POWER_OFF)));
            enableBtn.addChild(powerIcon);
            root.addChild(enableBtn);
        }

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

        // ===== 左侧侧配置 tab (-26,6) 26x26（Mek GuiSideConfigurationTab: height=26, innerSize=18） =====
        Button sideConfigTab = new Button();
        sideConfigTab.noText();
        sideConfigTab.buttonStyle(s -> s.baseTexture(
                        SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4))
                .hoverTexture(SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4))
                .pressedTexture(SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4)
                        .setColor(PRESSED_TINT)));
        sideConfigTab.layout(l -> l.width(26).height(26).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(-26).top(6));
        // 预先创建悬浮面板（确保纹理在初始渲染时注册到 atlas），初始隐藏
        // 注意：floatingPanel 必须在所有其他子元素之后添加，以保证 z-order 最上层
        final UIElement[] floatingPanel = {null};
        floatingPanel[0] = FloatingSideConfigPanel.create(cfgPos, cfgConfig, () -> {
            floatingPanel[0].setVisible(false);
        });
        floatingPanel[0].setVisible(false);
        
        sideConfigTab.setOnClick(e -> {
            if (floatingPanel[0].isVisible()) {
                floatingPanel[0].setVisible(false);
            } else {
                floatingPanel[0].setVisible(true);
            }
        });
        // Mek GuiInsetElement: icon 18x18 在 getButtonX=x+4+(left?1:-1)=-21、getButtonY=y+4 → 相对 (5,4)
        sideConfigTab.addChild(new UIElement()
                .layout(l -> l.width(18).height(18).positionType(TaffyPosition.ABSOLUTE).left(5).top(4))
                .style(s -> s.backgroundTexture(SpriteTexture.of(TEX_CONFIGURATION))));
        root.addChild(sideConfigTab);

        // ===== 左侧能量 tab (-26,137) 26x26：点击切换 FE/J/EU 单位（Mek 能量单位切换） =====
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

        // 悬浮面板最后添加，确保 z-order 最上层，能遮盖所有书签按钮
        root.addChild(floatingPanel[0]);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }

    /** 槽位样式按钮：18x18 slot_normal 背景 + overlay 图标（Mek 槽位像素级复刻） */
    private static Button slotButton(ResourceLocation overlayTex, Pos pos) {
        Button btn = new Button();
        btn.noText();
        btn.addPreIcon(SpriteTexture.of(overlayTex).setSprite(0, 0, 18, 18));
        btn.buttonStyle(s -> s.baseTexture(SpriteTexture.of(TEX_SLOT).setSprite(0, 0, 18, 18))
                .hoverTexture(SpriteTexture.of(TEX_SLOT).setSprite(0, 0, 18, 18))
                .pressedTexture(SpriteTexture.of(TEX_SLOT).setSprite(0, 0, 18, 18).setColor(PRESSED_TINT)));
        btn.layout(l -> l.width(18).height(18).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(pos.x()).top(pos.y()));
        return btn;
    }

    /** 简易坐标 record（避免元组依赖） */
    private record Pos(int x, int y) {}

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

    /** 更新电源键图标纹理 */
    private static void updatePowerIcon(Button btn, boolean enabled) {
        // 清除旧的图标子元素，重新添加新的
        btn.clearAllChildren();
        var powerIcon = new UIElement()
                .layout(l -> l.width(16).height(16).positionType(TaffyPosition.ABSOLUTE).left(3).top(5))
                .style(s -> s.backgroundTexture(SpriteTexture.of(
                        enabled ? TEX_POWER_ON : TEX_POWER_OFF)));
        btn.addChild(powerIcon);
    }
}
