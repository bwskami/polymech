package com.mss.polymech.client.renderer;

import com.mss.polymech.client.model.SteamRollerCrusherModel;
import com.mss.polymech.machine.production.SteamRollerCrusherBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

public class SteamRollerCrusherRenderer extends GeoBlockRenderer<SteamRollerCrusherBlockEntity> {
    public SteamRollerCrusherRenderer(BlockEntityRendererProvider.Context context) {
        super(new SteamRollerCrusherModel());
    }

    @Override
    public AABB getRenderBoundingBox(SteamRollerCrusherBlockEntity blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(4, 4, 4);
    }

    @Override
    public boolean shouldRenderOffScreen(SteamRollerCrusherBlockEntity blockEntity) {
        return true;
    }
}
