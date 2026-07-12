package com.mss.polymech.client.model.conveyor;

import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;

import java.util.function.Function;

public class UnbakedConveyorModel implements IUnbakedGeometry<UnbakedConveyorModel> {
    private final ResourceLocation centerModel;
    private final ResourceLocation leftRailModel;
    private final ResourceLocation rightRailModel;

    public UnbakedConveyorModel(ResourceLocation centerModel, ResourceLocation leftRailModel, ResourceLocation rightRailModel) {
        this.centerModel = centerModel;
        this.leftRailModel = leftRailModel;
        this.rightRailModel = rightRailModel;
    }

    @Override
    public BakedConveyorModel bake(IGeometryBakingContext context, ModelBaker baker,
                                    Function<Material, TextureAtlasSprite> spriteGetter,
                                    ModelState modelState, ItemOverrides overrides) {
        BakedModel center = baker.bake(centerModel, modelState, spriteGetter);
        BakedModel leftRail = baker.bake(leftRailModel, modelState, spriteGetter);
        BakedModel rightRail = baker.bake(rightRailModel, modelState, spriteGetter);
        return new BakedConveyorModel(center, leftRail, rightRail);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, net.minecraft.client.resources.model.UnbakedModel> resolver,
                               IGeometryBakingContext context) {
    }
}
