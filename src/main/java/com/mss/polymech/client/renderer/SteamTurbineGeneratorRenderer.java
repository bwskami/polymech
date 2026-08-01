package com.mss.polymech.client.renderer;

import com.mss.polymech.client.model.SteamTurbineGeneratorModel;
import com.mss.polymech.machine.production.SteamTurbineGeneratorBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class SteamTurbineGeneratorRenderer extends GeoBlockRenderer<SteamTurbineGeneratorBlockEntity> {
    public SteamTurbineGeneratorRenderer(BlockEntityRendererProvider.Context context) {
        super(new SteamTurbineGeneratorModel());
    }

    @Override
    public AABB getRenderBoundingBox(SteamTurbineGeneratorBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(4, 4, 4);
    }

    @Override
    public boolean shouldRenderOffScreen(SteamTurbineGeneratorBlockEntity blockEntity) {
        return true;
    }
}
