package com.mss.polymech.client.renderer.item;

import com.mss.polymech.client.model.SteamRollerCrusherItemModel;
import com.mss.polymech.item.SteamRollerCrusherItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class SteamRollerCrusherItemRenderer extends GeoItemRenderer<SteamRollerCrusherItem> {
    public SteamRollerCrusherItemRenderer() {
        super(new SteamRollerCrusherItemModel());
    }
}
