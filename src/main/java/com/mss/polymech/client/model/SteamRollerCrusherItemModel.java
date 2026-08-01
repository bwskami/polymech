package com.mss.polymech.client.model;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.SteamRollerCrusherItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class SteamRollerCrusherItemModel extends GeoModel<SteamRollerCrusherItem> {
    @Override
    public ResourceLocation getModelResource(SteamRollerCrusherItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/steam_roller_crusher.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SteamRollerCrusherItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/steam_roller_crusher/steam_roller_crusher.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SteamRollerCrusherItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/steam_roller_crusher.animation.json");
    }
}
