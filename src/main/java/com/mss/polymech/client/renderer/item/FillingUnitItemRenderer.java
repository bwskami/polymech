package com.mss.polymech.client.renderer.item;

import com.mss.polymech.client.model.FillingUnitItemModel;
import com.mss.polymech.item.FillingUnitItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class FillingUnitItemRenderer extends GeoItemRenderer<FillingUnitItem> {
    public FillingUnitItemRenderer() {
        super(new FillingUnitItemModel());
    }
}
