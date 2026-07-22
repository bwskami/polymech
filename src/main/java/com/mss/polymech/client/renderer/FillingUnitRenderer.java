package com.mss.polymech.client.renderer;

import com.mss.polymech.client.model.FillingUnitModel;
import com.mss.polymech.machine.production.FillingUnitBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class FillingUnitRenderer extends GeoBlockRenderer<FillingUnitBlockEntity> {
    public FillingUnitRenderer(BlockEntityRendererProvider.Context context) {
        super(new FillingUnitModel());
    }

    @Override
    public AABB getRenderBoundingBox(FillingUnitBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(4, 4, 4);
    }

    @Override
    public boolean shouldRenderOffScreen(FillingUnitBlockEntity blockEntity) {
        return true;
    }
}
