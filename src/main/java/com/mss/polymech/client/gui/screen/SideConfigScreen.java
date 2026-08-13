package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 面配置屏幕 — 像素级复刻 Mekanism GuiSideConfiguration（156x135）。
 * <p>
 * 布局对照（全部坐标相对窗口左上角，取自 Mek 1.21.x 原版源码）：
 * <pre>
 *   (-3,-3)  shadow 阴影 162x141（alpha 0.75，九宫格 border 4）
 *   (0,0)    base 背景 156x135（九宫格 border 4）
 *   (6,6)    关闭按钮 8x12（close.png）
 *   y=5      标题居中于 [12, 138]（TEXT_TITLE 0xFF404040）
 *   (38,25)  innerScreen 80x12（inner_screen.png，padding 5 / textScale 0.8 / 居中Y，
 *            文字 TEXT_SCREEN 0xFF3CFE9A："Eject: ON/OFF" 或 "No Eject"）
 *   左侧     3 个 26x26 类型 Tab（x=-26，y=2+28*i；holder_left 九宫格 border 4 + 类型色 tint；
 *            icon 18x18 在 (-21, y+4)；选中 Tab 隐藏）
 *   (136,6)  自动弹出按钮 14x14（auto_eject.png）
 *   (136,95) 清除面按钮 14x14（clear_sides.png；左键 next / 右键 previous / Shift→全清）
 *   面按钮   22x22（button.png 九宫格 slice 20x20 border 4 + DataType 色 HSV 调整 tint）：
 *            TOP(67,46) FRONT(67,69) RIGHT(90,69) LEFT(44,69) BACK(44,92) BOTTOM(67,92)
 *   y=120   "Slots" 居中（TEXT_SUBHEADING 0xFF787878）
 * </pre>
 * </p>
 */
public class SideConfigScreen extends ModularUIScreen {

    // ==================== 素材（Mekanism 原版贴图，已复制到本模组） ====================

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

    // ==================== 颜色（Mek SpecialColors / GuiConfigTypeTab 原值） ====================

    /** 标题文字色 */
    private static final int TEXT_TITLE = 0xFF404040;
    /** 小标题文字色（"Slots"） */
    private static final int TEXT_SUBHEADING = 0xFF787878;
    /** innerScreen 屏幕文字色 */
    private static final int TEXT_SCREEN = 0xFF3CFE9A;
    /** 类型 Tab 颜色 */
    private static final int TAB_ENERGY_CONFIG = 0xFF59C15F;
    private static final int TAB_ITEM_CONFIG = 0xFFCFCFCF;
    private static final int TAB_FLUID_CONFIG = 0xFF366BD0;
    /** shadow 渲染 alpha（0.75） */
    private static final int SHADOW_TINT = 0xBFFFFFFF;
    /** 按压变暗 tint */
    private static final int PRESSED_TINT = 0xFFE0E0E0;

    // ==================== 窗口尺寸（Mek GuiSideConfiguration） ====================

    private static final int WINDOW_WIDTH = 156;
    private static final int WINDOW_HEIGHT = 135;

    // ==================== 面按钮坐标（Mek addSideDataButton） ====================

    private static final int FACE_BTN_SIZE = 22;
    private static final int[] FACE_X = {67, 67, 90, 44, 44, 67}; // TOP FRONT RIGHT LEFT BACK BOTTOM
    private static final int[] FACE_Y = {46, 69, 69, 69, 92, 92};

    private final BlockPos pos;
    private final SideConfig config;
    private final net.minecraft.client.gui.screens.Screen previousScreen;

    public SideConfigScreen(BlockPos pos, SideConfig config) {
        this(pos, config, null);
    }

    public SideConfigScreen(BlockPos pos, SideConfig config,
                            net.minecraft.client.gui.screens.Screen previousScreen) {
        super(buildUI(pos, config, previousScreen),
                Component.translatable("gui.poly_mech.side_config.title"));
        this.pos = pos;
        this.config = config;
        this.previousScreen = previousScreen;
    }

    // ==================== UI 构建 ====================

    private static ResourceLocation tex(String path) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/gui/" + path);
    }

    private static ModularUI buildUI(BlockPos pos, SideConfig config,
                                     net.minecraft.client.gui.screens.Screen previousScreen) {
        // 当前选中类型（Mek GuiSideConfiguration.currentType）
        CapabilityType[] state = {CapabilityType.ENERGY};

        // 根容器 156x135
        var root = new UIElement();
        root.layout(l -> l.width(WINDOW_WIDTH).height(WINDOW_HEIGHT));

        // 阴影（Mek: relativeX-3, relativeY-3, 162x141, alpha 0.75, 九宫格 border 4）
        var shadow = new UIElement()
                .layout(l -> l.width(WINDOW_WIDTH + 6).height(WINDOW_HEIGHT + 6)
                        .positionType(TaffyPosition.ABSOLUTE).left(-3).top(-3));
        shadow.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_SHADOW).setSprite(0, 0, 256, 256).setBorder(4).setColor(SHADOW_TINT)));
        shadow.setAllowHitTest(false);
        root.addChild(shadow);

        // 背景 base（Mek: renderBackgroundTexture 九宫格 border 4）
        var base = new UIElement()
                .layout(l -> l.width(WINDOW_WIDTH).height(WINDOW_HEIGHT)
                        .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        base.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_BASE).setSprite(0, 0, 256, 256).setBorder(4)));
        base.setAllowHitTest(false);
        root.addChild(base);

        // 内容层（交互后整体重建）
        var content = new UIElement().layout(l -> l.width(WINDOW_WIDTH).height(WINDOW_HEIGHT)
                .positionType(TaffyPosition.ABSOLUTE).left(0).top(0));
        root.addChild(content);

        Runnable[] rebuildRef = new Runnable[1];
        rebuildRef[0] = () -> {
            content.clearAllChildren();
            buildContent(content, pos, config, state, previousScreen, rebuildRef[0]);
        };
        buildContent(content, pos, config, state, previousScreen, rebuildRef[0]);

        return ModularUI.of(UI.of(root,
                StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    /**
     * 构建内容层 — 像素级复刻 Mek GuiSideConfiguration 全部元素。
     */
    private static void buildContent(UIElement content, BlockPos pos, SideConfig config, CapabilityType[] state,
                                     net.minecraft.client.gui.screens.Screen previousScreen, Runnable rebuild) {
        CapabilityType type = state[0];

        // ===== 标题（Mek drawTitleText: 居中于 [12, 138] y=5, TEXT_TITLE） =====
        var title = new Label()
                .setText(Component.translatable("gui.poly_mech.side_config.config_type",
                        Component.translatable(typeKey(type))))
                .textStyle(s -> s.textColor(TEXT_TITLE).textAlignHorizontal(Horizontal.CENTER))
                .layout(l -> l.width(WINDOW_WIDTH - 30).positionType(TaffyPosition.ABSOLUTE).left(12).top(5));
        content.addChild(title);

        // ===== 关闭按钮（Mek GuiCloseButton: (6,6) 8x12 close.png） =====
        var closeBtn = new Button().noText();
        closeBtn.buttonStyle(s -> s.baseTexture(SpriteTexture.of(TEX_CLOSE).setSprite(0, 0, 12, 12))
                .hoverTexture(SpriteTexture.of(TEX_CLOSE).setSprite(0, 0, 12, 12))
                .pressedTexture(SpriteTexture.of(TEX_CLOSE).setSprite(0, 0, 12, 12).setColor(PRESSED_TINT)));
        closeBtn.layout(l -> l.width(8).height(12).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(6).top(6));
        closeBtn.setOnClick(e -> Minecraft.getInstance().setScreen(previousScreen));
        content.addChild(closeBtn);

        // ===== innerScreen（Mek: (38,25) 80x12, padding 5, textScale 0.8, centerY） =====
        var innerScreen = new UIElement()
                .layout(l -> l.width(80).height(12).positionType(TaffyPosition.ABSOLUTE).left(38).top(25));
        innerScreen.style(s -> s.backgroundTexture(
                SpriteTexture.of(TEX_INNER_SCREEN).setSprite(0, 0, 256, 256).setBorder(4)));
        innerScreen.addChild(innerText(config, type));
        content.addChild(innerScreen);

        // ===== 类型 Tab 列（Mek: x=-26, y=2+28*i, 26x26, 选中隐藏） =====
        for (int i = 0; i < CapabilityType.values().length; i++) {
            content.addChild(configTab(pos, config, state, CapabilityType.values()[i], i, rebuild));
        }

        // ===== 自动弹出按钮（Mek: (136,6) 14x14 auto_eject.png） =====
        var ejectBtn = new Button().noText();
        ejectBtn.buttonStyle(s -> s.baseTexture(SpriteTexture.of(TEX_AUTO_EJECT))
                .hoverTexture(SpriteTexture.of(TEX_AUTO_EJECT))
                .pressedTexture(SpriteTexture.of(TEX_AUTO_EJECT).setColor(PRESSED_TINT)));
        ejectBtn.layout(l -> l.width(14).height(14).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(136).top(6));
        ejectBtn.setOnClick(e -> {
            boolean newEject = !config.isAutoEject(type);
            config.setAutoEject(type, newEject);
            PacketDistributor.sendToServer(new AutoEjectPacket(pos, capEnum(type), newEject));
            rebuild.run();
        });
        ejectBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
            List<Component> tips = new ArrayList<>();
            tips.add(Component.translatable("gui.poly_mech.side_config.auto_eject").withStyle(ChatFormatting.GRAY));
            if (!canEject(config, type)) {
                tips.add(Component.translatable("gui.poly_mech.side_config.cannot_eject")
                        .withStyle(ChatFormatting.DARK_RED));
            }
            event.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });
        content.addChild(ejectBtn);

        // ===== 清除面按钮（Mek: (136,95) 14x14 clear_sides.png） =====
        var clearBtn = new Button().noText();
        clearBtn.buttonStyle(s -> s.baseTexture(SpriteTexture.of(TEX_CLEAR_SIDES))
                .hoverTexture(SpriteTexture.of(TEX_CLEAR_SIDES))
                .pressedTexture(SpriteTexture.of(TEX_CLEAR_SIDES).setColor(PRESSED_TINT)));
        clearBtn.layout(l -> l.width(14).height(14).paddingAll(0)
                .positionType(TaffyPosition.ABSOLUTE).left(136).top(95));
        clearBtn.setOnClick(e -> batchApply(pos, config, type, SideIO::next, e.isShiftDown(), rebuild));
        // 右键：previous 循环
        clearBtn.addEventListener(UIEvents.MOUSE_DOWN, (UIEvent event) -> {
            if (event.button == 1) {
                batchApply(pos, config, type, SideIO::previous, event.isShiftDown(), rebuild);
            }
        });
        clearBtn.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
            List<Component> tips = new ArrayList<>();
            if (net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
                tips.add(Component.translatable("gui.poly_mech.side_config.clear_all").withStyle(ChatFormatting.GRAY));
            } else {
                tips.add(Component.translatable("gui.poly_mech.side_config.clear").withStyle(ChatFormatting.GRAY));
                tips.add(Component.translatable("gui.poly_mech.side_config.increment").withStyle(ChatFormatting.DARK_GRAY));
            }
            event.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });
        content.addChild(clearBtn);

        // ===== 6 个面按钮（Mek SideDataButton 22x22） =====
        Direction[] faces = {Direction.UP, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.DOWN};
        for (int i = 0; i < faces.length; i++) {
            content.addChild(faceBtn(pos, config, type, faces[i], FACE_X[i], FACE_Y[i], rebuild));
        }

        // ===== "Slots" 标签（Mek drawScrollingString: y=120 居中, TEXT_SUBHEADING） =====
        var slotsLabel = new Label()
                .setText(Component.translatable("gui.poly_mech.side_config.bottom_label"))
                .textStyle(s -> s.textColor(TEXT_SUBHEADING).textAlignHorizontal(Horizontal.CENTER))
                .layout(l -> l.width(WINDOW_WIDTH).positionType(TaffyPosition.ABSOLUTE).left(0).top(120));
        content.addChild(slotsLabel);
    }

    // ==================== 类型 Tab ====================

    private static UIElement configTab(BlockPos pos, SideConfig config, CapabilityType[] state,
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
        // Mek: 当前选中的 Tab 隐藏
        tab.setVisible(tabType != state[0]);
        tab.setOnClick(e -> {
            state[0] = tabType;
            rebuild.run();
        });
        // icon 18x18（Mek GuiInsetElement: getButtonX=x+4+(left?1:-1), getButtonY=y+4）
        var icon = new UIElement()
                .layout(l -> l.width(18).height(18).positionType(TaffyPosition.ABSOLUTE).left(5).top(4));
        icon.style(s -> s.backgroundTexture(SpriteTexture.of(tabIcon(tabType))));
        icon.setAllowHitTest(false);
        tab.addChild(icon);
        return tab;
    }

    // ==================== 面按钮 ====================

    /**
     * 单个面按钮 — 22x22，button.png 三态纹理（行1 normal / 行2 hovered）+ DataType 色 HSV 调整 tint。
     * 左键 next（Shift→NONE）、右键 previous，无字母文字（Mek SideDataButton 原样）。
     */
    private static UIElement faceBtn(BlockPos pos, SideConfig config, CapabilityType type,
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
            applyFace(pos, config, type, face, target, rebuild);
        });
        // 右键：previous（Mek MekClickType.RIGHT）
        btn.addEventListener(UIEvents.MOUSE_DOWN, (UIEvent event) -> {
            if (event.button == 1) {
                applyFace(pos, config, type, face, io.previous(), rebuild);
            }
        });
        // tooltip：方向名 + IO 名（带 DataType 色）
        btn.addEventListener(UIEvents.HOVER_TOOLTIPS, (UIEvent event) -> {
            List<Component> tips = new ArrayList<>();
            tips.add(Component.translatable("gui.poly_mech.side_config.face." + face.getSerializedName())
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(": "))
                    .append(Component.translatable("gui.poly_mech.side_config." + io.name().toLowerCase())
                            .withStyle(ioFormatting(io))));
            event.hoverTooltips = new HoverTooltips(tips, null, null, null);
        });
        return btn;
    }

    private static void applyFace(BlockPos pos, SideConfig config, CapabilityType type,
                                  Direction face, SideIO target, Runnable rebuild) {
        config.setConfig(type, face, target);
        PacketDistributor.sendToServer(new SideConfigPacket(pos, capEnum(type), face, ioEnum(target)));
        rebuild.run();
    }

    // ==================== 批量清除（Mek getTargetType + PacketBatchConfiguration） ====================

    /**
     * Mek 语义：Shift → 所有类型全部面 NONE；否则找当前类型所有面的共同 IO，
     * 一致时取 next/previous，不一致则 NONE（不变更）。
     */
    private static void batchApply(BlockPos pos, SideConfig config, CapabilityType type,
                                   java.util.function.UnaryOperator<SideIO> shift, boolean shiftDown,
                                   Runnable rebuild) {
        SideIO target;
        boolean allTypes = false;
        if (shiftDown) {
            target = SideIO.NONE;
            allTypes = true;
        } else {
            SideIO common = null;
            boolean consistent = true;
            for (Direction dir : Direction.values()) {
                SideIO cur = config.getConfig(type, dir);
                if (common == null) {
                    common = cur;
                } else if (common != cur) {
                    consistent = false;
                    break;
                }
            }
            target = consistent && common != null ? shift.apply(common) : SideIO.NONE;
        }
        if (allTypes) {
            config.setAllConfigAllTypes(target);
        } else {
            config.setAllConfig(type, target);
        }
        PacketDistributor.sendToServer(new BatchConfigPacket(pos, capEnum(type), ioEnum(target), allTypes));
        rebuild.run();
    }

    // ==================== 辅助 ====================

    /** innerScreen 文字（Mek: canEject ? "Eject: ON/OFF" : "No Eject"，TEXT_SCREEN 色，textScale 0.8，居中Y） */
    private static UIElement innerText(SideConfig config, CapabilityType type) {
        Component text;
        if (canEject(config, type)) {
            text = Component.translatable("gui.poly_mech.side_config.eject",
                    Component.translatable(config.isAutoEject(type)
                            ? "gui.poly_mech.side_config.eject_on"
                            : "gui.poly_mech.side_config.eject_off"));
        } else {
            text = Component.translatable("gui.poly_mech.side_config.no_eject");
        }
        return new Label()
                .setText(text)
                .textStyle(s -> s.textColor(TEXT_SCREEN).fontSize(8.0f).textAlignVertical(Vertical.CENTER))
                .layout(l -> l.width(80).height(12).paddingAll(5));
    }

    /** 该类型是否可自动弹出（至少一个面配置为 OUT） */
    private static boolean canEject(SideConfig config, CapabilityType type) {
        for (Direction dir : Direction.values()) {
            if (config.getConfig(type, dir) == SideIO.OUT) {
                return true;
            }
        }
        return false;
    }

    private static String typeKey(CapabilityType type) {
        return switch (type) {
            case ENERGY -> "gui.poly_mech.side_config.tab_energy";
            case ITEM -> "gui.poly_mech.side_config.tab_item";
            case FLUID -> "gui.poly_mech.side_config.tab_fluid";
        };
    }

    private static ResourceLocation tabIcon(CapabilityType type) {
        return switch (type) {
            case ENERGY -> TEX_ICON_ENERGY;
            case ITEM -> TEX_ICON_ITEM;
            case FLUID -> TEX_ICON_FLUID;
        };
    }

    /** 类型 Tab 颜色（Mek SpecialColors.TAB_*_CONFIG） */
    private static int tabColor(CapabilityType type) {
        return switch (type) {
            case ENERGY -> TAB_ENERGY_CONFIG;
            case ITEM -> TAB_ITEM_CONFIG;
            case FLUID -> TAB_FLUID_CONFIG;
        };
    }

    /** DataType 颜色（Mek EnumColor: NONE=GRAY / IN=DARK_RED / OUT=DARK_BLUE）经 HSV 调整后作为 tint */
    private static int dataTypeTint(SideIO io) {
        int[] rgb = switch (io) {
            case NONE -> new int[]{207, 207, 207};
            case IN -> new int[]{201, 7, 31};
            case OUT -> new int[]{54, 107, 208};
        };
        // Mek BasicColorButton: saturation - 0.1, value + 0.1
        float[] hsv = Color.RGBtoHSB(rgb[0], rgb[1], rgb[2], null);
        float s = Math.max(0f, hsv[1] - 0.1f);
        float v = Math.min(1f, hsv[2] + 0.1f);
        return 0xFF000000 | (Color.HSBtoRGB(hsv[0], s, v) & 0xFFFFFF);
    }

    /** IO → ChatFormatting（tooltip 文字色，对应 DataType 色） */
    private static ChatFormatting ioFormatting(SideIO io) {
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
