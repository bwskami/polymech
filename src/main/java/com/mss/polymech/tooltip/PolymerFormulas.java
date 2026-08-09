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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聚合物重复单元结构注册表。
 * <p>
 * 数据来自资源文件 {@code assets/poly_mech/config/polymer_formulas.json}
 * （键=流体id或材料名，值=重复单元SMILES+可选链锚点，可被资源包覆盖）。
 * 聚合物与单体常共用化学式（如乙烯与聚乙烯都是C2H4），故按键（物质id）
 * 而非化学式查询。渲染时重复单元外围绘制"[ ]"括号与右下角"n"，
 * 锚点原子穿出括号画链延续键（见{@link SmilesStructures}）。
 * 客户端懒加载（tooltip事件只在客户端发生）。
 * </p>
 */
public final class PolymerFormulas {

    private static final Logger LOGGER = LogManager.getLogger(PolymerFormulas.class);

    /** 物质id → 重复单元SMILES（可含"|锚点"后缀） */
    private static final Map<String, String> SMILES = new LinkedHashMap<>();
    /** 是否已尝试加载JSON配置（每进程仅一次，缺文件时不重试） */
    private static boolean configLoaded = false;

    private PolymerFormulas() {
    }

    /** 客户端懒加载聚合物注册表（dist检查保证专用服务端不会执行） */
    private static void loadConfigIfNeeded() {
        if (configLoaded) return;
        configLoaded = true;
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        var optional = mc.getResourceManager().getResource(
                ResourceLocation.parse(Polymech.MOD_ID + ":config/polymer_formulas.json"));
        if (optional.isEmpty()) {
            LOGGER.warn("未找到聚合物重复单元注册表，聚合物结构式将不显示");
            return;
        }
        try (var reader = new InputStreamReader(optional.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            for (var entry : root.entrySet()) {
                if (entry.getKey().startsWith("comment")) continue;
                try {
                    SMILES.put(entry.getKey(), entry.getValue().getAsString());
                } catch (Exception e) {
                    LOGGER.warn("聚合物注册条目解析失败 [{}]: {}", entry.getKey(), e.toString());
                }
            }
            LOGGER.info("聚合物重复单元注册表已加载：{} 个物质", SMILES.size());
        } catch (Exception e) {
            LOGGER.error("加载聚合物重复单元注册表失败", e);
        }
    }

    /**
     * 按物质id查询聚合物重复单元结构（CDK从SMILES懒加载生成并缓存，含链锚点）；
     * 未登记或生成失败返回null。
     */
    public static MoleculeStructure get(String id) {
        loadConfigIfNeeded();
        String smiles = SMILES.get(id);
        if (smiles == null) return null;
        return SmilesStructures.getPolymer(id, smiles);
    }
}
