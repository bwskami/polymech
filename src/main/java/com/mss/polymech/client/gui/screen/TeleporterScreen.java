package com.mss.polymech.client.gui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.mss.polymech.client.gui.widget.planet.SolarSystem;
import com.mss.polymech.client.gui.widget.planet.SolarSystemView;
import com.mss.polymech.dimension.PlanetDimensions;
import com.mss.polymech.network.TeleportToPlanetPacket;
import com.mss.polymech.space.RealAstroData;
import com.mss.polymech.space.SpaceWorld;
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
    private final Button spaceButton;
    private int selectedPlanet;

    private TeleporterScreen(State s, Label selectionLabel, Button teleportButton, Button spaceButton) {
        super(s, Component.literal("星际传送器 / Teleporter"));
        this.selectionLabel = selectionLabel;
        this.teleportButton = teleportButton;
        this.spaceButton = spaceButton;
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
            PacketDistributor.sendToServer(new TeleportToPlanetPacket(pi, false, selectedName()));
            Minecraft.getInstance().setScreen(null);
        });
        // 传送到天体上方的宇宙空间（太空维度）。按名字解析，索引空间无关；
        // 在当前尺度下过小的天体（直径不足 10 格）无法观测，直接提示。
        spaceButton.setOnClick(e -> {
            int pi = this.selectedPlanet;
            var sys = view.getSolarSystem();
            if (pi < 0 || pi >= sys.size()) {
                selectionLabel.setText(Component.literal("未选择天体"));
                return;
            }
            String name = sys.get(pi).name();
            RealAstroData body = RealAstroData.byName(name);
            if (body == null) {
                selectionLabel.setText(Component.literal(name + " 暂无太空数据"));
                return;
            }
            if (SpaceWorld.toMc(body.radiusMeters()) < 5.0) {
                selectionLabel.setText(Component.literal(name + " 在当前尺度下过小，无法太空观测"));
                return;
            }
            PacketDistributor.sendToServer(new TeleportToPlanetPacket(pi, true, name));
            Minecraft.getInstance().setScreen(null);
        });
    }

    public static void open() {
        Ui ui = buildUi();
        TeleporterScreen screen = new TeleporterScreen(ui.state(), ui.selectionLabel(), ui.teleportButton(), ui.spaceButton());
        Minecraft.getInstance().setScreen(screen);
    }

    /** M 键打开星图。 */
    @Override
    protected void onMKeyPressed() {
        StarMapScreen.open(view.getSystemIndex());
    }

    // ============================ 构建 ============================

    private record Ui(State state, Label selectionLabel, Button teleportButton, Button spaceButton) {
    }

    private static Ui buildUi() {
        Label selectionLabel = new Label();
        Button teleportButton = new Button()
                .setText(Component.literal("传送"));
        teleportButton.layout(l -> l.height(20).width(56));
        Button spaceButton = new Button()
                .setText(Component.literal("星球上空"));
        spaceButton.layout(l -> l.height(20).width(80));

        State s = buildState(
                Component.literal("星际传送器 / Teleporter"),
                Component.literal("左键单击星球选择目标 · 滚轮缩放 · 点击“传送”前往 · M 星图"),
                root -> new SolarSystemView(SolarSystem.createDefault(), node -> { }),
                selectionLabel,
                teleportButton,
                spaceButton);

        return new Ui(s, selectionLabel, teleportButton, spaceButton);
    }

    /** 当前选中天体的中文名（越界返回空串）。 */
    private String selectedName() {
        var sys = view.getSolarSystem();
        if (selectedPlanet < 0 || selectedPlanet >= sys.size()) return "";
        return sys.get(selectedPlanet).name();
    }

    private static void updateSelection(SolarSystemView view, int planetIndex, Label label) {
        if (planetIndex < 0 || planetIndex >= view.getSolarSystem().size()) return;
        String name = view.getSolarSystem().get(planetIndex).name();
        if (PlanetDimensions.isTeleportable(planetIndex)) {
            label.setText(Component.literal("目标: " + name));
        } else {
            label.setText(Component.literal("目标: " + name + "（无地表，仅可传送至上空）"));
        }
    }
}
