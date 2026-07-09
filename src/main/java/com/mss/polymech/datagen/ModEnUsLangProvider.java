package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.item.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModEnUsLangProvider extends LanguageProvider {
    public ModEnUsLangProvider(PackOutput output) {
        super(output, Polymech.MOD_ID, "en_us");
    }

    @Override
    protected void addTranslations() {
        // 数据驱动的材料物品翻译
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            // 锭
            var ingotItem = ModItems.getMaterialItem(ModItemTypes.INGOT, materialName);
            if (ingotItem != null) {
                String displayName = formatMaterialName(materialName) + " Ingot";
                add(ingotItem.get(), displayName);
            }
            
            // 粗矿（仅test材料有）
            if ("test".equals(materialName)) {
                var rawItem = ModItems.getMaterialItem(ModItemTypes.RAW_ORE, materialName);
                if (rawItem != null) {
                    add(rawItem.get(), "Test Raw");
                }
            }
        }
        
        // 测试物品
        for (int i = 1; i <= 3; i++) {
            var testItem = ModItems.getMaterialItem(ModItemTypes.TEST_ITEM, String.valueOf(i));
            if (testItem != null) {
                add(testItem.get(), "Test Item " + i);
            }
        }
        
        add(ModItems.WRENCH.get(), "Wrench");

        add(ModBlocks.COKE_OVEN_BRICK.get(), "Coke Oven Brick");
        add(ModBlocks.TEST_ORE.get(), "Test Ore");
        add(ModBlocks.FLUID_TANK.get(), "Fluid Tank");

        for (var materialEntry : ModBlocks.PIPE_TABLE.entrySet()) {
            PipeMaterial material = materialEntry.getKey();
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                PipeBlock.PipeSize size = sizeEntry.getKey();
                String displayName = buildDisplayName(material, size);
                add(sizeEntry.getValue().get(), displayName);
            }
        }

        add("itemGroup.material_tab", "Ploy Mech:Material");
        add("itemGroup.block_tab", "Ploy Mech:Block");
        add("itemGroup.pipe_tab", "Ploy Mech:Pipes");
        add("itemGroup.tool_tab", "Ploy Mech:Tool");
    }

    private String formatMaterialName(String name) {
        StringBuilder result = new StringBuilder();
        boolean capitalizeNext = true;
        for (char c : name.toCharArray()) {
            if (c == '_') {
                capitalizeNext = true;
            } else {
                result.append(capitalizeNext ? Character.toUpperCase(c) : c);
                capitalizeNext = false;
            }
        }
        return result.toString();
    }

    private String buildDisplayName(PipeMaterial material, PipeBlock.PipeSize size) {
        String materialName = switch (material) {
            case IRON -> "";
            case BRONZE -> "Bronze ";
            case STAINLESS_STEEL -> "Stainless Steel ";
            case BRASS -> "Brass ";
        };
        String sizeName = switch (size) {
            case SMALL -> "Small Pipe";
            case BIG   -> "Big Pipe";
            case HUGE  -> "Huge Pipe";
            default    -> "Pipe";
        };
        return materialName + sizeName;
    }
}
