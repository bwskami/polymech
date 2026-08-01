package com.mss.polymech.client.renderer;

import com.mss.polymech.client.model.BeehiveCokeOvenModel;
import com.mss.polymech.machine.production.BeehiveCokeOvenBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class BeehiveCokeOvenRenderer extends GeoBlockRenderer<BeehiveCokeOvenBlockEntity> {
    public BeehiveCokeOvenRenderer(BlockEntityRendererProvider.Context context) {
        super(new BeehiveCokeOvenModel());
    }

    @Override
    public AABB getRenderBoundingBox(BeehiveCokeOvenBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(4, 4, 4);
    }

    @Override
    public boolean shouldRenderOffScreen(BeehiveCokeOvenBlockEntity blockEntity) {
        return true;
    }
}
