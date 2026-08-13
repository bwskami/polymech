package com.mss.polymech.client.gui.common;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.texture.SpriteTexture;
import com.mss.polymech.Polymech;
import com.mss.polymech.machine.SideConfig;
import com.mss.polymech.machine.SideConfig.CapabilityType;
import com.mss.polymech.machine.SideConfig.SideIO;
import com.mss.polymech.network.AutoEjectPacket;
import com.mss.polymech.network.BatchConfigPacket;
import com.mss.polymech.network.SideConfigPacket;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * 悬浮可拖动面配置面板 — Mekanism 风格悬浮窗。
 * <p>
 * 作为机器 UI 的子元素悬浮显示，可通过标题栏拖动。
 * 所有需要面配置功能的机器 UI 均可复用此组件。
 * </p>
 */
public class FloatingSideConfigPanel {

    private static final ResourceLocation TEX_SHADOW = tex("side_config/shadow.png");
    private static final ResourceLocation TEX_BASE = tex("common/base.png");
    private static final ResourceLocation TEX_INNER_SCREEN = tex("side_config/inner_screen.png");
    private static final ResourceLocation TEX_HOLDER_LEFT = tex("common/holder_left.png");
    private static final ResourceLocation TEX_BUTTON = tex("side_config/button.png");
    private static final ResourceLocation TEX_AUTO_EJECT = tex("side_config/auto_eject.png");
    private static final ResourceLocation TEX_CLEAR_SIDES = tex("side_config/clear_sides.png");
    private static final ResourceLocation TEX_CLOSE = tex("side_config/close.png");
    private static final ResourceLocation TEX_ICON_ENERGY = tex("common/energy.png");
    private static final ResourceLocation TEX_ICON_ITEM = tex("side_config/items.png");
    private static final ResourceLocation TEX_ICON_FLUID = tex("side_config/fluids.png");

    private static final int TEXT_TITLE = 0xFF404040;
    private static final int TEXT_SUBHEADING = 0xFF787878;
    private static final int TEXT_SCREEN = 0xFF3CFE9A;
    private static final int TAB_ENERGY_CONFIG = 0xFF59C15F;
    private static final int TAB_ITEM_CONFIG = 0xFFCFCFCF;
    private static final int TAB_FLUID_CONFIG = 0xFF366BD0;
    private static final int SHADOW_TINT = 0xBFFFFFFF;
    private static final int PRESSED_TINT = 0xFFE0E0E0;

    private static final int WINDOW_WIDTH = 156;
    private static final int WINDOW_HEIGHT = 135;
    private static final int FACE_BTN_SIZE = 22;
    private static final int[] FACE_X = {67, 67, 90, 44, 44, 67};
    private static final int[] FACE_Y = {46, 69, 69, 69, 92, 92};

    private static ResourceLocation tex(String path) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/" + path);
    }

    /**
     * 创建悬浮面板。
     *
     * @param pos           方块位置
     * @param config        面配置
     * @param onClose       关闭回调
     * @return 悬浮面板 UIElement
     */
    public static UIElement create(BlockPos pos, SideConfig config, Runnable onClose) {
        CapabilityType[] state = {CapabilityType.ENERGY};

        // 根容器 - 绝对定位，可拖动
        var root = new UIElement();
        root.layout(l -> l.width(WINDOW_WIDTH).height(WINDOW_HEIGHT)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));

        // 拖动偏移量（用 margin 实现，避免每次拖动触发 Taffy 全树布局重算）
        float[] dragOffset = {10, 10}; // [x, y]
        root.layout(l -> l.marginLeft(dragOffset[0]).marginTop(dragOffset[1]));

        // 阴影
        var shadow = new UIElement();
        shadow.layout(l -> l.width(WINDOW_WIDTH + 6).height(WINDOW_HEIGHT + 6)
                .positionType(TaffyPosition.ABSOLUTE).left(-3).top(-3));
        shadow.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_SHADOW).setSprite(0, 0, 256, 256).setBorder(4).setColor(SHADOW_TINT)));
        shadow.setAllowHitTest(false);
        root.addChild(shadow);

        // 背景
        var base = new UIElement();
        base.layout(l -> l.width(WINDOW_WIDTH).height(WINDOW_HEIGHT)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        base.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_BASE).setSprite(0, 0, 256, 256).setBorder(4)));
        base.setAllowHitTest(false);
        root.addChild(base);

        // 内容层
        var content = new UIElement();
        content.layout(l -> l.width(WINDOW_WIDTH).height(WINDOW_HEIGHT)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        root.addChild(content);

        Runnable[] rebuildRef = new Runnable[1];
        rebuildRef[0] = () -> {
            content.clearAllChildren();
            buildContent(content, pos, config, state, onClose, rebuildRef[0]);
        };
        buildContent(content, pos, config, state, onClose, rebuildRef[0]);

        // 标题栏拖动区域 - 最后添加以确保在最上层接收鼠标事件
        var dragHandle = new UIElement();
        dragHandle.layout(l -> l.width(WINDOW_WIDTH).height(18)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        dragHandle.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            // 只有直接点击 dragHandle（而非其子元素按钮）时才启动拖拽
            if (e.button == 0 && e.target == dragHandle) {
                root.startDrag(null, null);
            }
        });
        // DRAG_SOURCE_UPDATE 只派发给拖拽源（root），必须在 root 上监听
        root.addEventListener(UIEvents.DRAG_SOURCE_UPDATE, e -> {
            // 用 margin 偏移代替 layout 重设，避免 Taffy 全树重算导致卡顿
            dragOffset[0] += (float) e.deltaX;
            dragOffset[1] += (float) e.deltaY;
            root.layout(l -> l.marginLeft(dragOffset[0]).marginTop(dragOffset[1]));
        });

        // 关闭按钮 - 作为 dragHandle 子元素以接收点击
        var closeBtn = new Button().noText();
        closeBtn.buttonStyle(s -> s.baseTexture(SpriteTexture.of(TEX_CLOSE).setSprite(0, 0, 12, 12))
                .hoverTexture(SpriteTexture.of(TEX_CLOSE).setSprite(0, 0, 12, 12))
                .pressedTexture(SpriteTexture.of(TEX_CLOSE).setSprite(0, 0, 12, 12).setColor(PRESSED_TINT)));
        closeBtn.layout(l -> l.width(8).height(12).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(6).top(3));
        closeBtn.setOnClick(e -> onClose.run());
        dragHandle.addChild(closeBtn);

        // 自动弹出按钮 - 作为 dragHandle 子元素
        var autoEjectBtn = new Button().noText();
        autoEjectBtn.buttonStyle(s -> s.baseTexture(SpriteTexture.of(TEX_AUTO_EJECT).setSprite(0, 0, 14, 14))
                .hoverTexture(SpriteTexture.of(TEX_AUTO_EJECT).setSprite(0, 0, 14, 14))
                .pressedTexture(SpriteTexture.of(TEX_AUTO_EJECT).setSprite(0, 0, 14, 14).setColor(PRESSED_TINT)));
        autoEjectBtn.layout(l -> l.width(14).height(14).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(136).top(2));
        autoEjectBtn.setOnClick(e -> {
            boolean current = config.isAutoEject(state[0]);
            config.setAutoEject(state[0], !current);
            PacketDistributor.sendToServer(new AutoEjectPacket(pos, capEnum(state[0]), !current));
            rebuildRef[0].run();
        });
        dragHandle.addChild(autoEjectBtn);

        root.addChild(dragHandle);

        return root;
    }

    private static void buildContent(UIElement content, BlockPos pos, SideConfig config,
                                     CapabilityType[] state, Runnable onClose, Runnable rebuild) {
        CapabilityType type = state[0];

        // 标题
        var title = new Label()
                .setText(Component.translatable("gui.poly_mech.side_config.config_type",
                        Component.translatable(typeKey(type))))
                .textStyle(s -> s.textColor(TEXT_TITLE))
                .layout(l -> l.width(WINDOW_WIDTH - 30).positionType(TaffyPosition.ABSOLUTE).left(12).top(5));
        title.setAllowHitTest(false); // 不拦截拖动
        content.addChild(title);

        // innerScreen
        var innerScreen = new UIElement();
        innerScreen.layout(l -> l.width(80).height(12).positionType(TaffyPosition.ABSOLUTE).left(38).top(25));
        innerScreen.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_INNER_SCREEN).setSprite(0, 0, 256, 256).setBorder(4)));
        innerScreen.addChild(createInnerText(config, type));
        content.addChild(innerScreen);

        // 类型 Tab
        CapabilityType[] types = {CapabilityType.ENERGY, CapabilityType.ITEM, CapabilityType.FLUID};
        for (int i = 0; i < types.length; i++) {
            content.addChild(createConfigTab(pos, config, state, types[i], i, rebuild));
        }

        // 清除面按钮
        var clearBtn = new Button().noText();
        clearBtn.buttonStyle(s -> s.baseTexture(SpriteTexture.of(TEX_CLEAR_SIDES).setSprite(0, 0, 14, 14))
                .hoverTexture(SpriteTexture.of(TEX_CLEAR_SIDES).setSprite(0, 0, 14, 14))
                .pressedTexture(SpriteTexture.of(TEX_CLEAR_SIDES).setSprite(0, 0, 14, 14).setColor(PRESSED_TINT)));
        clearBtn.layout(l -> l.width(14).height(14).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(136).top(95));
        clearBtn.setOnClick(e -> {
            if (e.isShiftDown()) {
                // 清除所有类型所有面
                for (CapabilityType t : CapabilityType.values()) {
                    config.setAllConfig(t, SideIO.NONE);
                }
                PacketDistributor.sendToServer(new BatchConfigPacket(pos, capEnum(type), ioEnum(SideIO.NONE), true));
            } else {
                // 清除当前类型所有面
                config.setAllConfig(type, SideIO.NONE);
                PacketDistributor.sendToServer(new BatchConfigPacket(pos, capEnum(type), ioEnum(SideIO.NONE), false));
            }
            rebuild.run();
        });
        clearBtn.addEventListener(UIEvents.MOUSE_DOWN, (UIEvent event) -> {
            if (event.button == 1) {
                // 右键：清除所有类型所有面
                for (CapabilityType t : CapabilityType.values()) {
                    config.setAllConfig(t, SideIO.NONE);
                }
                PacketDistributor.sendToServer(new BatchConfigPacket(pos, capEnum(type), ioEnum(SideIO.NONE), true));
                rebuild.run();
            }
        });
        content.addChild(clearBtn);

        // 面按钮
        Direction[] faces = {Direction.UP, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.DOWN};
        for (int i = 0; i < faces.length; i++) {
            content.addChild(createFaceBtn(pos, config, type, faces[i], FACE_X[i], FACE_Y[i], rebuild));
        }

        // "Slots" 标签
        var slotsLabel = new Label()
                .setText(Component.translatable("gui.poly_mech.side_config.bottom_label"))
                .textStyle(s -> s.textColor(TEXT_SUBHEADING))
                .layout(l -> l.width(WINDOW_WIDTH).positionType(TaffyPosition.ABSOLUTE).left(0).top(120));
        content.addChild(slotsLabel);
    }

    private static UIElement createInnerText(SideConfig config, CapabilityType type) {
        String text;
        if (config.isAutoEject(type)) {
            text = "Eject: ON";
        } else {
            text = "No Eject";
        }
        var label = new Label()
                .setText(Component.literal(text))
                .textStyle(s -> s.textColor(TEXT_SCREEN))
                .layout(l -> l.width(80).height(12));
        return label;
    }

    private static UIElement createConfigTab(BlockPos pos, SideConfig config, CapabilityType[] state,
                                             CapabilityType tabType, int index, Runnable rebuild) {
        int tabY = 2 + 28 * index;
        int tint = tabColor(tabType);

        var tab = new Button().noText();
        tab.buttonStyle(s -> s.baseTexture(
                        SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4).setColor(tint))
                .hoverTexture(SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4).setColor(tint))
                .pressedTexture(SpriteTexture.of(TEX_HOLDER_LEFT).setSprite(0, 0, 26, 9).setBorder(4).setColor(tint)));
        tab.layout(l -> l.width(26).height(26).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(-26).top(tabY));
        tab.setVisible(tabType != state[0]);
        tab.setOnClick(e -> {
            state[0] = tabType;
            rebuild.run();
        });

        var icon = new UIElement()
                .layout(l -> l.width(18).height(18).positionType(TaffyPosition.ABSOLUTE).left(5).top(4));
        icon.style(s -> s.backgroundTexture(SpriteTexture.of(tabIcon(tabType))));
        icon.setAllowHitTest(false);
        tab.addChild(icon);
        return tab;
    }

    private static UIElement createFaceBtn(BlockPos pos, SideConfig config, CapabilityType type,
                                           Direction face, int x, int y, Runnable rebuild) {
        SideIO io = config.getConfig(type, face);
        int tint = dataTypeTint(io);

        var btn = new Button().noText();
        btn.buttonStyle(s -> s.baseTexture(
                        SpriteTexture.of(TEX_BUTTON).setSprite(0, 20, 20, 20).setBorder(4).setColor(tint))
                .hoverTexture(SpriteTexture.of(TEX_BUTTON).setSprite(0, 40, 20, 20).setBorder(4).setColor(tint))
                .pressedTexture(SpriteTexture.of(TEX_BUTTON).setSprite(0, 40, 20, 20).setBorder(4).setColor(tint)));
        btn.layout(l -> l.width(FACE_BTN_SIZE).height(FACE_BTN_SIZE).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(x).top(y));
        btn.setOnClick(e -> {
            SideIO target = e.isShiftDown() ? SideIO.NONE : io.next();
            PacketDistributor.sendToServer(new SideConfigPacket(pos, capEnum(type), face, ioEnum(target)));
            config.setConfig(type, face, target);
            rebuild.run();
        });
        btn.addEventListener(UIEvents.MOUSE_DOWN, (UIEvent event) -> {
            if (event.button == 1) {
                SideIO target = io.previous();
                PacketDistributor.sendToServer(new SideConfigPacket(pos, capEnum(type), face, ioEnum(target)));
                config.setConfig(type, face, target);
                rebuild.run();
            }
        });
        btn.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
            List<Component> tips = new ArrayList<>();
            tips.add(Component.translatable("gui.poly_mech.side_config.face." + face.getSerializedName())
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(": "))
                    .append(Component.translatable("gui.poly_mech.side_config." + io.name().toLowerCase())
                            .withStyle(ioColor(io))));
            event.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });
        return btn;
    }

    private static String typeKey(CapabilityType type) {
        return switch (type) {
            case ENERGY -> "gui.poly_mech.side_config.tab_energy";
            case ITEM -> "gui.poly_mech.side_config.tab_item";
            case FLUID -> "gui.poly_mech.side_config.tab_fluid";
        };
    }

    private static int tabColor(CapabilityType type) {
        return switch (type) {
            case ENERGY -> TAB_ENERGY_CONFIG;
            case ITEM -> TAB_ITEM_CONFIG;
            case FLUID -> TAB_FLUID_CONFIG;
        };
    }

    private static ResourceLocation tabIcon(CapabilityType type) {
        return switch (type) {
            case ENERGY -> TEX_ICON_ENERGY;
            case ITEM -> TEX_ICON_ITEM;
            case FLUID -> TEX_ICON_FLUID;
        };
    }

    private static int dataTypeTint(SideIO io) {
        int[] rgb = switch (io) {
            case NONE -> new int[]{207, 207, 207};
            case IN -> new int[]{201, 7, 31};
            case OUT -> new int[]{54, 107, 208};
        };
        float[] hsv = java.awt.Color.RGBtoHSB(rgb[0], rgb[1], rgb[2], null);
        float s = Math.max(0f, hsv[1] - 0.1f);
        float v = Math.min(1f, hsv[2] + 0.1f);
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hsv[0], s, v) & 0xFFFFFF);
    }

    private static ChatFormatting ioColor(SideIO io) {
        return switch (io) {
            case NONE -> ChatFormatting.GRAY;
            case IN -> ChatFormatting.DARK_RED;
            case OUT -> ChatFormatting.BLUE;
        };
    }

    private static SideConfigPacket.CapabilityType capEnum(CapabilityType type) {
        return SideConfigPacket.CapabilityType.valueOf(type.name());
    }

    private static SideConfigPacket.SideIO ioEnum(SideIO io) {
        return SideConfigPacket.SideIO.valueOf(io.name());
    }
}
