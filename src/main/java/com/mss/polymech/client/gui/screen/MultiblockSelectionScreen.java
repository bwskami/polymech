package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.lowdragmc.lowdraglib2.gui.ui.styletemplate.Sprites;
import com.mss.polymech.Polymech;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

public class MultiblockSelectionScreen extends ModularUIScreen {

    private static final int SIDEBAR_WIDTH_PERCENT = 15;
    private static final int HEADER_HEIGHT = 32;
    private static final int TOGGLE_BUTTONS_HEIGHT = 24;
    private static final int MACHINE_CARD_WIDTH = 120;
    private static final int MACHINE_CARD_HEIGHT = 140;
    private static final int MACHINE_PROJECTION_HEIGHT = 96;
    private static final int CARD_GAP = 8;

    private static final String ID_HEADER_LABEL = "header_label";
    private static final String ID_CATEGORY_SCROLLER = "category_scroller";
    private static final String ID_CATEGORY_LABEL = "category_label";
    private static final String ID_MACHINE_GRID = "machine_grid";
    private static final String ID_BTN_VOLTAGE = "btn_voltage";
    private static final String ID_BTN_TYPE = "btn_type";

    private ClassifyMode classifyMode = ClassifyMode.BY_VOLTAGE;
    private String selectedCategory = "LV";
    private boolean refreshing = false;

    public MultiblockSelectionScreen() {
        super(buildUI(), Component.translatable("gui.poly_mech.multiblock_selection.title"));
    }

    @Override
    public void init() {
        super.init();
        refreshUI();
    }

    private static ModularUI buildUI() {
        var root = new UIElement();
        root.layout(l -> l.widthPercent(100).heightPercent(100).flexDirection(FlexDirection.COLUMN));
        root.addClass("panel_bg");

        var header = new UIElement();
        header.layout(l -> l.widthPercent(100).height(HEADER_HEIGHT).paddingHorizontal(8).flexDirection(FlexDirection.ROW));
        header.addClass("panel_bg");

        var closeBtn = new Button()
                .setText(Component.translatable("gui.poly_mech.multiblock_selection.close"))
                .setOnClick(e -> Minecraft.getInstance().setScreen(null))
                .layout(l -> l.height(20).width(40));

        var headerLabel = new Label()
                .setText(Component.literal(""))
                .setId(ID_HEADER_LABEL)
                .layout(l -> l.flex(1).marginLeft(16));

        header.addChildren(closeBtn, headerLabel);

        var mainContent = new UIElement();
        mainContent.layout(l -> l.widthPercent(100).flex(1).flexDirection(FlexDirection.ROW));

        var sidebar = buildSidebar();
        var contentArea = buildContentArea();

        mainContent.addChildren(sidebar, contentArea);
        root.addChildren(header, mainContent);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)));
    }

    private static UIElement buildSidebar() {
        var sidebar = new UIElement();
        sidebar.layout(l -> l.widthPercent(SIDEBAR_WIDTH_PERCENT).heightPercent(100).paddingAll(4).gapColumn(4));
        sidebar.addClass("panel_bg");

        var toggleRow = new UIElement();
        toggleRow.layout(l -> l.widthPercent(100).height(TOGGLE_BUTTONS_HEIGHT).gapAll(4).flexDirection(FlexDirection.ROW));

        var btnByVoltage = new Button()
                .setText(Component.translatable("gui.poly_mech.classify.by_voltage"))
                .setId(ID_BTN_VOLTAGE)
                .layout(l -> l.flex(1).heightPercent(100));

        var btnByType = new Button()
                .setText(Component.translatable("gui.poly_mech.classify.by_type"))
                .setId(ID_BTN_TYPE)
                .layout(l -> l.flex(1).heightPercent(100));

        toggleRow.addChildren(btnByVoltage, btnByType);

        var categoryScroller = new ScrollerView();
        categoryScroller.setId(ID_CATEGORY_SCROLLER);
        categoryScroller.layout(l -> l.widthPercent(100).flex(1));

        sidebar.addChildren(toggleRow, categoryScroller);
        return sidebar;
    }

    private static UIElement buildContentArea() {
        var area = new UIElement();
        area.layout(l -> l.flex(1).flex(1).paddingAll(8).gapColumn(8));

        var categoryLabel = new Label()
                .setText(Component.literal(""))
                .setId(ID_CATEGORY_LABEL)
                .layout(l -> l.widthPercent(100));

        var machineGridContainer = new UIElement();
        machineGridContainer.setId(ID_MACHINE_GRID);
        machineGridContainer.layout(l -> l.widthPercent(100).flex(1).flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).gapAll(CARD_GAP));

        area.addChildren(categoryLabel, machineGridContainer);
        return area;
    }

    private void refreshUI() {
        updateHeaderLabel();
        updateToggleButtons();
        updateCategoryList();
        updateMachineGrid();
    }

    private void updateHeaderLabel() {
        var label = modularUI.getElementById(ID_HEADER_LABEL);
        if (label instanceof Label l) {
            l.setText(getCurrentCategoryText());
        }
    }

    private void updateToggleButtons() {
        if (refreshing) return;
        refreshing = true;
        try {
            var btnVoltage = modularUI.getElementById(ID_BTN_VOLTAGE);
            var btnType = modularUI.getElementById(ID_BTN_TYPE);

            if (btnVoltage instanceof Button b) {
                b.setOnClick(e -> {
                    if (refreshing) return;
                    classifyMode = ClassifyMode.BY_VOLTAGE;
                    selectedCategory = getFirstCategory();
                    refreshUI();
                });
                b.buttonStyle(s -> s.baseTexture(classifyMode == ClassifyMode.BY_VOLTAGE ? Sprites.RECT_RD_LIGHT : Sprites.RECT_RD));
            }

            if (btnType instanceof Button b) {
                b.setOnClick(e -> {
                    if (refreshing) return;
                    classifyMode = ClassifyMode.BY_TYPE;
                    selectedCategory = getFirstCategory();
                    refreshUI();
                });
                b.buttonStyle(s -> s.baseTexture(classifyMode == ClassifyMode.BY_TYPE ? Sprites.RECT_RD_LIGHT : Sprites.RECT_RD));
            }
        } finally {
            refreshing = false;
        }
    }

    private void updateCategoryList() {
        var scroller = modularUI.getElementById(ID_CATEGORY_SCROLLER);
        if (scroller instanceof ScrollerView sv) {
            sv.viewContainer.clearAllChildren();
            for (String cat : getCategories()) {
                var btn = new Button()
                        .setText(Component.translatable(getCategoryTranslationKey(cat)))
                        .setOnClick(e -> {
                            selectedCategory = cat;
                            refreshUI();
                        })
                        .layout(l -> l.widthPercent(100).height(24));
                sv.viewContainer.addChild(btn);
            }
        }
    }

    private void updateMachineGrid() {
        var grid = modularUI.getElementById(ID_MACHINE_GRID);
        var catLabel = modularUI.getElementById(ID_CATEGORY_LABEL);

        if (grid instanceof UIElement g) {
            g.clearAllChildren();
            var machines = getMachinesForCategory(selectedCategory);

            if (catLabel instanceof Label l) {
                l.setText(Component.translatable("gui.poly_mech.multiblock_selection.category_info",
                        Component.translatable(getCategoryTranslationKey(selectedCategory)),
                        machines.size()));
            }

            for (MachineData machine : machines) {
                g.addChild(createMachineCard(machine));
            }
        }
    }

    private UIElement createMachineCard(MachineData machine) {
        var card = new UIElement();
        card.layout(l -> l.width(MACHINE_CARD_WIDTH).height(MACHINE_CARD_HEIGHT).paddingAll(4).gapColumn(4));
        card.addClass("panel_bg");

        var projection = new UIElement();
        projection.layout(l -> l.widthPercent(100).height(MACHINE_PROJECTION_HEIGHT));
        projection.addClass("panel_bg");

        var name = new Label()
                .setText(Component.translatable(machine.nameKey()))
                .layout(l -> l.widthPercent(100));

        card.addChildren(projection, name);
        card.addEventListener(UIEvents.MOUSE_DOWN, e -> {
            Polymech.LOGGER.info("Selected multiblock machine: {} ({})", machine.id(), machine.nameKey());
            Minecraft.getInstance().setScreen(null);
        });

        return card;
    }

    private Component getCurrentCategoryText() {
        String modeKey = classifyMode == ClassifyMode.BY_VOLTAGE
                ? "gui.poly_mech.classify.mode_voltage"
                : "gui.poly_mech.classify.mode_type";
        return Component.translatable("gui.poly_mech.multiblock_selection.header_label",
                Component.translatable(modeKey),
                Component.translatable(getCategoryTranslationKey(selectedCategory)));
    }

    private String getCategoryTranslationKey(String category) {
        String prefix = classifyMode == ClassifyMode.BY_VOLTAGE ? "gui.poly_mech.tier." : "gui.poly_mech.type.";
        return prefix + category;
    }

    private List<String> getCategories() {
        return switch (classifyMode) {
            case BY_VOLTAGE -> List.of("steam", "lv", "mv", "hv", "ev", "iv", "luv", "zpm", "uv", "uhv");
            case BY_TYPE -> List.of("chemical", "compression", "heat", "assembly", "recycling");
        };
    }

    private String getFirstCategory() {
        var cats = getCategories();
        return cats.isEmpty() ? "" : cats.get(0);
    }

    private List<MachineData> getMachinesForCategory(String category) {
        var allMachines = getAllMachines();
        return switch (classifyMode) {
            case BY_VOLTAGE -> allMachines.stream()
                    .filter(m -> m.voltageTier().equals(category))
                    .toList();
            case BY_TYPE -> allMachines.stream()
                    .filter(m -> m.machineType().equals(category))
                    .toList();
        };
    }

    private List<MachineData> getAllMachines() {
        return List.of(
                new MachineData("horizontal_steam_boiler", "block.poly_mech.horizontal_steam_boiler", "steam", "heat"),
                new MachineData("large_chemical_reactor", "gui.poly_mech.machine.large_chemical_reactor", "mv", "chemical"),
                new MachineData("implosion_compressor", "gui.poly_mech.machine.implosion_compressor", "hv", "compression"),
                new MachineData("pyrolyze_oven", "gui.poly_mech.machine.pyrolyze_oven", "lv", "heat"),
                new MachineData("electric_blast_furnace", "gui.poly_mech.machine.electric_blast_furnace", "mv", "heat"),
                new MachineData("vacuum_freezer", "gui.poly_mech.machine.vacuum_freezer", "hv", "heat"),
                new MachineData("assembly_line", "gui.poly_mech.machine.assembly_line", "iv", "assembly"),
                new MachineData("recycler", "gui.poly_mech.machine.recycler", "lv", "recycling")
        );
    }

    private enum ClassifyMode {
        BY_VOLTAGE,
        BY_TYPE
    }

    private record MachineData(String id, String nameKey, String voltageTier, String machineType) {}
}
