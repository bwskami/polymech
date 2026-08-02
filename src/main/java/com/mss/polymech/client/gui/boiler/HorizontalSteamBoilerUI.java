package com.mss.polymech.client.gui.boiler;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mss.polymech.machine.boiler.AbstractSteamBoilerBlockEntity;
import com.mss.polymech.machine.production.HorizontalSteamBoilerBlockEntity;

import java.util.Objects;

/**
 * 卧式蒸汽锅炉（大锅炉）GUI，委托给 {@link AbstractSteamBoilerUI} 共享基类。
 */
public class HorizontalSteamBoilerUI extends AbstractSteamBoilerUI {

    @Override
    protected String getTitleKey() {
        return "block.poly_mech.horizontal_steam_boiler";
    }

    @Override
    protected AbstractSteamBoilerBlockEntity getBoiler(BlockUIMenuType.BlockUIHolder holder) {
        return (HorizontalSteamBoilerBlockEntity) Objects.requireNonNull(holder.player.level())
                .getBlockEntity(holder.pos);
    }

    public static ModularUI create(BlockUIMenuType.BlockUIHolder holder) {
        return new HorizontalSteamBoilerUI().createBoilerUI(holder);
    }
}
