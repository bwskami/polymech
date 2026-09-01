package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.mss.polymech.client.gui.widget.planet.SolarSystem;
import com.mss.polymech.client.gui.widget.planet.SolarSystemView;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.network.TeleportToPlanetPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 星际传送器界面。
 * <p>
 * 继承 {@link StarMapScreen} 的纯星图部分：全屏 3D 太阳系 + 顶栏。
 * 左键单击行星锁定目标，顶栏显示当前选择，点击“传送”后经服务端校验并跳转到对应维度。
 * 气态巨行星与恒星不可传送。
 * </p>
 */
public class TeleporterScreen extends StarMapScreen {

    private final Label selectionLabel;
    private final Button teleportButton;
    private int selectedPlanet;

    private TeleporterScreen(State s, Label selectionLabel, Button teleportButton) {
        super(s, Component.literal("星际传送器 / Teleporter"));
        this.selectionLabel = selectionLabel;
        this.teleportButton = teleportButton;
        this.selectedPlanet = s.view.getFocalIndex();

        s.view.setPlanetSelectListener(pi -> {
            this.selectedPlanet = pi;
            updateSelection(s.view, pi, selectionLabel);
        });
        updateSelection(s.view, this.selectedPlanet, selectionLabel);

        teleportButton.setOnClick(e -> {
            int pi = this.selectedPlanet;
            if (!PlanetDimensions.isTeleportable(pi)) {
                selectionLabel.setText(Component.literal("该星球不可传送"));
                return;
            }
            PacketDistributor.sendToServer(new TeleportToPlanetPacket(pi));
            Minecraft.getInstance().setScreen(null);
        });
    }

    public static void open() {
        Ui ui = buildUi();
        TeleporterScreen screen = new TeleporterScreen(ui.state(), ui.selectionLabel(), ui.teleportButton());
        Minecraft.getInstance().setScreen(screen);
    }

    /** M 键打开星图。 */
    @Override
    protected void onMKeyPressed() {
        StarMapScreen.open(view.getSystemIndex());
    }

    // ============================ 构建 ============================

    private record Ui(State state, Label selectionLabel, Button teleportButton) {
    }

    private static Ui buildUi() {
        Label selectionLabel = new Label();
        Button teleportButton = new Button()
                .setText(Component.literal("传送"));
        teleportButton.layout(l -> l.height(20).width(56));

        State s = buildState(
                Component.literal("星际传送器 / Teleporter"),
                Component.literal("左键单击星球选择目标 · 滚轮缩放 · 点击“传送”前往 · M 星图"),
                root -> new SolarSystemView(SolarSystem.createDefault(), node -> { }),
                selectionLabel,
                teleportButton);

        return new Ui(s, selectionLabel, teleportButton);
    }

    private static void updateSelection(SolarSystemView view, int planetIndex, Label label) {
        if (planetIndex < 0 || planetIndex >= view.getSolarSystem().size()) return;
        String name = view.getSolarSystem().get(planetIndex).name();
        if (PlanetDimensions.isTeleportable(planetIndex)) {
            label.setText(Component.literal("目标: " + name));
        } else {
            label.setText(Component.literal("目标: " + name + "（不可传送）"));
        }
    }
}
