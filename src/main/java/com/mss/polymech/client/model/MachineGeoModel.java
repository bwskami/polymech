package com.mss.polymech.client.model;

import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoRenderer;

/**
 * 通用机器 GeoModel，块模型和物品模型共用。
 * <p>
 * 资源路径在构造时注入，无需为每台机器单独创建 Model 子类。
 * </p>
 */
public class MachineGeoModel<T extends GeoAnimatable> extends GeoModel<T> {

    private final ResourceLocation model;
    private final ResourceLocation texture;
    private final ResourceLocation animation;

    public MachineGeoModel(ResourceLocation model, ResourceLocation texture, ResourceLocation animation) {
        this.model = model;
        this.texture = texture;
        this.animation = animation;
    }

    @Override
    public ResourceLocation getModelResource(T animatable) {
        return model;
    }

    @Override
    public ResourceLocation getTextureResource(T animatable) {
        return texture;
    }

    @Override
    public ResourceLocation getAnimationResource(T animatable) {
        return animation;
    }

    /**
     * 创建一个渲染类型被覆写的副本，供预览虚影使用。
     */
    public MachineGeoModel<T> withRenderType(RenderTypeOverride override) {
        return new MachineGeoModel<>(model, texture, animation) {
            @Override
            public net.minecraft.client.renderer.RenderType getRenderType(T animatable, ResourceLocation texture) {
                return override.apply(animatable, texture);
            }
        };
    }

    @FunctionalInterface
    public interface RenderTypeOverride {
        net.minecraft.client.renderer.RenderType apply(GeoAnimatable animatable, ResourceLocation texture);
    }
}
