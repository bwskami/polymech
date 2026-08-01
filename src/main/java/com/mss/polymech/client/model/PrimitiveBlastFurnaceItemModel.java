package com.mss.polymech.client.model;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.PrimitiveBlastFurnaceItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class PrimitiveBlastFurnaceItemModel extends GeoModel<PrimitiveBlastFurnaceItem> {
    @Override
    public ResourceLocation getModelResource(PrimitiveBlastFurnaceItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/primitive_blast_furnace.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(PrimitiveBlastFurnaceItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/primitive_blast_furnace/primitive_blast_furnace.png");
    }

    @Override
    public ResourceLocation getAnimationResource(PrimitiveBlastFurnaceItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/primitive_blast_furnace.animation.json");
    }
}
