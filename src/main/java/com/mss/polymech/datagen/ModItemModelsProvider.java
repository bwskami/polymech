package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.texture_data.ItemLayerTemplates;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import java.util.Map;

public class ModItemModelsProvider extends ItemModelProvider {
    private static final Map<String, ItemLayerTemplates> ITEM_TYPE_OVERRIDES = Map.ofEntries(
            Map.entry("test_ingot", ItemLayerTemplates.INGOT),
            Map.entry("aluminium_ingot", ItemLayerTemplates.INGOT),
            Map.entry("nickel_ingot", ItemLayerTemplates.INGOT),
            Map.entry("tin_ingot", ItemLayerTemplates.INGOT),
            Map.entry("zinc_ingot", ItemLayerTemplates.INGOT),
            Map.entry("brass_ingot", ItemLayerTemplates.ALLOY),
            Map.entry("bronze_ingot", ItemLayerTemplates.ALLOY),
            Map.entry("ivar_ingot", ItemLayerTemplates.ALLOY),
            Map.entry("cupronickel_ingot", ItemLayerTemplates.ALLOY),
            Map.entry("stainless_steel_ingot", ItemLayerTemplates.ALLOY),
            
            // 普通管道物品
            Map.entry("pipe", ItemLayerTemplates.PIPE_ITEM),
            Map.entry("small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM),
            Map.entry("big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM),
            Map.entry("huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM),
            
            // 青铜管道物品
            Map.entry("bronze_pipe", ItemLayerTemplates.PIPE_ITEM),
            Map.entry("bronze_small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM),
            Map.entry("bronze_big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM),
            Map.entry("bronze_huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM),
            
            // 不锈钢管道物品
            Map.entry("stainless_steel_pipe", ItemLayerTemplates.PIPE_ITEM),
            Map.entry("stainless_steel_small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM),
            Map.entry("stainless_steel_big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM),
            Map.entry("stainless_steel_huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM),
            
            // 黄铜管道物品
            Map.entry("brass_pipe", ItemLayerTemplates.PIPE_ITEM),
            Map.entry("brass_small_pipe", ItemLayerTemplates.SMALL_PIPE_ITEM),
            Map.entry("brass_big_pipe", ItemLayerTemplates.BIG_PIPE_ITEM),
            Map.entry("brass_huge_pipe", ItemLayerTemplates.HUGE_PIPE_ITEM)
    );
    private static final String[] NORMAL_ITEMS = {
            "steel_ingot",
            "test_item1",
            "test_item2",
            "test_item3",
            "test_raw",
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
