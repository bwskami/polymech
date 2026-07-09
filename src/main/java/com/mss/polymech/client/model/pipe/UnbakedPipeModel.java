package com.mss.polymech.client.model.pipe;

import com.mojang.math.Transformation;
import com.mss.polymech.client.model.pipe.PipeModelLoader.ArmConfig;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IUnbakedGeometry;
import org.joml.Quaternionf;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

public class UnbakedPipeModel implements IUnbakedGeometry<UnbakedPipeModel> {
    private final ResourceLocation centerModel;
    private final Map<Direction, ArmConfig> armConfigs;

    public UnbakedPipeModel(ResourceLocation centerModel, Map<Direction, ArmConfig> armConfigs) {
        this.centerModel = centerModel;
        this.armConfigs = armConfigs;
    }

    @Override
    public BakedPipeModel bake(IGeometryBakingContext context, ModelBaker baker,
                               Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState,
                               ItemOverrides overrides) {
        BakedModel center = baker.bake(centerModel, modelState, spriteGetter);
        Map<Direction, BakedModel> arms = new EnumMap<>(Direction.class);

        for (var entry : armConfigs.entrySet()) {
            Direction dir = entry.getKey();
            ArmConfig config = entry.getValue();
            
            // 创建带旋转的 ModelState
            ModelState rotatedState = createRotatedState(modelState, config.xRot(), config.yRot());
            BakedModel arm = baker.bake(config.model(), rotatedState, spriteGetter);
            arms.put(dir, arm);
        }

        return new BakedPipeModel(center, arms);
    }

    private ModelState createRotatedState(ModelState baseState, int xRot, int yRot) {
        if (xRot == 0 && yRot == 0) {
            return baseState;
        }

        Quaternionf rotation = new Quaternionf();
        if (yRot != 0) {
            rotation.rotateY((float) Math.toRadians(yRot));
        }
        if (xRot != 0) {
            rotation.rotateX((float) Math.toRadians(xRot));
        }

        Transformation transform = new Transformation(null, rotation, null, null);

        return new ModelState() {
            @Override
            public Transformation getRotation() {
                return transform;
            }

            @Override
            public boolean isUvLocked() {
                return baseState.isUvLocked();
            }
        };
    }

    @Override
    public void resolveParents(Function<ResourceLocation, UnbakedModel> resolver, IGeometryBakingContext context) {
        // 解析父模型（如果需要）
    }
}