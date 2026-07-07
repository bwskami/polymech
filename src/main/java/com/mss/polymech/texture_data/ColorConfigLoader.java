package com.mss.polymech.texture_data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class ColorConfigLoader {
    private static final Gson GSON = new Gson();
    
    // 材质定义：材质名 -> 颜色数组
    private static final Map<String, Integer[]> MATERIAL_COLORS = new HashMap<>();
    
    // 物品到材质的映射：物品ID -> 材质名
    private static final Map<Item, String> ITEM_MATERIAL_MAP = new HashMap<>();
    
    // 方块到材质的映射：方块ID -> 材质名
    private static final Map<Block, String> BLOCK_MATERIAL_MAP = new HashMap<>();
    
    // 缓存：物品/方块 -> 最终颜色数组（用于快速查找）
    private static final Map<Item, Integer[]> ITEM_COLOR_CACHE = new HashMap<>();
    private static final Map<Block, Integer[]> BLOCK_COLOR_CACHE = new HashMap<>();
    
    private static boolean loaded = false;

    public static void load() {
        if (loaded) return;
        var manager = Minecraft.getInstance().getResourceManager();
        ResourceLocation location = ResourceLocation.parse(Polymech.MOD_ID + ":config/colors.json");
        var optional = manager.getResource(location);
        if (optional.isEmpty()) {
            Polymech.LOGGER.warn("Color config file not found: {}", location);
            return;
        }
        var resource = optional.get();
        try (var inputStream = resource.open();
             var reader = new InputStreamReader(inputStream)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);

            // 1. 先加载材质定义
            if (root.has("materials")) {
                JsonObject materialsObj = root.getAsJsonObject("materials");
                for (var entry : materialsObj.entrySet()) {
                    String materialName = entry.getKey();
                    var colorsArray = entry.getValue().getAsJsonObject().getAsJsonArray("colors");
                    Integer[] colors = parseColors(colorsArray, materialName);
                    MATERIAL_COLORS.put(materialName, colors);
                    Polymech.LOGGER.info("Loaded material {}: {}", materialName, colors);
                }
            }

            // 2. 加载物品材质映射
            if (root.has("item_materials")) {
                JsonObject itemMaterialsObj = root.getAsJsonObject("item_materials");
                for (var entry : itemMaterialsObj.entrySet()) {
                    String itemName = entry.getKey();
                    String materialName = entry.getValue().getAsString();
                    
                    ResourceLocation itemId = ResourceLocation.parse(Polymech.MOD_ID + ":" + itemName);
                    Item item = BuiltInRegistries.ITEM.get(itemId);
                    if (item == null || item == Items.AIR) {
                        Polymech.LOGGER.warn("Item {} not found, skipping material mapping", itemName);
                        continue;
                    }
                    
                    if (!MATERIAL_COLORS.containsKey(materialName)) {
                        Polymech.LOGGER.warn("Material {} not defined, skipping item {}", materialName, itemName);
                        continue;
                    }
                    
                    ITEM_MATERIAL_MAP.put(item, materialName);
                    Polymech.LOGGER.info("Mapped item {} to material {}", itemName, materialName);
                }
            }

            // 3. 加载方块材质映射
            if (root.has("block_materials")) {
                JsonObject blockMaterialsObj = root.getAsJsonObject("block_materials");
                for (var entry : blockMaterialsObj.entrySet()) {
                    String blockName = entry.getKey();
                    String materialName = entry.getValue().getAsString();
                    
                    ResourceLocation blockId = ResourceLocation.parse(Polymech.MOD_ID + ":" + blockName);
                    Block block = BuiltInRegistries.BLOCK.get(blockId);
                    if (block == null || block == Blocks.AIR) {
                        Polymech.LOGGER.warn("Block {} not found, skipping material mapping", blockName);
                        continue;
                    }
                    
                    if (!MATERIAL_COLORS.containsKey(materialName)) {
                        Polymech.LOGGER.warn("Material {} not defined, skipping block {}", materialName, blockName);
                        continue;
                    }
                    
                    BLOCK_MATERIAL_MAP.put(block, materialName);
                    Polymech.LOGGER.info("Mapped block {} to material {}", blockName, materialName);
                }
            }

            // 4. 构建缓存（预计算每个物品/方块的最终颜色）
            buildColorCaches();

            loaded = true;
            Polymech.LOGGER.info("Color config loaded! Materials: {}, Items: {}, Blocks: {}",
                    MATERIAL_COLORS.size(), ITEM_MATERIAL_MAP.size(), BLOCK_MATERIAL_MAP.size());
        } catch (Exception e) {
            Polymech.LOGGER.error("Failed to load color config", e);
        }
    }

    private static void buildColorCaches() {
        // 为所有映射的物品构建颜色缓存
        for (Map.Entry<Item, String> entry : ITEM_MATERIAL_MAP.entrySet()) {
            Item item = entry.getKey();
            String materialName = entry.getValue();
            Integer[] colors = MATERIAL_COLORS.get(materialName);
            if (colors != null) {
                ITEM_COLOR_CACHE.put(item, colors);
            }
        }
        
        // 为所有映射的方块构建颜色缓存
        for (Map.Entry<Block, String> entry : BLOCK_MATERIAL_MAP.entrySet()) {
            Block block = entry.getKey();
            String materialName = entry.getValue();
            Integer[] colors = MATERIAL_COLORS.get(materialName);
            if (colors != null) {
                BLOCK_COLOR_CACHE.put(block, colors);
            }
        }
    }

    private static Integer[] parseColors(com.google.gson.JsonArray colorsArray, String name) {
        Integer[] colors = new Integer[colorsArray.size()];
        for (int i = 0; i < colorsArray.size(); i++) {
            if (colorsArray.get(i).isJsonNull()) {
                colors[i] = null;
                continue;
            }
            String hex = colorsArray.get(i).getAsString();
            if (hex.startsWith("#")) hex = hex.substring(1);
            colors[i] = (int) Long.parseLong(hex, 16);
        }
        return colors;
    }

    public static Integer[] getColors(Item item) {
        return ITEM_COLOR_CACHE.get(item);
    }

    public static Integer[] getColors(Block block) {
        return BLOCK_COLOR_CACHE.get(block);
    }

    public static Item[] getConfiguredItems() {
        return ITEM_COLOR_CACHE.keySet().toArray(new Item[0]);
    }

    public static void reload() {
        Polymech.LOGGER.info("Reloading color config...");
        MATERIAL_COLORS.clear();
        ITEM_MATERIAL_MAP.clear();
        BLOCK_MATERIAL_MAP.clear();
        ITEM_COLOR_CACHE.clear();
        BLOCK_COLOR_CACHE.clear();
        loaded = false;
        load();
        Polymech.LOGGER.info("Color config reloaded! Materials: {}, Items: {}, Blocks: {}",
                MATERIAL_COLORS.size(), ITEM_MATERIAL_MAP.size(), BLOCK_MATERIAL_MAP.size());
    }
}