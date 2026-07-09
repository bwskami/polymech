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
        
        // 普通管道
        add(ModBlocks.PIPE.get(), "Pipe");
        add(ModBlocks.SMALL_PIPE.get(), "Small Pipe");
        add(ModBlocks.BIG_PIPE.get(), "Big Pipe");
        add(ModBlocks.HUGE_PIPE.get(), "Huge Pipe");
        
        // 青铜管道
        add(ModBlocks.BRONZE_PIPE.get(), "Bronze Pipe");
        add(ModBlocks.BRONZE_SMALL_PIPE.get(), "Bronze Small Pipe");
        add(ModBlocks.BRONZE_BIG_PIPE.get(), "Bronze Big Pipe");
        add(ModBlocks.BRONZE_HUGE_PIPE.get(), "Bronze Huge Pipe");
        
        // 不锈钢管道
        add(ModBlocks.STAINLESS_STEEL_PIPE.get(), "Stainless Steel Pipe");
        add(ModBlocks.STAINLESS_STEEL_SMALL_PIPE.get(), "Stainless Steel Small Pipe");
        add(ModBlocks.STAINLESS_STEEL_BIG_PIPE.get(), "Stainless Steel Big Pipe");
        add(ModBlocks.STAINLESS_STEEL_HUGE_PIPE.get(), "Stainless Steel Huge Pipe");
        
        // 黄铜管道
        add(ModBlocks.BRASS_PIPE.get(), "Brass Pipe");
        add(ModBlocks.BRASS_SMALL_PIPE.get(), "Brass Small Pipe");
        add(ModBlocks.BRASS_BIG_PIPE.get(), "Brass Big Pipe");
        add(ModBlocks.BRASS_HUGE_PIPE.get(), "Brass Huge Pipe");

        add("itemGroup.material_tab", "Ploy Mech:Material");
        add("itemGroup.block_tab", "Ploy Mech:Block");
        add("itemGroup.pipe_tab", "Ploy Mech:Pipes");
        add("itemGroup.tool_tab", "Ploy Mech:Tool");
    }
}
