package com.mss.polymech.client.model;

import com.mss.polymech.Polymech;
import com.mss.polymech.machine.production.SteamRollerCrusherBlockEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.GeoModel;

public class SteamRollerCrusherModel extends GeoModel<SteamRollerCrusherBlockEntity> {
    @Override
    public ResourceLocation getModelResource(SteamRollerCrusherBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/block/steam_roller_crusher.geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(SteamRollerCrusherBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/steam_roller_crusher/steam_roller_crusher.png");
    }

    @Override
    public ResourceLocation getAnimationResource(SteamRollerCrusherBlockEntity animatable) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/block/steam_roller_crusher.animation.json");
    }
}
