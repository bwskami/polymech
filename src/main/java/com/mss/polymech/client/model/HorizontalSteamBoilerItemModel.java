package com.mss.polymech.client.model;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.HorizontalSteamBoilerItem;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class HorizontalSteamBoilerItemModel extends GeoModel<HorizontalSteamBoilerItem> {
    @Override
    public ResourceLocation getModelResource(HorizontalSteamBoilerItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/horizontal_steam_boiler.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(HorizontalSteamBoilerItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/horizontal_steam_boiler/horizontal_steam_boiler.png");
    }

    @Override
    public ResourceLocation getAnimationResource(HorizontalSteamBoilerItem animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/horizontal_steam_boiler.animation.json");
    }
}
