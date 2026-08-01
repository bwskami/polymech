package com.mss.polymech.item;

import com.mss.polymech.client.renderer.item.MachineGeoItemRenderer;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.client.GeoRenderProvider;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animatable.instance.SingletonAnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;

import java.util.function.Consumer;

/**
 * 通用机器方块物品，所有大型机器共用。
 * <p>
 * 使用 {@code block/} 约定自动推导资源路径，FillingUnit 等例外通过三参构造显式指定。
 * </p>
 */
public class MachineBlockItem extends BlockItem implements GeoItem {

    private final AnimatableInstanceCache cache = new SingletonAnimatableInstanceCache(this);
    private final ResourceLocation modelPath;
    private final ResourceLocation texturePath;
    private final ResourceLocation animationPath;

    /** 使用 {@code block/} 约定自动推导路径 */
    public MachineBlockItem(Block block, Properties settings) {
        super(block, settings);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        String id = key.getPath();
        String modId = key.getNamespace();
        this.modelPath = ResourceLocation.fromNamespaceAndPath(modId, "geo/block/" + id + ".geo.json");
        this.texturePath = ResourceLocation.fromNamespaceAndPath(modId, "textures/block/" + id + "/" + id + ".png");
        this.animationPath = ResourceLocation.fromNamespaceAndPath(modId, "animations/block/" + id + ".animation.json");
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }

    /** 显式指定资源路径（用于不遵循 block/ 约定的机器，如 FillingUnit） */
    public MachineBlockItem(Block block, Properties settings,
                             ResourceLocation modelPath, ResourceLocation texturePath, ResourceLocation animationPath) {
        super(block, settings);
        this.modelPath = modelPath;
        this.texturePath = texturePath;
        this.animationPath = animationPath;
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }

    @Override
    public void createGeoRenderer(Consumer<GeoRenderProvider> consumer) {
        consumer.accept(new GeoRenderProvider() {
            private BlockEntityWithoutLevelRenderer renderer;
            @Override
            public @Nullable BlockEntityWithoutLevelRenderer getGeoItemRenderer() {
                if (renderer == null) {
                    renderer = new MachineGeoItemRenderer(modelPath, texturePath, animationPath);
                }
                return renderer;
            }
        });
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // 无动画；仍需注册以避紫黑块
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }
}
