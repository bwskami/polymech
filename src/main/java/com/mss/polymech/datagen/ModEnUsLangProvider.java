package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
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
        add(ModBlocks.PIPE.get(), "Pipe");
        add(ModBlocks.SMALL_PIPE.get(), "Small Pipe");
        add(ModBlocks.BIG_PIPE.get(), "Big Pipe");
        add(ModBlocks.HUGE_PIPE.get(), "Huge Pipe");

        add("itemGroup.material_tab", "Ploy Mech:Material");
        add("itemGroup.block_tab", "Ploy Mech:Block");
        add("itemGroup.tool_tab", "Ploy Mech:Tool");
    }
}
