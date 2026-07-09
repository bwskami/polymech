package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.texture_data.ItemLayerTemplates;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.HashMap;
import java.util.Map;

public class ModItemModelsProvider extends ItemModelProvider {
    private static final Map<String, ItemLayerTemplates> ITEM_TYPE_OVERRIDES = new HashMap<>();
    
    static {
        // 初始化物品类型映射
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            // 锭
            ITEM_TYPE_OVERRIDES.put(materialName + "_ingot", ItemLayerTemplates.INGOT);
            // 合金锭
            ITEM_TYPE_OVERRIDES.put(materialName + "_alloy_ingot", ItemLayerTemplates.ALLOY);
            // 粒
            ITEM_TYPE_OVERRIDES.put(materialName + "_nugget", ItemLayerTemplates.NUGGET);
            // 粉
            ITEM_TYPE_OVERRIDES.put(materialName + "_dust", ItemLayerTemplates.DUST);
            // 板
            ITEM_TYPE_OVERRIDES.put(materialName + "_plate", ItemLayerTemplates.PLATE);
            // 箔
            ITEM_TYPE_OVERRIDES.put(materialName + "_foil", ItemLayerTemplates.FOIL);
            // 杆
            ITEM_TYPE_OVERRIDES.put(materialName + "_stick", ItemLayerTemplates.STICK);
            // 齿轮
            ITEM_TYPE_OVERRIDES.put(materialName + "_gear", ItemLayerTemplates.GEAR);
            // 小齿轮
            ITEM_TYPE_OVERRIDES.put(materialName + "_small_gear", ItemLayerTemplates.SMALL_GEAR);
            // 弹簧
            ITEM_TYPE_OVERRIDES.put(materialName + "_spring", ItemLayerTemplates.SPRING);
            // 螺丝
            ITEM_TYPE_OVERRIDES.put(materialName + "_screw", ItemLayerTemplates.SCREW);
            // 螺栓
            ITEM_TYPE_OVERRIDES.put(materialName + "_bolt", ItemLayerTemplates.BOLT);
            // 环
            ITEM_TYPE_OVERRIDES.put(materialName + "_ring", ItemLayerTemplates.RING);
        }
        
        // 管道物品
        ITEM_TYPE_OVERRIDES.put("pipe", ItemLayerTemplates.PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM);
        
        // 青铜管道物品
        ITEM_TYPE_OVERRIDES.put("bronze_pipe", ItemLayerTemplates.PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("bronze_small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("bronze_big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("bronze_huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM);
        
        // 不锈钢管道物品
        ITEM_TYPE_OVERRIDES.put("stainless_steel_pipe", ItemLayerTemplates.PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("stainless_steel_small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("stainless_steel_big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("stainless_steel_huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM);
        
        // 黄铜管道物品
        ITEM_TYPE_OVERRIDES.put("brass_pipe", ItemLayerTemplates.PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("brass_small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("brass_big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM);
        ITEM_TYPE_OVERRIDES.put("brass_huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM);
    }
    private static final String[] NORMAL_ITEMS = {
            "steel_ingot",
            "wrench"
            // 在这里列出所有需要独立纹理的普通物品
    };
    private boolean isNormalItem(String path) {
        for (String normal : NORMAL_ITEMS) {
            if (normal.equals(path)) {
                return true;
            }
        }
        return false;
    }

    public ModItemModelsProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, Polymech.MOD_ID, existingFileHelper);
    }



    @Override
    protected void registerModels() {
        for (var entry : ModItems.ITEMS.getEntries()) {
            Item item = entry.get();
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null) continue;
            String path = itemId.getPath();

            // 情况1: 染色模板物品 → 生成多层模型
            ItemLayerTemplates type = ITEM_TYPE_OVERRIDES.get(path);
            if (type != null) {
                var builder = withExistingParent(path, "item/generated");
                var layers = type.getLayerTextures();
                for (int i = 0; i < layers.size(); i++) {
                    builder.texture("layer" + i, modLoc(layers.get(i)));
                }
                continue;
            }

            // 情况2: 普通物品 → 使用 basicItem（需要独立纹理）
            if (isNormalItem(path)) {
                basicItem(item);
                continue;
            }

            // 情况3: 方块物品或其他 → 跳过（由 BlockStateProvider 处理）
            Polymech.LOGGER.debug("Skipped model generation for block item: {}", path);
        }
    }
}