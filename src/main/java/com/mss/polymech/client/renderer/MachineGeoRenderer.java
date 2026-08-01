package com.mss.polymech.client.renderer;

import com.mss.polymech.client.model.MachineGeoModel;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * 通用机器方块渲染器，所有大型机器共用。
 * <p>
 * 在构造时注入模型路径，无需为每台机器单独创建 Renderer 子类。
 * </p>
 */
public class MachineGeoRenderer<T extends BlockEntity & GeoBlockEntity> extends GeoBlockRenderer<T> {

    public MachineGeoRenderer(BlockEntityRendererProvider.Context context,
                               ResourceLocation modelPath, ResourceLocation texturePath, ResourceLocation animationPath) {
        super(new MachineGeoModel<>(modelPath, texturePath, animationPath));
    }

    @Override
    public AABB getRenderBoundingBox(T blockEntity) {
        return new AABB(blockEntity.getBlockPos()).inflate(4, 4, 4);
    }

    @Override
    public boolean shouldRenderOffScreen(T blockEntity) {
        return true;
    }
}
