package com.mss.polymech.texture_data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.InputStreamReader;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class ColorConfigLoader {
    private static final Gson GSON = new Gson();
    private static final Map<Item, Integer[]> COLOR_CACHE = new HashMap<>();
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
            JsonObject itemsObj = root.getAsJsonObject("items");
            for (var entry : itemsObj.entrySet()) {
                String itemName = entry.getKey();
                var colorsArray = entry.getValue().getAsJsonObject().getAsJsonArray("colors");
                ResourceLocation itemId = ResourceLocation.parse(Polymech.MOD_ID + ":" + itemName);
                Item item = BuiltInRegistries.ITEM.get(itemId);
                if (item == null || item == Items.AIR) {
                    Polymech.LOGGER.warn("Item {} not found, skipping color config", itemName);
                    continue;
                }
                Integer[] colors = new Integer[colorsArray.size()];
                for (int i = 0; i < colorsArray.size(); i++) {
                    if (colorsArray.get(i).isJsonNull()) {
                        colors[i] = null;
                        Polymech.LOGGER.debug("Layer {} of item {} set to no-tint", i, itemName);
                        continue;
                    }
                    String hex = colorsArray.get(i).getAsString();
                    if (hex.startsWith("#")) {
                        hex = hex.substring(1);
                    }
                    colors[i] = (int) Long.parseLong(hex, 16);
                }
                COLOR_CACHE.put(item, colors);
                Polymech.LOGGER.info("Loaded colors for item {}: {}", itemName, colors);
            }
            loaded = true;
            Polymech.LOGGER.info("Color config loaded successfully!");
        } catch (Exception e) {
            Polymech.LOGGER.error("Failed to load color config", e);
        }
    }

    public static Integer[] getColors(Item item) {
        Integer[] colors = COLOR_CACHE.get(item);
        if (colors == null) {
            Polymech.LOGGER.debug("No colors found for item: {}", BuiltInRegistries.ITEM.getKey(item));
        }
        return colors;
    }

    public static void reload() {
        Polymech.LOGGER.info("Reloading color config...");
        COLOR_CACHE.clear();
        loaded = false;
        load();
        Polymech.LOGGER.info("Color config reloaded! Cache size: {}", COLOR_CACHE.size());
    }
}