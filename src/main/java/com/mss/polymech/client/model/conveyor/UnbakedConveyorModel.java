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
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Function;

public class UnbakedConveyorModel implements IUnbakedGeometry<UnbakedConveyorModel> {
    private final ResourceLocation centerModel;
    private final ResourceLocation leftRailModel;
    private final ResourceLocation rightRailModel;
    private final ResourceLocation upModel;
    private final ResourceLocation downModel;

    public UnbakedConveyorModel(ResourceLocation centerModel, ResourceLocation leftRailModel, ResourceLocation rightRailModel) {
        this(centerModel, leftRailModel, rightRailModel, null, null);
    }

    public UnbakedConveyorModel(ResourceLocation centerModel, ResourceLocation leftRailModel, 
                                 ResourceLocation rightRailModel, ResourceLocation upModel, 
                                 ResourceLocation downModel) {
        this.centerModel = centerModel;
        this.leftRailModel = leftRailModel;
        this.rightRailModel = rightRailModel;
        this.upModel = upModel;
        this.downModel = downModel;
    }

    @Override
    public BakedConveyorModel bake(IGeometryBakingContext context, ModelBaker baker,
                                    Function<Material, TextureAtlasSprite> spriteGetter,
                                    ModelState modelState, ItemOverrides overrides) {
        BakedModel center = baker.bake(centerModel, modelState, spriteGetter);
        BakedModel leftRail = baker.bake(leftRailModel, modelState, spriteGetter);
        BakedModel rightRail = baker.bake(rightRailModel, modelState, spriteGetter);
        BakedModel up = upModel != null ? baker.bake(upModel, modelState, spriteGetter) : null;

        BakedModel down = null;
        if (downModel != null) {
            ModelState rotatedState = new ModelState() {
                @Override
                public com.mojang.math.Transformation getRotation() {
                    Quaternionf rot180Y = new Quaternionf().rotationAxis((float) Math.PI, new Vector3f(0, 1, 0));
                    return modelState.getRotation().compose(new com.mojang.math.Transformation(null, rot180Y, null, null));
                }
            };
            down = baker.bake(downModel, rotatedState, spriteGetter);
        }

        return new BakedConveyorModel(center, leftRail, rightRail, up, down);
    }

    @Override
    public void resolveParents(Function<ResourceLocation, net.minecraft.client.resources.model.UnbakedModel> resolver,
                               IGeometryBakingContext context) {
    }
}