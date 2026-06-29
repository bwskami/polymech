package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.item.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class ModZhCnLangProvider extends LanguageProvider {
    public ModZhCnLangProvider(PackOutput output) {
        super(output, Polymech.MOD_ID, "zh_cn");
    }

    @Override
    protected void addTranslations() {
        add(ModItems.STEEL_INGOT.get(), "钢锭");
        add(ModItems.TEST_ITEM1.get(), "测试物品1");
        add(ModItems.TEST_ITEM2.get(), "测试物品2");
        add(ModItems.TEST_ITEM3.get(), "测试物品3");

        add(ModBlocks.COKE_OVEN_BRICK.get(), "焦炉砖");

        add("itemGroup.material_tab", "Ploy Mech:材料");
        add("itemGroup.block_tab", "Ploy Mech:方块");
    }
}
