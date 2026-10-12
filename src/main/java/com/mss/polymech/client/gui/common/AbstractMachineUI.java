package com.mss.polymech.client.gui.common;

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
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.data.FillDirection;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.mss.polymech.Polymech;
import com.mss.polymech.machine.BaseIOBlockEntity;
import com.mss.polymech.machine.SideConfig;
import com.mss.polymech.machine.production.AbstractProcessingBlockEntity;
import com.mss.polymech.machine.production.AbstractTurbineGeneratorBlockEntity;
import com.mss.polymech.network.MachineTogglePacket;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 机器 UI 基类模板 — 提供所有机器共用的 GUI 组件与构建模板。
 * <p>
 * 通用组件包括：
 * <ul>
 *   <li>电源键（enable/disable 切换按钮）— 右侧书签样式</li>
 *   <li>面配置 tab — 左侧书签样式，点击打开悬浮面配置面板</li>
 *   <li>悬浮面配置面板 — Mekanism 风格可拖动悬浮窗</li>
 * </ul>
 * <p>
 * 加工机器的标准布局通过 {@link #buildProcessingUI(BlockUIMenuType.BlockUIHolder, ProcessingUIConfig)}
 * 模板方法构建，子类只需提供 {@link ProcessingUIConfig} 即可获得统一布局。
 * 锅炉等特殊布局的机器应直接继承本类并复用 {@link #createPowerButton} 等工具方法。
 * </p>
 */
public abstract class AbstractMachineUI {

    // ==================== 通用素材（所有机器共用） ====================

    /** 基础背景纹理（LDLib2 自带） */
    protected static final ResourceLocation TEX_BASE = ResourceLocation.fromNamespaceAndPath("ldlib2", "textures/gui/bordered_background.png");
    /** 左侧书签 holder */
    protected static final ResourceLocation TEX_HOLDER_LEFT = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/holder_left.png");
    /** 右侧书签 holder */
    protected static final ResourceLocation TEX_HOLDER_RIGHT = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/holder_right.png");
    /** 电源键开启图标 */
    protected static final ResourceLocation TEX_POWER_ON = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/on.png");
    /** 电源键关闭图标 */
    protected static final ResourceLocation TEX_POWER_OFF = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/off.png");
    /** 面配置图标 */
    protected static final ResourceLocation TEX_CONFIGURATION = ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/common/configuration.png");

    /** 标题文字颜色（深灰） */
    protected static final int TITLE_COLOR = 0xFF404040;
    /** 按钮按下时轻微变暗的 tint */
    protected static final int PRESSED_TINT = 0xFFE0E0E0;

    // ==================== 加工机器 UI 构建模板 ====================

    /**
     * 加工机器 UI 配置记录（所有加工机器共用同一布局模板）。
     * <p>
     * 包含模板所需的所有数据，子类负责从 {@link BlockUIMenuType.BlockUIHolder} 解析并填充此记录。
     * </p>
     *
     * @param machine      方块实体（加工机器）
     * @param itemHandler  物品处理器
     * @param inputSlots   输入槽位索引数组
     * @param outputSlots  输出槽位索引数组
     * @param tanks        流体储罐数组
     * @param title        UI 标题组件
     * @param pos          方块位置（用于网络包）
     */
    public record ProcessingUIConfig(
            AbstractProcessingBlockEntity machine,
            ItemStackHandler itemHandler,
            int[] inputSlots,
            int[] outputSlots,
            FluidTank[] tanks,
            Component title,
            BlockPos pos
    ) {}

    /**
     * 加工机器 UI 模板方法 — 构建标准加工机器界面。
     * <p>
     * 布局：标题 → 输入槽 | 进度条 | 输出槽 | 储罐 → 状态行 → 玩家背包 → 电源键
     * </p>
     *
     * @param holder BlockUIMenuType 持有者
     * @param config 布局配置
     * @return 完整的 ModularUI 实例
     */
    protected static ModularUI buildProcessingUI(BlockUIMenuType.BlockUIHolder holder, ProcessingUIConfig config) {
        var machine = config.machine();
        var itemHandler = config.itemHandler();

        // ===== 根窗口 176x166：主面板 + 九宫格背景 =====
        var root = new UIElement();
        root.layout(l -> l.width(176).height(166));
        root.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_BASE).setSprite(0, 0, 195, 136).setBorder(4)));

        // === 标题 ===
        root.addChild(new Label().setText(config.title())
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
        if (config.inputSlots().length > 0) {
            var inputCol = new UIElement().layout(l -> l.width(20).gapAll(2));
            for (int slot : config.inputSlots()) {
                inputCol.addChild(new ItemSlot().bind(itemHandler, slot));
            }
            machineRow.addChild(inputCol);
        }

        machineRow.addChild(progressBar);

        // 输出槽（竖排）
        if (config.outputSlots().length > 0) {
            var outputCol = new UIElement().layout(l -> l.width(20).gapAll(2));
            for (int slot : config.outputSlots()) {
                outputCol.addChild(new ItemSlot().bind(itemHandler, slot));
            }
            machineRow.addChild(outputCol);
        }

        // 储罐（横排）
        if (config.tanks().length > 0) {
            var tankRow = new UIElement().layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(2));
            for (int i = 0; i < config.tanks().length; i++) {
                final int index = i;
                tankRow.addChild(new FluidSlot().bind(config.tanks()[index], 0)
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

        // ===== 基类通用组件：电源键 =====
        Button powerBtn = createPowerButton(config.pos(), machine.isEnable(), (pos, enabled) -> {
            PacketDistributor.sendToServer(new MachineTogglePacket(pos));
        });
        root.addChild(powerBtn);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }

    /**
     * 状态行文本（加工机器/发电机通用）。
     */
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

    // ==================== 电源键组件 ====================

    /**
     * 创建电源键按钮（右侧书签样式）。
     *
     * @param pos           方块位置（用于发送网络包）
     * @param initialState  初始启用状态
     * @param onToggle      切换回调（参数为新的启用状态）
     * @param left          按钮左边界位置（默认 176）
     * @param top           按钮上边界位置（默认 137）
     * @return 电源键按钮，包含 on/off 图标子元素
     */
    protected static Button createPowerButton(BlockPos pos, boolean initialState, PowerToggleCallback onToggle, int left, int top) {
        final boolean[] enabledState = {initialState};

        Button enableBtn = new Button();
        enableBtn.noText();
        enableBtn.buttonStyle(s -> s.baseTexture(
                        SpriteTexture.of(TEX_HOLDER_RIGHT).setSprite(0, 0, 26, 9).setBorder(4))
                .hoverTexture(SpriteTexture.of(TEX_HOLDER_RIGHT).setSprite(0, 0, 26, 9).setBorder(4))
                .pressedTexture(SpriteTexture.of(TEX_HOLDER_RIGHT).setSprite(0, 0, 26, 9).setBorder(4)
                        .setColor(PRESSED_TINT)));
        enableBtn.layout(l -> l.width(26).height(26).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(left).top(top));

        enableBtn.setOnClick(e -> {
            enabledState[0] = !enabledState[0];
            onToggle.onToggle(pos, enabledState[0]);
            updatePowerIcon(enableBtn, enabledState[0]);
        });

        enableBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
            List<Component> tips = new ArrayList<>();
            tips.add(Component.translatable("gui.poly_mech.button." +
                            (enabledState[0] ? "disable" : "enable"))
                    .withStyle(enabledState[0] ? ChatFormatting.RED : ChatFormatting.GREEN));
            event.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });

        // 添加电源图标
        updatePowerIcon(enableBtn, enabledState[0]);

        return enableBtn;
    }

    /**
     * 创建电源键按钮（右侧书签样式），使用默认位置（176, 137）。
     */
    protected static Button createPowerButton(BlockPos pos, boolean initialState, PowerToggleCallback onToggle) {
        return createPowerButton(pos, initialState, onToggle, 176, 137);
    }

    /**
     * 更新电源键图标纹理。
     */
    protected static void updatePowerIcon(Button btn, boolean enabled) {
        btn.clearAllChildren();
        var powerIcon = new UIElement()
                .layout(l -> l.width(16).height(16).positionType(TaffyPosition.ABSOLUTE).left(3).top(5))
                .style(s -> s.backgroundTexture(SpriteTexture.of(
                        enabled ? TEX_POWER_ON : TEX_POWER_OFF)));
        btn.addChild(powerIcon);
    }

    /**
     * 电源键切换回调接口。
     */
    @FunctionalInterface
    protected interface PowerToggleCallback {
        void onToggle(BlockPos pos, boolean enabled);
    }

    // ==================== 面配置组件 ====================

    /**
     * 创建面配置 tab 按钮（左侧书签样式）。
     *
     * @param floatingPanelRef  悬浮面板引用数组（用于延迟绑定）
     * @return 面配置 tab 按钮
     */
    protected static Button createSideConfigTab(UIElement[] floatingPanelRef) {
        Button sideConfigTab = new Button();
        sideConfigTab.noText();
        sideConfigTab.buttonStyle(s -> s.baseTexture(
                        SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4))
                .hoverTexture(SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4))
                .pressedTexture(SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4)
                        .setColor(PRESSED_TINT)));
        sideConfigTab.layout(l -> l.width(26).height(26).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(-26).top(6));

        sideConfigTab.setOnClick(e -> {
            if (floatingPanelRef[0] != null && floatingPanelRef[0].isVisible()) {
                floatingPanelRef[0].setVisible(false);
            } else if (floatingPanelRef[0] != null) {
                floatingPanelRef[0].setVisible(true);
            }
        });

        // 添加面配置图标
        sideConfigTab.addChild(new UIElement()
                .layout(l -> l.width(18).height(18).positionType(TaffyPosition.ABSOLUTE).left(5).top(4))
                .style(s -> s.backgroundTexture(SpriteTexture.of(TEX_CONFIGURATION))));

        return sideConfigTab;
    }

    /**
     * 创建悬浮面配置面板。
     * <p>
     * 面板初始隐藏，需要在所有其他子元素之后添加到 root。
     * </p>
     */
    protected static UIElement createFloatingSideConfigPanel(BlockPos pos, SideConfig config, Runnable onClose) {
        UIElement panel = FloatingSideConfigPanel.create(pos, config, onClose);
        panel.setVisible(false);
        return panel;
    }

    /**
     * 将面配置相关组件添加到 root。
     * <p>
     * 注意：必须在所有其他子元素添加完毕后调用此方法！
     *
     * @param root          根容器
     * @param pos           方块位置
     * @param config        面配置数据
     * @param sideConfigTab 面配置 tab 按钮
     * @return 悬浮面板引用（可用于后续控制显示/隐藏）
     */
    protected static UIElement addSideConfigComponents(UIElement root, BlockPos pos, SideConfig config, Button sideConfigTab) {
        // 先添加 tab 按钮
        root.addChild(sideConfigTab);

        // 创建悬浮面板
        UIElement[] panelRef = {null};
        panelRef[0] = createFloatingSideConfigPanel(pos, config, () -> {
            if (panelRef[0] != null) {
                panelRef[0].setVisible(false);
            }
        });

        // 更新 tab 按钮的回调引用
        final UIElement[] finalPanelRef = panelRef;
        sideConfigTab.setOnClick(e -> {
            if (finalPanelRef[0] != null) {
                if (finalPanelRef[0].isVisible()) {
                    finalPanelRef[0].setVisible(false);
                } else {
                    finalPanelRef[0].setVisible(true);
                }
            }
        });

        // 最后添加悬浮面板，确保 z-order 最上层
        root.addChild(panelRef[0]);

        return panelRef[0];
    }
}
