package com.mss.polymech.client.renderer.item;

import com.mss.polymech.client.model.MachineGeoModel;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.renderer.GeoItemRenderer;

/**
 * 通用机器物品渲染器，所有大型机器的物品共用。
 * <p>
 * 资源路径在构造时注入，无需为每台机器单独创建 ItemRenderer 子类。
 * </p>
 */
public class MachineGeoItemRenderer extends GeoItemRenderer<com.mss.polymech.item.MachineBlockItem> {

    public MachineGeoItemRenderer(ResourceLocation modelPath, ResourceLocation texturePath, ResourceLocation animationPath) {
        super(new MachineGeoModel<>(modelPath, texturePath, animationPath));
    }
}
