package com.mss.polymech.texture_data;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * colors.json 材料底色查询（通用端，客户端/服务端均可用）。
 * <p>
 * 与{@link ColorConfigLoader}（依赖客户端资源管理器、只能在客户端初始化后使用）不同，
 * 本类直接从类路径解析 {@code assets/poly_mech/config/colors.json}，
 * 因此可以在流体注册等静态初始化阶段安全调用（包括专用服务端）。
 * </p>
 * <p>
 * 底色定义为 materials 段中该材料 colors 数组的第一个非空颜色，
 * 与金属锭等物品渲染使用的底色一致；alpha 通道强制为 0xFF（配置中部分条目
 * 的 alpha 小于 ff，仅用于物品图层叠加，流体染色需要不透明底色）。
 * </p>
 */
public class MaterialColorConfig {

    /** 材料名 -> 底色（0xFFRRGGBB） */
    private static volatile Map<String, Integer> baseColors;

    /**
     * 获取材料底色；配置中不存在该材料时返回 fallback。
     *
     * @param materialName 材料名（如 steel、iron）
     * @param fallback     找不到时的回退颜色（0xRRGGBB）
     */
    public static int getBaseColor(String materialName, int fallback) {
        Integer color = getBaseColorOrNull(materialName);
        return color == null ? (0xFF000000 | (fallback & 0xFFFFFF)) : color;
    }

    /** 获取材料底色；配置中不存在该材料时返回 null */
    public static Integer getBaseColorOrNull(String materialName) {
        Map<String, Integer> map = baseColors;
        if (map == null) {
            synchronized (MaterialColorConfig.class) {
                if ((map = baseColors) == null) {
                    map = load();
                    baseColors = map;
                }
            }
        }
        return map.get(materialName);
    }

    /** 从类路径解析 colors.json 的 materials 段，取每个材料 colors 数组的第一个非空颜色 */
    private static Map<String, Integer> load() {
        Map<String, Integer> result = new HashMap<>();
        try (var in = MaterialColorConfig.class.getResourceAsStream("/assets/poly_mech/config/colors.json")) {
            if (in == null) {
                Polymech.LOGGER.warn("colors.json not found on classpath, fluid base colors unavailable");
                return result;
            }
            JsonObject root = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            if (root.has("materials")) {
                for (var entry : root.getAsJsonObject("materials").entrySet()) {
                    var colorsArray = entry.getValue().getAsJsonObject().getAsJsonArray("colors");
                    for (int i = 0; i < colorsArray.size(); i++) {
                        if (colorsArray.get(i).isJsonNull()) {
                            continue;
                        }
                        String hex = colorsArray.get(i).getAsString();
                        if (hex.startsWith("#")) {
                            hex = hex.substring(1);
                        }
                        int argb = (int) Long.parseLong(hex, 16);
                        result.put(entry.getKey(), 0xFF000000 | (argb & 0xFFFFFF));
                        break;
                    }
                }
            }
            Polymech.LOGGER.info("Loaded {} material base colors from colors.json", result.size());
        } catch (Exception e) {
            Polymech.LOGGER.error("Failed to load colors.json base colors", e);
        }
        return result;
    }
}
