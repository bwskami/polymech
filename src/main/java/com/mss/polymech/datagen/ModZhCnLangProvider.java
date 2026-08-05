package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.ConveyorMaterial;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.fluid.ChemicalFluid;
import com.mss.polymech.fluid.ModElements;
import com.mss.polymech.item.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

import java.util.Map;

public class ModZhCnLangProvider extends LanguageProvider {
    public ModZhCnLangProvider(PackOutput output) {
        super(output, Polymech.MOD_ID, "zh_cn");
    }

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
            // 原版金属（仅用于管道/零件命名，不注册锭）
            Map.entry("iron", "铁"),
            Map.entry("gold", "金"),
            Map.entry("copper", "铜"),
            Map.entry("aluminium", "铝"),
            Map.entry("nickel", "镍"),
            Map.entry("tin", "锡"),
            Map.entry("zinc", "锌"),
            // 从GTM抄录的真实单质金属
            Map.entry("silver", "银"),
            Map.entry("lead", "铅"),
            Map.entry("chromium", "铬"),
            Map.entry("titanium", "钛"),
            Map.entry("tungsten", "钨"),
            Map.entry("platinum", "铂"),
            Map.entry("osmium", "锇"),
            Map.entry("iridium", "铱"),
            Map.entry("palladium", "钯"),
            Map.entry("cobalt", "钴"),
            Map.entry("manganese", "锰"),
            Map.entry("molybdenum", "钼"),
            Map.entry("silicon", "硅"),
            Map.entry("bismuth", "铋"),
            Map.entry("antimony", "锑"),
            Map.entry("gallium", "镓"),
            Map.entry("indium", "铟"),
            Map.entry("tantalum", "钽"),
            Map.entry("niobium", "铌"),
            Map.entry("vanadium", "钒"),
            Map.entry("neodymium", "钕"),
            Map.entry("beryllium", "铍"),
            // 合金
            Map.entry("brass", "黄铜"),
            Map.entry("bronze", "青铜"),
            Map.entry("ivar", "殷钢"),
            Map.entry("cupronickel", "白铜"),
            Map.entry("stainless_steel", "不锈钢"),
            Map.entry("electrum", "琥珀金"),
            // 第二批补全的锭状金属
            Map.entry("europium", "铕"),
            Map.entry("samarium", "钐"),
            Map.entry("yttrium", "钇"),
            Map.entry("rhodium", "铑"),
            Map.entry("ruthenium", "钌"),
            Map.entry("thorium", "钍"),
            Map.entry("uranium", "铀"),
            Map.entry("plutonium", "钚"),
            // 粉状金属（碱金属/碱土金属/稀土等）
            Map.entry("lithium", "锂"),
            Map.entry("sodium", "钠"),
            Map.entry("potassium", "钾"),
            Map.entry("rubidium", "铷"),
            Map.entry("caesium", "铯"),
            Map.entry("francium", "钫"),
            Map.entry("magnesium", "镁"),
            Map.entry("calcium", "钙"),
            Map.entry("strontium", "锶"),
            Map.entry("barium", "钡"),
            Map.entry("radium", "镭"),
            Map.entry("scandium", "钪"),
            Map.entry("hafnium", "铪"),
            Map.entry("zirconium", "锆"),
            Map.entry("rhenium", "铼"),
            Map.entry("cadmium", "镉"),
            Map.entry("lanthanum", "镧"),
            Map.entry("cerium", "铈"),
            Map.entry("praseodymium", "镨"),
            Map.entry("promethium", "钷"),
            Map.entry("gadolinium", "钆"),
            Map.entry("terbium", "铽"),
            Map.entry("dysprosium", "镝"),
            Map.entry("holmium", "钬"),
            Map.entry("erbium", "铒"),
            Map.entry("thulium", "铥"),
            Map.entry("ytterbium", "镱"),
            Map.entry("lutetium", "镥"),
            Map.entry("actinium", "锕"),
            Map.entry("protactinium", "镤"),
            Map.entry("neptunium", "镎"),
            Map.entry("americium", "镅"),
            Map.entry("test", "测试")
    );

    /** 化学流体中文名（datagen侧的翻译源数据，运行时一律通过翻译键解析） */
    private static final Map<String, String> CHEMICAL_ZH = Map.ofEntries(
            // 酸 / 碱 / 氧化剂（液体）
            Map.entry("sulfuric_acid", "硫酸"),
            Map.entry("nitric_acid", "硝酸"),
            Map.entry("hydrochloric_acid", "盐酸"),
            Map.entry("hydrofluoric_acid", "氢氟酸"),
            Map.entry("hydrogen_peroxide", "过氧化氢"),
            Map.entry("sodium_hydroxide", "氢氧化钠溶液"),
            Map.entry("ammonia_water", "氨水"),
            Map.entry("acetic_acid", "乙酸"),
            // 有机溶剂（液体）
            Map.entry("ethanol", "乙醇"),
            Map.entry("methanol", "甲醇"),
            Map.entry("acetone", "丙酮"),
            Map.entry("glycerol", "甘油"),
            Map.entry("benzene", "苯"),
            Map.entry("toluene", "甲苯"),
            Map.entry("phenol", "苯酚"),
            Map.entry("nitrobenzene", "硝基苯"),
            // 单质 / 氧化物（液体）
            Map.entry("bromine", "溴"),
            Map.entry("mercury", "汞（水银）"),
            Map.entry("sulfur_trioxide", "三氧化硫"),
            // 气体
            Map.entry("hydrogen", "氢气"),
            Map.entry("nitrogen", "氮气"),
            Map.entry("oxygen", "氧气"),
            Map.entry("chlorine", "氯气"),
            Map.entry("ammonia", "氨气"),
            Map.entry("methane", "甲烷"),
            Map.entry("propane", "丙烷"),
            Map.entry("butane", "丁烷"),
            Map.entry("carbon_dioxide", "二氧化碳"),
            Map.entry("carbon_monoxide", "一氧化碳"),
            Map.entry("sulfur_dioxide", "二氧化硫"),
            Map.entry("hydrogen_sulfide", "硫化氢"),
            Map.entry("helium", "氦气"),
            Map.entry("argon", "氩气")
    );

    /** 周期表全118元素中文名（等离子体流体显示用） */
    private static final Map<String, String> ELEMENT_ZH = Map.ofEntries(
            Map.entry("hydrogen", "氢"),
            Map.entry("helium", "氦"),
            Map.entry("lithium", "锂"),
            Map.entry("beryllium", "铍"),
            Map.entry("boron", "硼"),
            Map.entry("carbon", "碳"),
            Map.entry("nitrogen", "氮"),
            Map.entry("oxygen", "氧"),
            Map.entry("fluorine", "氟"),
            Map.entry("neon", "氖"),
            Map.entry("sodium", "钠"),
            Map.entry("magnesium", "镁"),
            Map.entry("aluminium", "铝"),
            Map.entry("silicon", "硅"),
            Map.entry("phosphorus", "磷"),
            Map.entry("sulfur", "硫"),
            Map.entry("chlorine", "氯"),
            Map.entry("argon", "氩"),
            Map.entry("potassium", "钾"),
            Map.entry("calcium", "钙"),
            Map.entry("scandium", "钪"),
            Map.entry("titanium", "钛"),
            Map.entry("vanadium", "钒"),
            Map.entry("chromium", "铬"),
            Map.entry("manganese", "锰"),
            Map.entry("iron", "铁"),
            Map.entry("cobalt", "钴"),
            Map.entry("nickel", "镍"),
            Map.entry("copper", "铜"),
            Map.entry("zinc", "锌"),
            Map.entry("gallium", "镓"),
            Map.entry("germanium", "锗"),
            Map.entry("arsenic", "砷"),
            Map.entry("selenium", "硒"),
            Map.entry("bromine", "溴"),
            Map.entry("krypton", "氪"),
            Map.entry("rubidium", "铷"),
            Map.entry("strontium", "锶"),
            Map.entry("yttrium", "钇"),
            Map.entry("zirconium", "锆"),
            Map.entry("niobium", "铌"),
            Map.entry("molybdenum", "钼"),
            Map.entry("technetium", "锝"),
            Map.entry("ruthenium", "钌"),
            Map.entry("rhodium", "铑"),
            Map.entry("palladium", "钯"),
            Map.entry("silver", "银"),
            Map.entry("cadmium", "镉"),
            Map.entry("indium", "铟"),
            Map.entry("tin", "锡"),
            Map.entry("antimony", "锑"),
            Map.entry("tellurium", "碲"),
            Map.entry("iodine", "碘"),
            Map.entry("xenon", "氙"),
            Map.entry("caesium", "铯"),
            Map.entry("barium", "钡"),
            Map.entry("lanthanum", "镧"),
            Map.entry("cerium", "铈"),
            Map.entry("praseodymium", "镨"),
            Map.entry("neodymium", "钕"),
            Map.entry("promethium", "钷"),
            Map.entry("samarium", "钐"),
            Map.entry("europium", "铕"),
            Map.entry("gadolinium", "钆"),
            Map.entry("terbium", "铽"),
            Map.entry("dysprosium", "镝"),
            Map.entry("holmium", "钬"),
            Map.entry("erbium", "铒"),
            Map.entry("thulium", "铥"),
            Map.entry("ytterbium", "镱"),
            Map.entry("lutetium", "镥"),
            Map.entry("hafnium", "铪"),
            Map.entry("tantalum", "钽"),
            Map.entry("tungsten", "钨"),
            Map.entry("rhenium", "铼"),
            Map.entry("osmium", "锇"),
            Map.entry("iridium", "铱"),
            Map.entry("platinum", "铂"),
            Map.entry("gold", "金"),
            Map.entry("mercury", "汞"),
            Map.entry("thallium", "铊"),
            Map.entry("lead", "铅"),
            Map.entry("bismuth", "铋"),
            Map.entry("polonium", "钋"),
            Map.entry("astatine", "砹"),
            Map.entry("radon", "氡"),
            Map.entry("francium", "钫"),
            Map.entry("radium", "镭"),
            Map.entry("actinium", "锕"),
            Map.entry("thorium", "钍"),
            Map.entry("protactinium", "镤"),
            Map.entry("uranium", "铀"),
            Map.entry("neptunium", "镎"),
            Map.entry("plutonium", "钚"),
            Map.entry("americium", "镅"),
            Map.entry("curium", "锔"),
            Map.entry("berkelium", "锫"),
            Map.entry("californium", "锎"),
            Map.entry("einsteinium", "锿"),
            Map.entry("fermium", "镄"),
            Map.entry("mendelevium", "钔"),
            Map.entry("nobelium", "锘"),
            Map.entry("lawrencium", "铹"),
            Map.entry("rutherfordium", "炉"),
            Map.entry("dubnium", "釒"),
            Map.entry("seaborgium", "釔"),
            Map.entry("bohrium", "釓"),
            Map.entry("hassium", "釙"),
            Map.entry("meitnerium", "鿏"),
            Map.entry("darmstadtium", "鐽"),
            Map.entry("roentgenium", "錀"),
            Map.entry("copernicium", "鎶"),
            Map.entry("nihonium", "鿭"),
            Map.entry("flerovium", "鈇"),
            Map.entry("moscovium", "镆"),
            Map.entry("livermorium", "鉝"),
            Map.entry("tennessine", "鿬"),
            Map.entry("oganesson", "鿫")
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

        // 化学流体（真实存在的化学物质，不可放置）
        for (ChemicalFluid chem : ChemicalFluid.values()) {
            String zhName = CHEMICAL_ZH.get(chem.getId());
            add("fluid.poly_mech." + chem.getId(), zhName);
            // 只有液体才有桶
            if (chem.isLiquid()) {
                add("item.poly_mech." + chem.getId() + "_bucket", zhName + "桶");
            }
        }

        // 熔融金属（每种材料一条，温度≈熔点，带桶）
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            String zhName = MATERIAL_ZH_NAMES.getOrDefault(materialName, materialName);
            add("fluid.poly_mech.molten_" + materialName, "熔融" + zhName);
            add("item.poly_mech.molten_" + materialName + "_bucket", "熔融" + zhName + "桶");
        }

        // 等离子体（周期表全118元素，无桶）
        for (ModElements element : ModElements.values()) {
            String zhName = ELEMENT_ZH.getOrDefault(element.getId(), element.getSymbol());
            add("fluid.poly_mech." + element.getId() + "_plasma", zhName + "等离子体");
        }

        // 金属存储块（仅有锭的材料，键为材料名）
        for (var entry : ModBlocks.MATERIAL_BLOCKS.entrySet()) {
            String zhName = MATERIAL_ZH_NAMES.getOrDefault(entry.getKey(), entry.getKey());
            add(entry.getValue().get(), zhName + "块");
        }
        // tooltip管理中心：物态 / 温度 / 危险警示（化学式直接由ModTooltipCenter渲染，无需翻译键）
        add("tooltip.poly_mech.fluid.state_liquid", "物态：液体");
        add("tooltip.poly_mech.fluid.state_gas", "物态：气体");
        add("tooltip.poly_mech.fluid.state_plasma", "物态：等离子体");
        add("tooltip.poly_mech.fluid.temperature", "温度：%d K");
        add("tooltip.poly_mech.hazardous", "⚠ 危险物质");

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
            // 铁为默认材质，不加材料前缀（如“管道”“小型管道”）
            String materialZh = material == PipeMaterial.IRON ? ""
                    : MATERIAL_ZH_NAMES.getOrDefault(material.getName(), material.getName());
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                PipeBlock.PipeSize size = sizeEntry.getKey();
                String name = materialZh + SIZE_ZH.get(size);
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
        add("itemGroup.bucket_tab", "Ploy Mech:流体桶");
    }
}