package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
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
        add(ModItems.STEEL_INGOT.get(), "Steel Ingot");
        add(ModItems.TEST_ITEM1.get(), "Test Item 1");
        add(ModItems.TEST_ITEM2.get(), "Test Item 2");
        add(ModItems.TEST_ITEM3.get(), "Test Item 3");
        add(ModItems.TEST_INGOT.get(), "Test Ingot");
        add(ModItems.BRASS_INGOT.get(), "Brass Ingot");
        add(ModItems.BRONZE_INGOT.get(), "Bronze Ingot");
        add(ModItems.TEST_RAW.get(), "Test Raw");
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
