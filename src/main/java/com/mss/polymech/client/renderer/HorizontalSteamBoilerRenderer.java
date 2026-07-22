package com.mss.polymech.client.renderer;

import com.mss.polymech.client.model.HorizontalSteamBoilerModel;
import com.mss.polymech.machine.production.HorizontalSteamBoilerBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class HorizontalSteamBoilerRenderer extends GeoBlockRenderer<HorizontalSteamBoilerBlockEntity> {
    public HorizontalSteamBoilerRenderer(BlockEntityRendererProvider.Context context) {
        super(new HorizontalSteamBoilerModel());
    }

    @Override
    public AABB getRenderBoundingBox(HorizontalSteamBoilerBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(4, 4, 4);
    }

    @Override
    public boolean shouldRenderOffScreen(HorizontalSteamBoilerBlockEntity blockEntity) {
        return true;
    }
}
