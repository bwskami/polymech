package com.mss.polymech.tooltip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;
import com.mss.polymech.fluid.ModElements;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 元素周期表配色表（tooltip中元素符号染色用，参考GregTech材料色风格）。
 * <p>
 * 颜色从资源文件 {@code assets/poly_mech/config/element_colors.json} 加载
 * （符号→十六进制颜色，可被资源包覆盖）；未在JSON登记的元素按原子序数
 * 黄金分割色相自动生成兜底色，保证118种元素在tooltip中都有互不相同且可读的颜色。
 * </p>
 */
public final class ElementColors {

    private static final Logger LOGGER = LogManager.getLogger(ElementColors.class);

    /** 符号 → ModElements 反查表 */
    private static final Map<String, ModElements> BY_SYMBOL = new HashMap<>();
    /** JSON配置指定的元素颜色（符号 → RGB） */
    private static final Map<String, Integer> CURATED = new HashMap<>();
    /** 最终颜色缓存（含自动生成的部分） */
    private static final Map<String, Integer> COLORS = new HashMap<>();
    /** 是否已尝试加载JSON配置（每进程仅一次，缺文件时不重试） */
    private static boolean configLoaded = false;

    static {
        for (ModElements element : ModElements.values()) {
            BY_SYMBOL.put(element.getSymbol(), element);
        }
    }

    private ElementColors() {
    }

    /** 客户端懒加载颜色配置（tooltip事件只在客户端发生，dist检查保证专用服务端不会执行） */
    private static void loadConfigIfNeeded() {
        if (configLoaded) return;
        configLoaded = true;
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        var optional = mc.getResourceManager().getResource(
                ResourceLocation.parse(Polymech.MOD_ID + ":config/element_colors.json"));
        if (optional.isEmpty()) {
            LOGGER.warn("未找到元素配色配置，全部使用自动生成的兜底颜色");
            return;
        }
        try (var reader = new InputStreamReader(optional.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            for (var entry : root.entrySet()) {
                if ("comment".equals(entry.getKey())) continue;
                try {
                    String hex = entry.getValue().getAsString();
                    if (hex.startsWith("#")) hex = hex.substring(1);
                    CURATED.put(entry.getKey(), (int) Long.parseLong(hex, 16));
                } catch (Exception e) {
                    LOGGER.warn("元素配色条目解析失败 [{}]: {}", entry.getKey(), e.toString());
                }
            }
            COLORS.clear();
            LOGGER.info("元素配色配置已加载：{} 个元素", CURATED.size());
        } catch (Exception e) {
            LOGGER.error("加载元素配色配置失败", e);
        }
    }

    /** 按符号反查元素定义；未知符号返回null */
    public static ModElements bySymbol(String symbol) {
        return BY_SYMBOL.get(symbol);
    }

    /**
     * 获取元素符号对应的显示颜色（RGB）。
     * JSON配置指定者优先；否则按原子序数黄金分割色相自动生成，118元素互不相同。
     */
    public static int getColor(String symbol) {
        loadConfigIfNeeded();
        Integer cached = COLORS.get(symbol);
        if (cached != null) return cached;
        Integer curated = CURATED.get(symbol);
        int color;
        if (curated != null) {
            color = curated;
        } else {
            ModElements element = BY_SYMBOL.get(symbol);
            int seed = element != null ? element.ordinal() : Math.abs(symbol.hashCode());
            float hue = (float) ((seed * 0.6180339887) % 1.0);
            color = 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.55f, 0.9f) & 0x00FFFFFF);
        }
        COLORS.put(symbol, color);
        return color;
    }
}
