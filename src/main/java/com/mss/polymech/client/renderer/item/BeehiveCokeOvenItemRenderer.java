package com.mss.polymech.client.renderer.item;

import com.mss.polymech.client.model.BeehiveCokeOvenItemModel;
import com.mss.polymech.item.BeehiveCokeOvenItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class BeehiveCokeOvenItemRenderer extends GeoItemRenderer<BeehiveCokeOvenItem> {
    public BeehiveCokeOvenItemRenderer() {
        super(new BeehiveCokeOvenItemModel());
    }
}
