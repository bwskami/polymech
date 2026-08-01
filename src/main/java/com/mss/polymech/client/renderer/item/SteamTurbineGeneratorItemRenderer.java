package com.mss.polymech.client.renderer.item;

import com.mss.polymech.client.model.SteamTurbineGeneratorItemModel;
import com.mss.polymech.item.SteamTurbineGeneratorItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class SteamTurbineGeneratorItemRenderer extends GeoItemRenderer<SteamTurbineGeneratorItem> {
    public SteamTurbineGeneratorItemRenderer() {
        super(new SteamTurbineGeneratorItemModel());
    }
}
