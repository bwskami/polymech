package com.mss.polymech.client.gui.prospector;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mss.polymech.prospecting.ProspectorScan;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.function.Supplier;

/*
 * 探矿仪GUI：勘探地图（岩石底图+矿物叠加）。
 * <p>
 * 服务端首次求值时扫描玩家所在5×5区块范围（{@link ProspectorScan}），
 * 编码为字符串经stringS2C一次性同步到客户端，由{@link ProspectorMapWidget}绘制。
 * </p>
 */
public final class ProspectorUI {

    private ProspectorUI() {
    }

    public static ModularUI create(HeldItemUIMenuType.HeldItemUIHolder holder) {
        // 服务端懒扫描：getter仅在服务端被求值（客户端使用远程数据源=widget）
        String[] memo = new String[1];
        Supplier<String> scanSupplier = () -> {
            if (memo[0] == null) {
                ServerLevel level = (ServerLevel) holder.player.level();
                memo[0] = scanPlayerArea(level, holder.player.blockPosition().getX(),
                        holder.player.blockPosition().getZ());
            }
            return memo[0];
        };

        var map = new ProspectorMapWidget();
        map.bind(DataBindingBuilder.stringS2C(scanSupplier).build());
        map.layout(l -> l.width(176).height(176));

        var title = new Label()
                .setText(Component.translatable("gui.poly_mech.prospector.title"));
        var hint = new Label()
                .setText(Component.translatable("gui.poly_mech.prospector.hint"));

        var root = new UIElement();
        root.layout(l -> l.paddingAll(7).gapAll(4));
        root.addClass("panel_bg");
        root.addChildren(title, map, hint);

        return ModularUI.of(UI.of(root, StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC)), holder.player);
    }

    /** 扫描玩家所在区域并编码为字符串 */
    private static String scanPlayerArea(ServerLevel level, int blockX, int blockZ) {
        int chunkX = SectionPos.blockToSectionCoord(blockX);
        int chunkZ = SectionPos.blockToSectionCoord(blockZ);
        return ProspectorScan.scan(level, chunkX, chunkZ).encode();
    }
}
