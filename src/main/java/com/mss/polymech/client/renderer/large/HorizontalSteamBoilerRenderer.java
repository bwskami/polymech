package com.mss.polymech.client.renderer.large;

import com.mss.polymech.block.entity.large.HorizontalSteamBoilerBlockEntity;
import com.mss.polymech.client.model.large.HorizontalSteamBoilerModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class HorizontalSteamBoilerRenderer extends GeoBlockRenderer<HorizontalSteamBoilerBlockEntity> {

    public HorizontalSteamBoilerRenderer(BlockEntityRendererProvider.Context context) {
        super(new HorizontalSteamBoilerModel());
    }

    @Override
    public boolean shouldRenderOffScreen(HorizontalSteamBoilerBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return 256;
    }
}
