package com.mss.polymech.client.gui.common;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.mss.polymech.Polymech;
import com.mss.polymech.machine.SideConfig;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器 UI 基类模板 — 提供所有机器共用的 GUI 组件。
 * <p>
 * 通用组件包括：
 * <ul>
 *   <li>电源键（enable/disable 切换按钮）— 右侧书签样式</li>
 *   <li>面配置 tab — 左侧书签样式，点击打开悬浮面配置面板</li>
 *   <li>悬浮面配置面板 — Mekanism 风格可拖动悬浮窗</li>
 * </ul>
 * <p>
 * 子类可通过 {@link #createPowerButton} 和 {@link #createSideConfigTab} 方法
 * 获取这些通用组件，并按需添加到自己的 UI 布局中。
 * </p>
 * <p>
 * 注意：悬浮面配置面板必须在所有其他子元素之后添加到 root，以保证 z-order 最上层。
 * 使用 {@link #addSideConfigComponents} 方法可自动处理 z-order。
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

    // ==================== 电源键组件 ====================

    /**
     * 创建电源键按钮（右侧书签样式）。
     * <p>
     * 按钮位于主面板右侧，使用 holder_right 作为背景，
     * 内部显示 on/off 图标表示启用状态。
     * </p>
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
     *
     * @param btn       电源键按钮
     * @param enabled   是否启用
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
     * <p>
     * 按钮位于主面板左侧，使用 holder_left 作为背景，
     * 内部显示面配置图标。点击后会切换悬浮面板的显示/隐藏。
     * </p>
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
     *
     * @param pos       方块位置
     * @param config    面配置数据
     * @param onClose   关闭回调
     * @return 悬浮面板 UIElement
     */
    protected static UIElement createFloatingSideConfigPanel(BlockPos pos, SideConfig config, Runnable onClose) {
        UIElement panel = FloatingSideConfigPanel.create(pos, config, onClose);
        panel.setVisible(false);
        return panel;
    }

    /**
     * 将面配置相关组件添加到 root。
     * <p>
     * 此方法会：
     * <ol>
     *   <li>添加面配置 tab 按钮</li>
     *   <li>创建并添加悬浮面板（最后添加，确保 z-order 最上层）</li>
     * </ol>
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
