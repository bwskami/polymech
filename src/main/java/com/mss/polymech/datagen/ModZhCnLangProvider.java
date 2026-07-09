package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.item.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

import java.util.Map;

public class ModZhCnLangProvider extends LanguageProvider {
    public ModZhCnLangProvider(PackOutput output) {
        super(output, Polymech.MOD_ID, "zh_cn");
    }

    private static final Map<PipeMaterial, String> MATERIAL_ZH = Map.of(
            PipeMaterial.IRON, "",
            PipeMaterial.BRONZE, "青铜",
            PipeMaterial.STAINLESS_STEEL, "不锈钢",
            PipeMaterial.BRASS, "黄铜"
    );

    private static final Map<PipeBlock.PipeSize, String> SIZE_ZH = Map.of(
            PipeBlock.PipeSize.NORMAL, "管道",
            PipeBlock.PipeSize.SMALL, "小型管道",
            PipeBlock.PipeSize.BIG, "大型管道",
            PipeBlock.PipeSize.HUGE, "巨型管道"
    );

    private static final Map<String, String> MATERIAL_ZH_NAMES = Map.ofEntries(
            Map.entry("steel", "钢"),
            Map.entry("aluminium", "铝"),
            Map.entry("nickel", "镍"),
            Map.entry("tin", "锡"),
            Map.entry("zinc", "锌"),
            Map.entry("brass", "黄铜"),
            Map.entry("bronze", "青铜"),
            Map.entry("ivar", "殷钢"),
            Map.entry("cupronickel", "白铜"),
            Map.entry("stainless_steel", "不锈钢"),
            Map.entry("test", "测试")
    );

    @Override
    protected void addTranslations() {
        // 数据驱动的材料物品翻译
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            String zhName = MATERIAL_ZH_NAMES.getOrDefault(materialName, materialName);
            
            // 锭
            var ingotItem = ModItems.getMaterialItem(ModItemTypes.INGOT, materialName);
            if (ingotItem != null) {
                add(ingotItem.get(), zhName + "锭");
            }
            
            // 粗矿（仅test材料有）
            if ("test".equals(materialName)) {
                var rawItem = ModItems.getMaterialItem(ModItemTypes.RAW_ORE, materialName);
                if (rawItem != null) {
                    add(rawItem.get(), "测试粗矿");
                }
            }
        }
        
        // 测试物品
        for (int i = 1; i <= 3; i++) {
            var testItem = ModItems.getMaterialItem(ModItemTypes.TEST_ITEM, String.valueOf(i));
            if (testItem != null) {
                add(testItem.get(), "测试物品" + i);
            }
        }

        add(ModBlocks.COKE_OVEN_BRICK.get(), "焦炉砖");
        add(ModBlocks.TEST_ORE.get(), "测试原矿");
        add(ModBlocks.FLUID_TANK.get(), "流体储罐");

        for (var materialEntry : ModBlocks.PIPE_TABLE.entrySet()) {
            PipeMaterial material = materialEntry.getKey();
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                PipeBlock.PipeSize size = sizeEntry.getKey();
                String name = MATERIAL_ZH.get(material) + SIZE_ZH.get(size);
                add(sizeEntry.getValue().get(), name);
            }
        }

        add("itemGroup.material_tab", "Ploy Mech:材料");
        add("itemGroup.block_tab", "Ploy Mech:方块");
        add("itemGroup.pipe_tab", "Ploy Mech:管道");
        add("itemGroup.tool_tab", "Ploy Mech:工具");
    }
}
