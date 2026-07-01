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
    private static final Map<String, ItemLayerTemplates> ITEM_TYPE_OVERRIDES = Map.of(
            //这一行以下写锭（单质）
            "test_ingot", ItemLayerTemplates.INGOT,
            "aluminium_ingot", ItemLayerTemplates.INGOT,
            "nickel_ingot", ItemLayerTemplates.INGOT,
            "tin_ingot", ItemLayerTemplates.INGOT,
            "zinc_ingot", ItemLayerTemplates.INGOT,
            //这一行以下写锭（合金）
            "brass_ingot", ItemLayerTemplates.ALLOY,
            "bronze_ingot", ItemLayerTemplates.ALLOY,
            "ivar_ingot", ItemLayerTemplates.ALLOY,
            "cupronickel_ingot", ItemLayerTemplates.ALLOY,
            "stainless_steel_ingot", ItemLayerTemplates.ALLOY
            // 后续新增染色物品在此添加，例如：
            // "gold_ingot", ItemLayerTemplates.INGOT,
            // "diamond_gear", ItemLayerTemplates.GEAR
    );
    private static final String[] NORMAL_ITEMS = {
            "steel_ingot",
            "test_item1",
            "test_item2",
            "test_item3",
            "test_raw"
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
        //basicItem(ModItems.STEEL_INGOT.get());
        //basicItem(ModItems.TEST_ITEM1.get());
        //basicItem(ModItems.TEST_ITEM2.get());
        //basicItem(ModItems.TEST_ITEM3.get());
        //basicItem(ModItems.TEST_INGOT.get());
        //basicItem(ModItems.TEST_RAW.get());
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
