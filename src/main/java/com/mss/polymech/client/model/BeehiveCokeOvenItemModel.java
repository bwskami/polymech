package com.mss.polymech.client.model;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.BeehiveCokeOvenItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class BeehiveCokeOvenItemModel extends GeoModel<BeehiveCokeOvenItem> {
    @Override
    public ResourceLocation getModelResource(BeehiveCokeOvenItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/beehive_coke_oven.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BeehiveCokeOvenItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/beehive_coke_oven/beehive_coke_oven.png");
    }

    @Override
    public ResourceLocation getAnimationResource(BeehiveCokeOvenItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/beehive_coke_oven.animation.json");
    }
}
