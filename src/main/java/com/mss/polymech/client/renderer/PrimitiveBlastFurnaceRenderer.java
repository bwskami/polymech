package com.mss.polymech.client.renderer;

import com.mss.polymech.client.model.PrimitiveBlastFurnaceModel;
import com.mss.polymech.machine.production.PrimitiveBlastFurnaceBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class PrimitiveBlastFurnaceRenderer extends GeoBlockRenderer<PrimitiveBlastFurnaceBlockEntity> {
    public PrimitiveBlastFurnaceRenderer(BlockEntityRendererProvider.Context context) {
        super(new PrimitiveBlastFurnaceModel());
    }

    @Override
    public AABB getRenderBoundingBox(PrimitiveBlastFurnaceBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(4, 4, 4);
    }

    @Override
    public boolean shouldRenderOffScreen(PrimitiveBlastFurnaceBlockEntity blockEntity) {
        return true;
    }
}
