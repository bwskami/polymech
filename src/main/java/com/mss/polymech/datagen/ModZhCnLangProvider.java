package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
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
