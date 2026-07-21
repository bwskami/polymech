package com.mss.polymech.client.model.large;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.entity.large.HorizontalSteamBoilerBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class HorizontalSteamBoilerModel extends GeoModel<HorizontalSteamBoilerBlockEntity> {

    @Override
    public ResourceLocation getModelResource(HorizontalSteamBoilerBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/horizontal_steam_boiler.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(HorizontalSteamBoilerBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/horizontal_steam_boiler.png");
    }

    @Override
    public ResourceLocation getAnimationResource(HorizontalSteamBoilerBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/horizontal_steam_boiler.animation.json");
    }
}
