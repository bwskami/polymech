package com.mss.polymech.texture_data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;
import com.mss.polymech.powergrid.GridWireType;
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
import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class ColorConfigLoader {
    private static final Gson GSON = new Gson();
    
    /* 材质定义：材质名 -> 颜色数组 */
    private static final Map<String, Integer[]> MATERIAL_COLORS = new HashMap<>();
    
    /* 物品到材质的映射：物品ID -> 材质名（手动配置） */
    private static final Map<Item, String> ITEM_MATERIAL_MAP = new HashMap<>();
    
    /* 方块到材质的映射：方块ID -> 材质名（手动配置） */
    private static final Map<Block, String> BLOCK_MATERIAL_MAP = new HashMap<>();
    
    /* 缓存：物品/方块 -> 最终颜色数组（用于快速查找） */
    private static final Map<Item, Integer[]> ITEM_COLOR_CACHE = new HashMap<>();
    private static final Map<Block, Integer[]> BLOCK_COLOR_CACHE = new HashMap<>();

    /** 线轴物品后缀：{@code <metal>_wire_spool}，染色数组由对应金属材质派生，无需在colors.json中配置 */
    private static final String WIRE_SPOOL_SUFFIX = "_wire_spool";

    /** 绝缘线轴物品后缀：{@code <metal>_insulated_wire_spool}，派生时额外把颜色加深（与GridWireType同系数） */
    private static final String INSULATED_WIRE_SPOOL_SUFFIX = "_insulated_wire_spool";
    
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
                    Polymech.LOGGER.debug("Loaded material {}: {}", materialName, colors);
                }
            }

            // 2. 加载物品材质映射（手动配置优先）
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
                    Polymech.LOGGER.debug("Mapped item {} to material {}", itemName, materialName);
                }
            }

            // 3. 加载方块材质映射（手动配置优先）
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
                    Polymech.LOGGER.debug("Mapped block {} to material {}", blockName, materialName);
                }
            }

            // 4. 构建缓存（预计算每个物品/方块的最终颜色）
            buildColorCaches();

            loaded = true;
            Polymech.LOGGER.debug("Color config loaded! Materials: {}, Items: {}, Blocks: {}",
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

    /*
     * 获取物品的颜色数组。
     * <p>
     * 首先检查手动配置的映射，如果没有则尝试自动推断。
     * 自动推断逻辑：从物品ID中提取材料名（如 steel_ingot -> steel），
     * 然后查找对应的材质颜色。
     * </p>
     * 
     * @param item 物品实例
     * @return 颜色数组，如果找不到则返回null
     */
    public static Integer[] getColors(Item item) {
        // 1. 首先检查缓存（手动配置）
        Integer[] cached = ITEM_COLOR_CACHE.get(item);
        if (cached != null) {
            return cached;
        }
        
        // 2. 尝试自动推断
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId != null && itemId.getNamespace().equals(Polymech.MOD_ID)) {
            String path = itemId.getPath();

            // 绝缘线轴物品（<metal>_insulated_wire_spool）：取金属材质颜色加深后再加null前缀
            if (path.endsWith(INSULATED_WIRE_SPOOL_SUFFIX)) {
                String materialName = path.substring(0, path.length() - INSULATED_WIRE_SPOOL_SUFFIX.length());
                Integer[] base = MATERIAL_COLORS.get(materialName);
                if (base != null) {
                    // 模型共5层：null(空线轴) + 3(加深线圈) + null(绝缘标识层不染色)
                    Integer[] colors = appendUntintedLayer(
                            withUntintedFirstLayer(darkenColors(base, GridWireType.INSULATED_COLOR_FACTOR)));
                    ITEM_COLOR_CACHE.put(item, colors);
                    Polymech.LOGGER.debug("Derived insulated spool color for item {}: material={}", path, materialName);
                    return colors;
                }
            }

            // 线轴物品（<metal>_wire_spool）不占用colors.json配置：
            // 直接取对应金属材质的颜色数组并在前面加一个null（底层空线轴层不染色）
            if (path.endsWith(WIRE_SPOOL_SUFFIX)) {
                String materialName = path.substring(0, path.length() - WIRE_SPOOL_SUFFIX.length());
                Integer[] base = MATERIAL_COLORS.get(materialName);
                if (base != null) {
                    Integer[] colors = withUntintedFirstLayer(base);
                    ITEM_COLOR_CACHE.put(item, colors);
                    Polymech.LOGGER.debug("Derived spool color for item {}: material={}", path, materialName);
                    return colors;
                }
            }

            // 尝试从物品ID中提取材料名
            // 例如：steel_ingot -> steel, brass_alloy_ingot -> brass
            String materialName = extractMaterialName(path);
            if (materialName != null && MATERIAL_COLORS.containsKey(materialName)) {
                Integer[] colors = MATERIAL_COLORS.get(materialName);
                // 缓存自动推断的结果
                ITEM_COLOR_CACHE.put(item, colors);
                Polymech.LOGGER.debug("Auto-inferred color for item {}: material={}", path, materialName);
                return colors;
            }
        }
        
        return null;
    }

    /*
     * 获取方块的颜色数组。
     * <p>
     * 首先检查手动配置的映射，如果没有则尝试自动推断。
     * </p>
     * 
     * @param block 方块实例
     * @return 颜色数组，如果找不到则返回null
     */
    public static Integer[] getColors(Block block) {
        // 1. 首先检查缓存（手动配置）
        Integer[] cached = BLOCK_COLOR_CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        
        // 2. 尝试自动推断
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (blockId != null && blockId.getNamespace().equals(Polymech.MOD_ID)) {
            String path = blockId.getPath();

            // 矿石方块（格雷/群峦式岩种变体）：
            //   {mineral}_ore / deepslate_{mineral}_ore / {mineral}_{rock}_ore
            // 模型共4层（OOP准则：岩石底图不染色）——
            //   第0层 岩石底图：群峦岩种不染色；深板岩染色（群峦无深板岩，原版深板岩×染色）
            //   第1层 矿石底图（格雷ore）：主色
            //   第2层 矿石阴影（格雷ore_layer2）：辅色
            //   第3层 矿石高光（最亮像素提取）：不染色白色光泽
            if (path.endsWith("_ore")) {
                String material;
                Integer baseTint = null;
                if (path.startsWith("deepslate_")) {
                    material = path.substring("deepslate_".length(), path.length() - "_ore".length());
                    Integer[] deepslateColor = MATERIAL_COLORS.get("deepslate");
                    if (deepslateColor != null && deepslateColor.length > 0) {
                        baseTint = deepslateColor[0];
                    }
                } else {
                    // 岩种矿：去掉尾部 _{rock}_ore；否则为石头矿 {mineral}_ore
                    material = null;
                    for (com.mss.polymech.worldgen.ModRocks.RockType rock : com.mss.polymech.worldgen.ModRocks.ROCK_TYPES) {
                        String suffix = "_" + rock.name() + "_ore";
                        if (path.endsWith(suffix)) {
                            material = path.substring(0, path.length() - suffix.length());
                            break;
                        }
                    }
                    if (material == null) {
                        material = path.substring(0, path.length() - "_ore".length());
                    }
                }
                Integer[] base = MATERIAL_COLORS.get(material);
                if (base != null && base.length >= 2) {
                    // 高光色：显式配置的高光色发白则弃用，否则尊重；缺失/发白时自动从主色提亮。
                    // 这修复了矿石高光贴图呈"诡异白色斑点"的问题——高光应该是一层更亮的矿石色，
                    // 而不是纯白覆盖层（基于贴图透明度 + 主色提亮混合）。
                    // 专用高光色染色（与齿轮/锭物品模板一致）：colors[2] 就是高光色，
                    // 高光贴图本身半透明，用 translucent 渲染后与底色自动 alpha 混合。
                    // 宝石材料（diamond/garnet等）orders 为 主色/白色高光/暗部，colors[2]是暗部，
                    // 所以宝石高光色保持 null（白色半透明光泽），由高光贴图透明度混色。
                    boolean gem = com.mss.polymech.api.material.GemMaterials.hasGem(material);
                    Integer[] colors = gem
                            ? new Integer[]{baseTint, base[0], base.length > 2 ? base[2] : null, null}
                            : new Integer[]{baseTint, base[0], base[1], base.length > 2 ? base[2] : null};
                    BLOCK_COLOR_CACHE.put(block, colors);
                    Polymech.LOGGER.debug("Derived ore block color for {}: material={}", path, material);
                    return colors;
                }
            }

            // 尝试从方块ID中提取材料名
            String materialName = extractMaterialName(path);
            if (materialName != null && MATERIAL_COLORS.containsKey(materialName)) {
                Integer[] colors = MATERIAL_COLORS.get(materialName);
                // 缓存自动推断的结果
                BLOCK_COLOR_CACHE.put(block, colors);
                Polymech.LOGGER.debug("Auto-inferred color for block {}: material={}", path, materialName);
                return colors;
            }
        }
        
        return null;
    }

    /*
     * 在材质颜色数组前面加一个null（不染色层）。
     * <p>
     * 用于线轴等多层模板：底层空线轴不染色（null），
     * 后续各层按金属材质染色，与模型tintindex逐层对应。
     * </p>
     */
    private static Integer[] withUntintedFirstLayer(Integer[] base) {
        Integer[] colors = new Integer[base.length + 1];
        colors[0] = null;
        System.arraycopy(base, 0, colors, 1, base.length);
        return colors;
    }

    /*
     * 在颜色数组末尾追加一个null（不染色层）。
     * <p>
     * 用于绝缘线轴：模型最后一层为 Insulated_logo 标识层，
     * 该层应保持贴图原样显示，不参与金属染色。
     * </p>
     */
    private static Integer[] appendUntintedLayer(Integer[] base) {
        Integer[] colors = new Integer[base.length + 1];
        System.arraycopy(base, 0, colors, 0, base.length);
        colors[base.length] = null;
        return colors;
    }

    /*
     * 把颜色数组整体加深（RGB各通道×factor，null层保持null）。
     * 用于绝缘线轴：与世界内绝缘电线的加深系数保持一致。
     */
    private static Integer[] darkenColors(Integer[] base, float factor) {
        Integer[] colors = new Integer[base.length];
        for (int i = 0; i < base.length; i++) {
            colors[i] = base[i] == null ? null : GridWireType.darken(base[i], factor);
        }
        return colors;
    }

    /*
     * 从物品/方块ID路径中提取材料名。
     * <p>
     * 支持的格式：
     * - {material}_ingot -> material
     * - {material}_alloy_ingot -> material
     * - {material}_dust -> material
     * - {material}_plate -> material
     * - {material}_nugget -> material
     * - raw_{material} -> material
     * - {material}_pipe -> material
     * </p>
     * 
     * @param path 物品/方块ID的路径部分
     * @return 材料名，如果无法提取则返回null
     */
    private static String extractMaterialName(String path) {
        // 优先完整路径直接匹配（如 steel_block → steel_block 专属方块配色、coke）
        if (MATERIAL_COLORS.containsKey(path)) {
            return path;
        }

        // 处理 raw_ 前缀
        if (path.startsWith("raw_")) {
            String material = path.substring(4);
            if (MATERIAL_COLORS.containsKey(material)) {
                return material;
            }
        }
        
        // 处理 _alloy_ingot 后缀
        if (path.endsWith("_alloy_ingot")) {
            String material = path.substring(0, path.length() - 12);
            if (MATERIAL_COLORS.containsKey(material)) {
                return material;
            }
        }
        
        // 处理常见后缀
        String[] suffixes = {"_ingot", "_dust", "_plate", "_nugget", "_stick", "_gear", 
                            "_small_gear", "_spring", "_screw", "_bolt", "_ring", "_foil",
                            "_gem", "_pipe", "_small_pipe", "_big_pipe", "_huge_pipe", "_block", "_wire",
                            "_crushed", "_purified"};
        
        for (String suffix : suffixes) {
            if (path.endsWith(suffix)) {
                String material = path.substring(0, path.length() - suffix.length());
                if (MATERIAL_COLORS.containsKey(material)) {
                    return material;
                }
            }
        }
        
        // 尝试直接匹配（用于像 "steel" 这样的简单名称）
        if (MATERIAL_COLORS.containsKey(path)) {
            return path;
        }
        
        return null;
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