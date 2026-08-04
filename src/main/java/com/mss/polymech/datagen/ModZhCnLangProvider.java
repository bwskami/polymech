package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.ConveyorMaterial;
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

    private static final Map<ConveyorMaterial, String> CONVEYOR_MATERIAL_ZH = Map.of(
            ConveyorMaterial.IRON, "",
            ConveyorMaterial.BRONZE, "青铜",
            ConveyorMaterial.STAINLESS_STEEL, "不锈钢",
            ConveyorMaterial.BRASS, "黄铜"
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
        add(ModBlocks.HORIZONTAL_STEAM_BOILER.mainBlock().get(), "卧式蒸汽锅炉");

        // 卧式蒸汽锅炉 GUI 翻译
        add("gui.poly_mech.input_liquid", "输入液体");
        add("gui.poly_mech.fuel", "燃料");
        add("gui.poly_mech.output_liquid", "输出液体");
        add("gui.poly_mech.output_ash", "灰烬");
        add("gui.poly_mech.button.enable", "开机");
        add("gui.poly_mech.button.disable", "关机");

        // 蒸汽流体
        add("fluid.poly_mech.steam", "蒸汽");
        add("item.poly_mech.steam_bucket", "蒸汽桶");
        add("block.poly_mech.steam", "蒸汽");

        // 侧面方块类型
        add("side_type.poly_mech.normal", "机器外壳");
        add("side_type.poly_mech.fluid_input", "流体输入仓");
        add("side_type.poly_mech.fluid_output", "流体输出仓");
        add("side_type.poly_mech.item_input", "物品输入仓");
        add("side_type.poly_mech.item_output", "物品输出仓");
        
        // 添加蓝图工具的翻译
        add(ModItems.BLUEPRINT.get(), "蓝图");
        add(ModItems.COKE.get(), "焦煤");

        // 通用流体单元（四种规格）
        add(ModItems.SMALL_FLUID_CELL.get(), "小型流体单元");
        add(ModItems.UNIVERSAL_FLUID_CELL.get(), "通用流体单元");
        add(ModItems.MEDIUM_FLUID_CELL.get(), "中型流体单元");
        add(ModItems.HUGE_FLUID_CELL.get(), "超大型流体单元");
        add("tooltip.poly_mech.fluid_cell.empty", "空的");
        add("tooltip.poly_mech.fluid_cell.stored", "内含：%s（%d/%d mB）");
        add("tooltip.poly_mech.fluid_cell.limit", "容量上限已设为 %d/%d mB");
        add("tooltip.poly_mech.fluid_cell.config_hint", "按住 Shift 右键：设置容量上限");
        add("gui.poly_mech.fluid_cell.config_title", "设置容量上限");
        add("gui.poly_mech.fluid_cell.stored", "已储存：%d mB");
        add("gui.poly_mech.fluid_cell.max_capacity", "种类上限：%d mB");
        add("gui.poly_mech.fluid_cell.limit_label", "容量上限：");
        add("gui.poly_mech.button.confirm", "确认");
        add("gui.poly_mech.button.cancel", "取消");
        
        // 添加多方块机器选择界面的翻译
        add("gui.poly_mech.multiblock_selection.title", "多方块机器选择");
        add("gui.poly_mech.multiblock_selection.close", "←");
        add("gui.poly_mech.multiblock_selection.category_info", "分类: %s (%d 台机器)");
        add("gui.poly_mech.multiblock_selection.header_label", "当前分类模式: %s | 选中: %s");
        add("gui.poly_mech.classify.by_voltage", "按电压分");
        add("gui.poly_mech.classify.by_type", "按类型分");
        add("gui.poly_mech.classify.mode_voltage", "按电压");
        add("gui.poly_mech.classify.mode_type", "按类型");
        add("gui.poly_mech.tier.lv", "LV");
        add("gui.poly_mech.tier.mv", "MV");
        add("gui.poly_mech.tier.hv", "HV");
        add("gui.poly_mech.tier.ev", "EV");
        add("gui.poly_mech.tier.iv", "IV");
        add("gui.poly_mech.tier.luv", "LuV");
        add("gui.poly_mech.tier.zpm", "ZPM");
        add("gui.poly_mech.tier.uv", "UV");
        add("gui.poly_mech.tier.uhv", "UHV");
        add("gui.poly_mech.tier.steam", "蒸汽");
        add("gui.poly_mech.type.chemical", "化学反应");
        add("gui.poly_mech.type.compression", "压缩");
        add("gui.poly_mech.type.heat", "热处理");
        add("gui.poly_mech.type.assembly", "组装");
        add("gui.poly_mech.type.recycling", "回收");
        add("gui.poly_mech.machine.large_chemical_reactor", "大型化学反应釜");
        add("gui.poly_mech.machine.implosion_compressor", "内爆压缩机");
        add("gui.poly_mech.machine.pyrolyze_oven", "热解炉");
        add("gui.poly_mech.machine.electric_blast_furnace", "电力高炉");
        add("gui.poly_mech.machine.vacuum_freezer", "真空冷冻机");
        add("gui.poly_mech.machine.assembly_line", "装配线");
        add("gui.poly_mech.machine.recycler", "回收机");

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

        for (var conveyorEntry : ModBlocks.CONVEYOR_TABLE.entrySet()) {
            String name = CONVEYOR_MATERIAL_ZH.get(conveyorEntry.getKey()) + "传送带";
            add(conveyorEntry.getValue().get(), name);
        }

        add("itemGroup.material_tab", "Ploy Mech:材料");
        add("itemGroup.block_tab", "Ploy Mech:方块");
        add("itemGroup.pipe_tab", "Ploy Mech:管道与物流相关");
        add("itemGroup.tool_tab", "Ploy Mech:工具");
        add("itemGroup.fluid_cell_tab", "Ploy Mech:流体单元");
    }
}