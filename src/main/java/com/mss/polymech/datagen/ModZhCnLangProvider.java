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
            
            // 合金锭 这个的翻译成锭就好了，是不是合金锭只需要开发者知道就可以了
            var alloyIngotItem = ModItems.getMaterialItem(ModItemTypes.ALLOY_INGOT, materialName);
            if (alloyIngotItem != null) {
                add(alloyIngotItem.get(), zhName + "锭");
            }
            
            // 粒
            var nuggetItem = ModItems.getMaterialItem(ModItemTypes.NUGGET, materialName);
            if (nuggetItem != null) {
                add(nuggetItem.get(), zhName + "粒");
            }
            
            // 粉
            var dustItem = ModItems.getMaterialItem(ModItemTypes.DUST, materialName);
            if (dustItem != null) {
                add(dustItem.get(), zhName + "粉");
            }
            
            // 板
            var plateItem = ModItems.getMaterialItem(ModItemTypes.PLATE, materialName);
            if (plateItem != null) {
                add(plateItem.get(), zhName + "板");
            }
            
            // 箔
            var foilItem = ModItems.getMaterialItem(ModItemTypes.FOIL, materialName);
            if (foilItem != null) {
                add(foilItem.get(), zhName + "箔");
            }
            
            // 杆
            var stickItem = ModItems.getMaterialItem(ModItemTypes.STICK, materialName);
            if (stickItem != null) {
                add(stickItem.get(), zhName + "杆");
            }
            
            // 齿轮
            var gearItem = ModItems.getMaterialItem(ModItemTypes.GEAR, materialName);
            if (gearItem != null) {
                add(gearItem.get(), zhName + "齿轮");
            }
            
            // 小齿轮
            var smallGearItem = ModItems.getMaterialItem(ModItemTypes.SMALL_GEAR, materialName);
            if (smallGearItem != null) {
                add(smallGearItem.get(), zhName + "小齿轮");
            }
            
            // 弹簧
            var springItem = ModItems.getMaterialItem(ModItemTypes.SPRING, materialName);
            if (springItem != null) {
                add(springItem.get(), zhName + "弹簧");
            }
            
            // 螺丝
            var screwItem = ModItems.getMaterialItem(ModItemTypes.SCREW, materialName);
            if (screwItem != null) {
                add(screwItem.get(), zhName + "螺丝");
            }
            
            // 螺栓
            var boltItem = ModItems.getMaterialItem(ModItemTypes.BOLT, materialName);
            if (boltItem != null) {
                add(boltItem.get(), zhName + "螺栓");
            }
            
            // 环
            var ringItem = ModItems.getMaterialItem(ModItemTypes.RING, materialName);
            if (ringItem != null) {
                add(ringItem.get(), zhName + "环");
            }
            

        }

        add(ModItems.WRENCH.get(), "扳手");

        add(ModBlocks.COKE_OVEN_BRICK.get(), "焦炉砖");
        add(ModBlocks.FLUID_TANK.get(), "流体储罐");
        
        // 添加蓝图工具的翻译
        add(ModItems.BLUEPRINT.get(), "蓝图");
        
        // 添加多方块机器选择界面的翻译
        add("gui.poly_mech.multiblock_selection.title", "多方块机器选择");
        add("gui.poly_mech.multiblock_selection.large_chemical_reactor", "大型化学反应釜");
        add("gui.poly_mech.multiblock_selection.implosion_compressor", "内爆压缩机");
        add("gui.poly_mech.multiblock_selection.pyrolyze_oven", "热解炉");
        add("gui.poly_mech.multiblock_selection.close", "关闭");
        
        // 添加快捷键的翻译
        add("key.poly_mech.open_multiblock_menu", "打开多方块选择菜单");

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