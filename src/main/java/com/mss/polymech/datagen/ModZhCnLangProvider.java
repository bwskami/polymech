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
        add(ModItems.TEST_INGOT.get(), "测试锭");
        add(ModItems.TEST_RAW.get(), "测试粗矿");

        add(ModBlocks.COKE_OVEN_BRICK.get(), "焦炉砖");
        add(ModBlocks.TEST_ORE.get(), "测试原矿");
        add(ModBlocks.FLUID_TANK.get(), "流体储罐");
        
        // 普通管道
        add(ModBlocks.PIPE.get(), "管道");
        add(ModBlocks.SMALL_PIPE.get(), "小型管道");
        add(ModBlocks.BIG_PIPE.get(), "大型管道");
        add(ModBlocks.HUGE_PIPE.get(), "巨型管道");
        
        // 青铜管道
        add(ModBlocks.BRONZE_PIPE.get(), "青铜管道");
        add(ModBlocks.BRONZE_SMALL_PIPE.get(), "青铜小型管道");
        add(ModBlocks.BRONZE_BIG_PIPE.get(), "青铜大型管道");
        add(ModBlocks.BRONZE_HUGE_PIPE.get(), "青铜巨型管道");
        
        // 不锈钢管道
        add(ModBlocks.STAINLESS_STEEL_PIPE.get(), "不锈钢管道");
        add(ModBlocks.STAINLESS_STEEL_SMALL_PIPE.get(), "不锈钢小型管道");
        add(ModBlocks.STAINLESS_STEEL_BIG_PIPE.get(), "不锈钢大型管道");
        add(ModBlocks.STAINLESS_STEEL_HUGE_PIPE.get(), "不锈钢巨型管道");
        
        // 黄铜管道
        add(ModBlocks.BRASS_PIPE.get(), "黄铜管道");
        add(ModBlocks.BRASS_SMALL_PIPE.get(), "黄铜小型管道");
        add(ModBlocks.BRASS_BIG_PIPE.get(), "黄铜大型管道");
        add(ModBlocks.BRASS_HUGE_PIPE.get(), "黄铜巨型管道");

        add("itemGroup.material_tab", "Ploy Mech:材料");
        add("itemGroup.block_tab", "Ploy Mech:方块");
        add("itemGroup.pipe_tab", "Ploy Mech:管道");
        add("itemGroup.tool_tab", "Ploy Mech:工具");
    }
}
