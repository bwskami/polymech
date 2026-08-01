package com.mss.polymech.client.renderer.item;

import com.mss.polymech.client.model.PrimitiveBlastFurnaceItemModel;
import com.mss.polymech.item.PrimitiveBlastFurnaceItem;
import software.bernie.geckolib.renderer.GeoItemRenderer;

public class PrimitiveBlastFurnaceItemRenderer extends GeoItemRenderer<PrimitiveBlastFurnaceItem> {
    public PrimitiveBlastFurnaceItemRenderer() {
        super(new PrimitiveBlastFurnaceItemModel());
    }
}
