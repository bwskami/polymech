package com.mss.polymech.client.renderer.item;

import com.mss.polymech.client.model.HorizontalSteamBoilerItemModel;
import com.mss.polymech.item.HorizontalSteamBoilerItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class HorizontalSteamBoilerItemRenderer extends GeoItemRenderer<HorizontalSteamBoilerItem> {
    public HorizontalSteamBoilerItemRenderer() {
        super(new HorizontalSteamBoilerItemModel());
    }
}
