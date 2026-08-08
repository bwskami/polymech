package com.mss.polymech.tooltip;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mss.polymech.Polymech;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 化学式 → 离子式注册表（GTM风格的离子组成表示）。
 * <p>
 * 数据来自资源文件 {@code assets/poly_mech/config/ion_formulas.json}
 * （中性化学式→离子式字符串，可被资源包覆盖），客户端懒加载（tooltip事件只在客户端发生）。
 * 离子式语法：若干个离子段，电荷在括号内（{@code [SO42-]}，GTM盐类风格）
 * 或括号外（{@code [H2F]+}，GTM酸类风格）均可；括号外末尾数字为该离子的个数（省略为1）；
 * 电荷数字在前符号在后（{@code 2-}表示带2个负电荷）；{@code .} 后可接结晶水/溶剂分子
 * （如稀盐酸的 {@code .H2O}）。
 * </p>
 */
public final class IonFormulas {

    private static final Logger LOGGER = LogManager.getLogger(IonFormulas.class);

    /** 括号内尾部电荷匹配（如 "SO42-" 的 "2-"） */
    private static final Pattern INNER_CHARGE = Pattern.compile("(\\d*)([+-])$");

    /** 化学式 → 离子式字符串 */
    private static final Map<String, String> IONIC = new LinkedHashMap<>();
    /** 是否已尝试加载JSON配置（每进程仅一次，缺文件时不重试） */
    private static boolean configLoaded = false;

    private IonFormulas() {
    }

    /**
     * 单个离子段。
     *
     * @param formula 离子本体化学式（不含电荷与个数，如 "SbF6"、"H2F"、"K"）
     * @param charge  净电荷（正为正数、负为负数，如 "SbF6-" 为 -1）
     * @param count   离子个数（省略时为1，如 "[K+]2" 的 2）
     */
    public record Ion(String formula, int charge, int count) {
    }

    /** 客户端懒加载离子式注册表（dist检查保证专用服务端不会执行） */
    private static void loadConfigIfNeeded() {
        if (configLoaded) return;
        configLoaded = true;
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        var optional = mc.getResourceManager().getResource(
                ResourceLocation.parse(Polymech.MOD_ID + ":config/ion_formulas.json"));
        if (optional.isEmpty()) return; // 可选配置，缺失时静默跳过
        try (var reader = new InputStreamReader(optional.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            for (var entry : root.entrySet()) {
                if (entry.getKey().startsWith("comment")) continue;
                try {
                    IONIC.put(entry.getKey(), entry.getValue().getAsString());
                } catch (Exception e) {
                    LOGGER.warn("离子式注册条目解析失败 [{}]: {}", entry.getKey(), e.toString());
                }
            }
            LOGGER.info("离子式注册表已加载：{} 个物质", IONIC.size());
        } catch (Exception e) {
            LOGGER.error("加载离子式注册表失败", e);
        }
    }

    /** 按中性化学式查询离子式字符串；未登记返回null */
    public static String get(String formula) {
        loadConfigIfNeeded();
        return IONIC.get(formula);
    }

    /**
     * 解析离子式字符串为离子段列表（仅离子段，不含 "." 分隔的溶剂分子）。
     * 语法非法时记录警告并返回空列表。
     */
    public static List<Ion> parse(String ionic) {
        if (ionic == null || ionic.isEmpty()) return Collections.emptyList();
        List<Ion> ions = new ArrayList<>();
        int i = 0;
        int len = ionic.length();
        while (i < len) {
            char c = ionic.charAt(i);
            if (c == '.') break; // 溶剂分子（如结晶水）不参与离子列表
            if (c != '[') {
                LOGGER.warn("离子式语法非法（缺少'['）[{}]", ionic);
                return Collections.emptyList();
            }
            int close = ionic.indexOf(']', i);
            if (close < 0) {
                LOGGER.warn("离子式语法非法（缺少']'）[{}]", ionic);
                return Collections.emptyList();
            }
            String inner = ionic.substring(i + 1, close);
            i = close + 1;
            // 电荷在括号内（[SO42-]）或括号外（[H2F]+）两种写法均支持
            int magnitude = 0;
            boolean signed = false;
            boolean positive = true;
            Matcher m = INNER_CHARGE.matcher(inner);
            if (m.find()) {
                magnitude = m.group(1).isEmpty() ? 0 : Integer.parseInt(m.group(1));
                positive = "+".equals(m.group(2));
                signed = true;
            } else {
                // 括号外电荷：可选倍数数字 + 符号（2-、3+、+、-）
                int[] num = readNumber(ionic, i);
                magnitude = num[0];
                i = num[1];
                if (i >= len || (ionic.charAt(i) != '+' && ionic.charAt(i) != '-')) {
                    LOGGER.warn("离子式语法非法（缺少电荷符号）[{}]", ionic);
                    return Collections.emptyList();
                }
                positive = ionic.charAt(i) == '+';
                signed = true;
                i++;
            }
            if (!signed) {
                LOGGER.warn("离子式语法非法（缺少电荷）[{}]", ionic);
                return Collections.emptyList();
            }
            int charge = positive ? Math.max(1, magnitude) : -Math.max(1, magnitude);
            // 离子个数：括号外紧跟的数字（省略为1）
            int[] num = readNumber(ionic, i);
            int count = num[0] == 0 ? 1 : num[0];
            i = num[1];
            ions.add(new Ion(stripCharge(inner), charge, count));
        }
        return ions;
    }

    /** 从指定位置读取连续数字；返回 {数值(无数字为0), 新下标} */
    private static int[] readNumber(String s, int i) {
        int start = i;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        return new int[]{i == start ? 0 : Integer.parseInt(s.substring(start, i)), i};
    }

    /** 去掉括号内尾部的电荷标记，如 "SO42-" -> "SO4" */
    public static String stripCharge(String inner) {
        Matcher m = INNER_CHARGE.matcher(inner);
        return m.find() ? inner.substring(0, m.start()) : inner;
    }
}
