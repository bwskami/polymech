package com.mss.polymech.client.model;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.production.SteamTurbineGeneratorBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class SteamTurbineGeneratorModel extends GeoModel<SteamTurbineGeneratorBlockEntity> {
    @Override
    public ResourceLocation getModelResource(SteamTurbineGeneratorBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/steam_turbine_generator.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SteamTurbineGeneratorBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/steam_turbine_generator/steam_turbine_generator.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SteamTurbineGeneratorBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/steam_turbine_generator.animation.json");
    }
}
