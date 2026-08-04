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
    private final Map<Direction, ArmConfig> inputConfigs;

    public UnbakedPipeModel(ResourceLocation centerModel, Map<Direction, ArmConfig> armConfigs,
                            Map<Direction, ArmConfig> inputConfigs) {
        this.centerModel = centerModel;
        this.armConfigs = armConfigs;
        this.inputConfigs = inputConfigs;
    }

    @Override
    public BakedPipeModel bake(IGeometryBakingContext context, ModelBaker baker,
                               Function<Material, TextureAtlasSprite> spriteGetter, ModelState modelState,
                               ItemOverrides overrides) {
        BakedModel center = baker.bake(centerModel, modelState, spriteGetter);
        Map<Direction, BakedModel> arms = bakeSection(baker, spriteGetter, modelState, armConfigs);
        Map<Direction, BakedModel> inputs = bakeSection(baker, spriteGetter, modelState, inputConfigs);
        return new BakedPipeModel(center, arms, inputs);
    }

    private static Map<Direction, BakedModel> bakeSection(ModelBaker baker,
                                                          Function<Material, TextureAtlasSprite> spriteGetter,
                                                          ModelState modelState, Map<Direction, ArmConfig> configs) {
        Map<Direction, BakedModel> baked = new EnumMap<>(Direction.class);
        for (var entry : configs.entrySet()) {
            ArmConfig config = entry.getValue();
            // 创建带旋转的 ModelState
            ModelState rotatedState = createRotatedState(modelState, config.xRot(), config.yRot());
            baked.put(entry.getKey(), baker.bake(config.model(), rotatedState, spriteGetter));
        }
        return baked;
    }

    /**
     * 与原版 BlockModelRotation 一致的旋转约定：{@code rotateYXZ(-y, -x, 0)}。
     * 即 JSON 里的 x/y 语义等同 blockstate 变体的 x/y：
     * 朝北的模型 y=90 朝东、y=270 朝西、x=270 朝上、x=90 朝下。
     */
    private static ModelState createRotatedState(ModelState baseState, int xRot, int yRot) {
        if (xRot == 0 && yRot == 0) {
            return baseState;
        }

        float degToRad = (float) (Math.PI / 180.0);
        Quaternionf rotation = new Quaternionf()
                .rotateYXZ(-yRot * degToRad, -xRot * degToRad, 0.0F);

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