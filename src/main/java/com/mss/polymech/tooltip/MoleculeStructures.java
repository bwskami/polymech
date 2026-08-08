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
 * 化学式 → SMILES 注册表。
 * <p>
 * 数据来自资源文件 {@code assets/poly_mech/config/molecule_smiles.json}
 * （化学式→Canonical SMILES，可查PubChem核对，可被资源包覆盖），
 * 新增物质只需编辑JSON，无需改代码。客户端懒加载（tooltip事件只在客户端发生）。
 * </p>
 */
public final class MoleculeStructures {

    private static final Logger LOGGER = LogManager.getLogger(MoleculeStructures.class);

    /** 化学式 → SMILES（Canonical SMILES，可查PubChem） */
    private static final Map<String, String> SMILES = new LinkedHashMap<>();
    /** 是否已尝试加载JSON配置（每进程仅一次，缺文件时不重试） */
    private static boolean configLoaded = false;

    private MoleculeStructures() {
    }

    /** 客户端懒加载SMILES注册表（dist检查保证专用服务端不会执行） */
    private static void loadConfigIfNeeded() {
        if (configLoaded) return;
        configLoaded = true;
        if (FMLEnvironment.dist != Dist.CLIENT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        var optional = mc.getResourceManager().getResource(
                ResourceLocation.parse(Polymech.MOD_ID + ":config/molecule_smiles.json"));
        if (optional.isEmpty()) {
            LOGGER.warn("未找到分子结构式SMILES注册表，结构式tooltip将不显示");
            return;
        }
        try (var reader = new InputStreamReader(optional.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(reader, JsonObject.class);
            for (var entry : root.entrySet()) {
                if (entry.getKey().startsWith("comment")) continue;
                try {
                    SMILES.put(entry.getKey(), entry.getValue().getAsString());
                } catch (Exception e) {
                    LOGGER.warn("SMILES注册条目解析失败 [{}]: {}", entry.getKey(), e.toString());
                }
            }
            LOGGER.info("SMILES注册表已加载：{} 个物质", SMILES.size());
        } catch (Exception e) {
            LOGGER.error("加载SMILES注册表失败", e);
        }
    }

    /** 按化学式查询结构（CDK从SMILES懒加载生成并缓存）；未登记或生成失败返回null */
    public static MoleculeStructure get(String formula) {
        loadConfigIfNeeded();
        String smiles = SMILES.get(formula);
        if (smiles == null) return null;
        return SmilesStructures.get(formula, smiles);
    }
}
