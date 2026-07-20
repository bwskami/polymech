package com.mss.polymech.client.renderer;

import com.mss.polymech.block.entity.large.HorizontalSteamBoilerBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedBlockGeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class HorizontalSteamBoilerRenderer extends GeoBlockRenderer<HorizontalSteamBoilerBlockEntity> {
    public HorizontalSteamBoilerRenderer(BlockEntityRendererProvider.Context context) {
        super(new DefaultedBlockGeoModel<>(ResourceLocation.fromNamespaceAndPath("poly_mech", "horizontal_steam_boiler")));
    }
}
