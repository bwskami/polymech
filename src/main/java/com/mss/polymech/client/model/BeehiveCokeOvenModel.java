package com.mss.polymech.client.model;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.production.BeehiveCokeOvenBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class BeehiveCokeOvenModel extends GeoModel<BeehiveCokeOvenBlockEntity> {
    @Override
    public ResourceLocation getModelResource(BeehiveCokeOvenBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/beehive_coke_oven.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(BeehiveCokeOvenBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/beehive_coke_oven/beehive_coke_oven.png");
    }

    @Override
    public ResourceLocation getAnimationResource(BeehiveCokeOvenBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/beehive_coke_oven.animation.json");
    }
}
