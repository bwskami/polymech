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
import com.mss.polymech.powergrid.GridWireType;
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
            Map.entry("invar", "殷钢"),
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
            Map.entry("test", "测试"),
            // 宝石/晶体
            Map.entry("diamond", "钻石"),
            Map.entry("emerald", "绿宝石"),
            Map.entry("ruby", "红宝石"),
            Map.entry("sapphire", "蓝宝石"),
            Map.entry("topaz", "黄玉"),
            Map.entry("amethyst", "紫水晶"),
            Map.entry("garnet", "石榴石"),
            Map.entry("opal", "蛋白石"),
            Map.entry("apatite", "磷灰石"),
            Map.entry("quartz", "石英"),
            Map.entry("certus_quartz", "赛特斯石英")
    );

    /** 真实矿物中文名（矿物学标准译名） */
    private static final Map<String, String> MINERAL_ZH_NAMES = Map.ofEntries(
            // 铜族
            Map.entry("native_copper", "自然铜"),
            Map.entry("malachite", "孔雀石"),
            Map.entry("chalcopyrite", "黄铜矿"),
            Map.entry("bornite", "斑铜矿"),
            Map.entry("chalcocite", "辉铜矿"),
            Map.entry("tetrahedrite", "黝铜矿"),
            Map.entry("copper", "铜"),
            // 铁族
            Map.entry("hematite", "赤铁矿"),
            Map.entry("magnetite", "磁铁矿"),
            Map.entry("limonite", "褐铁矿"),
            Map.entry("goethite", "针铁矿"),
            Map.entry("vanadium_magnetite", "钒磁铁矿"),
            Map.entry("iron", "铁"),
            // 锡族
            Map.entry("cassiterite", "锡石"),
            Map.entry("cassiterite_sand", "砂锡矿"),
            Map.entry("tin", "锡"),
            // 锌铅银
            Map.entry("sphalerite", "闪锌矿"),
            Map.entry("galena", "方铅矿"),
            Map.entry("lead", "铅"),
            Map.entry("native_silver", "自然银"),
            Map.entry("silver", "银"),
            // 金
            Map.entry("native_gold", "自然金"),
            Map.entry("gold", "金"),
            // 铋
            Map.entry("bismuthinite", "辉铋矿"),
            // 镍钴
            Map.entry("garnierite", "硅镁镍矿"),
            Map.entry("pentlandite", "镍黄铁矿"),
            Map.entry("nickel", "镍"),
            Map.entry("cobaltite", "辉钴矿"),
            Map.entry("cobalt", "钴"),
            // 铝
            Map.entry("bauxite", "铝土矿"),
            Map.entry("alunite", "明矾石"),
            Map.entry("aluminium", "铝"),
            // 钨
            Map.entry("wolframite", "黑钨矿"),
            Map.entry("scheelite", "白钨矿"),
            Map.entry("tungstate", "钨酸锂"),
            // 锑砷
            Map.entry("stibnite", "辉锑矿"),
            Map.entry("realgar", "雄黄"),
            // 锰铬钛
            Map.entry("pyrolusite", "软锰矿"),
            Map.entry("chromite", "铬铁矿"),
            Map.entry("ilmenite", "钛铁矿"),
            // 钼
            Map.entry("molybdenite", "辉钼矿"),
            Map.entry("powellite", "钼华"),
            Map.entry("wulfenite", "钼铅矿"),
            Map.entry("molybdenum", "钼"),
            // 钽铌
            Map.entry("tantalite", "钽铁矿"),
            Map.entry("pyrochlore", "烧绿石"),
            // 铂族
            Map.entry("cooperite", "硫铂矿"),
            Map.entry("platinum", "铂"),
            Map.entry("palladium", "钯"),
            // 铀钍
            Map.entry("pitchblende", "沥青铀矿"),
            Map.entry("uraninite", "晶质铀矿"),
            Map.entry("thorium", "钍"),
            Map.entry("plutonium_239", "钚-239"),
            // 轻金属稀土
            Map.entry("beryllium", "铍"),
            Map.entry("spodumene", "锂辉石"),
            Map.entry("lepidolite", "锂云母"),
            Map.entry("lithium", "锂"),
            Map.entry("pollucite", "铯榴石"),
            Map.entry("bastnasite", "氟碳铈矿"),
            Map.entry("monazite", "独居石"),
            Map.entry("neodymium", "钕"),
            // 石榴石族
            Map.entry("almandine", "铁铝榴石"),
            Map.entry("andradite", "钙铁榴石"),
            Map.entry("grossular", "钙铝榴石"),
            Map.entry("pyrope", "镁铝榴石"),
            Map.entry("spessartine", "锰铝榴石"),
            Map.entry("red_garnet", "红石榴石"),
            Map.entry("yellow_garnet", "黄石榴石"),
            // 宝石
            Map.entry("amethyst", "紫水晶"),
            Map.entry("diamond", "钻石"),
            Map.entry("emerald", "祖母绿"),
            Map.entry("lapis_lazuli", "青金石"),
            Map.entry("lazurite", "蓝金石"),
            Map.entry("sodalite", "方钠石"),
            Map.entry("opal", "蛋白石"),
            Map.entry("pyrite", "黄铁矿"),
            Map.entry("ruby", "红宝石"),
            Map.entry("sapphire", "蓝宝石"),
            Map.entry("green_sapphire", "绿蓝宝石"),
            Map.entry("topaz", "黄玉"),
            Map.entry("blue_topaz", "蓝黄玉"),
            Map.entry("apatite", "磷灰石"),
            Map.entry("olivine", "橄榄石"),
            Map.entry("quartzite", "石英岩"),
            Map.entry("nether_quartz", "下界石英"),
            Map.entry("certus_quartz", "赛特斯石英"),
            // 非金属
            Map.entry("sulfur", "硫"),
            Map.entry("graphite", "石墨"),
            Map.entry("saltpeter", "硝石"),
            Map.entry("sylvite", "钾石盐"),
            Map.entry("salt", "盐"),
            Map.entry("rock_salt", "岩盐"),
            Map.entry("gypsum", "石膏"),
            Map.entry("cinnabar", "朱砂"),
            Map.entry("cryolite", "冰晶石"),
            Map.entry("borax", "硼砂"),
            Map.entry("calcite", "方解石"),
            Map.entry("barite", "重晶石"),
            Map.entry("magnesite", "菱镁矿"),
            Map.entry("asbestos", "石棉"),
            Map.entry("mica", "云母"),
            Map.entry("talc", "滑石"),
            Map.entry("soapstone", "皂石"),
            Map.entry("kyanite", "蓝晶石"),
            Map.entry("diatomite", "硅藻土"),
            Map.entry("bentonite", "膨润土"),
            Map.entry("fullers_earth", "漂白土"),
            Map.entry("glauconite_sand", "海绿石砂"),
            Map.entry("zeolite", "沸石"),
            Map.entry("trona", "天然碱"),
            Map.entry("tricalcium_phosphate", "磷酸三钙"),
            Map.entry("basaltic_mineral_sand", "玄武岩矿砂"),
            Map.entry("granitic_mineral_sand", "花岗岩矿砂"),
            Map.entry("garnet_sand", "石榴石砂"),
            // 煤系红石
            Map.entry("bituminous_coal", "烟煤"),
            Map.entry("lignite", "褐煤"),
            Map.entry("oilsands", "油砂"),
            Map.entry("redstone", "红石")
    );

    /** 群峦岩种中文名（岩石方块与岩种矿石命名共用） */
    private static final Map<String, String> ROCK_ZH_NAMES = Map.ofEntries(
            Map.entry("limestone", "石灰岩"),
            Map.entry("shale", "页岩"),
            Map.entry("chalk", "白垩岩"),
            Map.entry("chert", "燧石岩"),
            Map.entry("claystone", "粘土岩"),
            Map.entry("conglomerate", "砾岩"),
            Map.entry("dolomite", "白云岩"),
            Map.entry("tuff", "凝灰岩"),
            Map.entry("granite", "花岗岩"),
            Map.entry("basalt", "玄武岩"),
            Map.entry("rhyolite", "流纹岩"),
            Map.entry("dacite", "英安岩"),
            Map.entry("diorite", "闪长岩"),
            Map.entry("gabbro", "辉长岩"),
            Map.entry("andesite", "安山岩"),
            Map.entry("marble", "大理岩"),
            Map.entry("gneiss", "片麻岩"),
            Map.entry("schist", "片岩"),
            Map.entry("slate", "板岩"),
            Map.entry("phyllite", "千枚岩"),
            Map.entry("quartzite", "石英岩")
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
            Map.entry("argon", "氩气"),
            // 无机酸 / 盐溶液 / 混合液
            Map.entry("acidic_osmium_solution", "酸性锇溶液"),
            Map.entry("aqua_regia", "王水"),
            Map.entry("diluted_hydrochloric_acid", "稀盐酸"),
            Map.entry("diluted_sulfuric_acid", "稀硫酸"),
            Map.entry("phosphoric_acid", "磷酸"),
            Map.entry("phthalic_acid", "邻苯二甲酸"),
            Map.entry("formic_acid", "甲酸"),
            Map.entry("hypochlorous_acid", "次氯酸"),
            Map.entry("fluoroantimonic_acid", "氟锑酸"),
            Map.entry("nitration_mixture", "硝酸混酸"),
            Map.entry("sulfuric_copper_solution", "硫酸铜溶液"),
            Map.entry("sulfuric_nickel_solution", "硫酸镍溶液"),
            Map.entry("sodium_persulfate", "过硫酸钠"),
            Map.entry("rhodium_sulfate", "硫酸铑"),
            Map.entry("titanium_tetrachloride", "四氯化钛"),
            Map.entry("iron_ii_chloride", "氯化亚铁"),
            Map.entry("iron_iii_chloride", "氯化铁"),
            Map.entry("ammonium_formate", "甲酸铵"),
            // 有机单体 / 中间体
            Map.entry("formaldehyde", "甲醛"),
            Map.entry("formamide", "甲酰胺"),
            Map.entry("chloromethane", "氯甲烷"),
            Map.entry("dichloroethane", "二氯乙烷"),
            Map.entry("glycolonitrile", "乙腈"),
            Map.entry("dimethylamine", "二甲胺"),
            Map.entry("diethylenetriamine", "二乙烯三胺"),
            Map.entry("diethylenetriamine_pentaacetonitrile", "二乙烯三胺五乙腈"),
            Map.entry("ethane", "乙烷"),
            Map.entry("ethylene", "乙烯"),
            Map.entry("propene", "丙烯"),
            Map.entry("butene", "丁烯"),
            Map.entry("butadiene", "丁二烯"),
            Map.entry("cumene", "异丙苯"),
            Map.entry("vinyl_chloride", "氯乙烯"),
            Map.entry("tetrafluoroethylene", "四氟乙烯"),
            Map.entry("dimethyldichlorosilane", "二甲基二氯硅烷"),
            Map.entry("epichlorohydrin", "环氧氯丙烷"),
            Map.entry("hydrogen_cyanide", "氰化氢"),
            Map.entry("glyceryl_trinitrate", "硝酸甘油"),
            Map.entry("lead_zinc_solution", "铅锌溶液"),
            Map.entry("indium_concentrate", "铟富集溶液"),
            // 聚合物
            Map.entry("epoxy", "环氧树脂"),
            Map.entry("reinforced_epoxy_resin", "强化环氧树脂"),
            Map.entry("polyethylene", "聚乙烯"),
            Map.entry("polytetrafluoroethylene", "聚四氟乙烯"),
            Map.entry("polyvinyl_chloride", "聚氯乙烯"),
            Map.entry("polybenzimidazole", "聚苯并咪唑"),
            Map.entry("polycaprolactam", "聚己内酰胺"),
            Map.entry("polyphenylene_sulfide", "聚苯硫醚"),
            // 熔融金属 / 合金
            Map.entry("copper", "熔融铜"),
            Map.entry("gold", "熔融金"),
            Map.entry("iron", "熔融铁"),
            Map.entry("annealed_copper", "熔融退火铜"),
            Map.entry("wrought_iron", "熔融锻铁"),
            Map.entry("steel", "熔融钢"),
            Map.entry("arsenic", "熔融砷"),
            Map.entry("carbon", "熔融碳"),
            Map.entry("battery_alloy", "熔融电池合金"),
            Map.entry("bismuth_bronze", "熔融铋青铜"),
            Map.entry("black_bronze", "熔融黑青铜"),
            Map.entry("cobalt_brass", "熔融钴黄铜"),
            Map.entry("kanthal", "熔融坎塔尔合金"),
            Map.entry("magnalium", "熔融镁铝合金"),
            Map.entry("manganese_phosphide", "熔融磷化锰"),
            Map.entry("nichrome", "熔融镍铬合金"),
            Map.entry("osmiridium", "熔融铱锇合金"),
            Map.entry("potin", "熔融粗青铜合金"),
            Map.entry("rose_gold", "熔融玫瑰金"),
            Map.entry("soldering_alloy", "熔融焊锡"),
            Map.entry("sterling_silver", "熔融标准纯银"),
            Map.entry("tin_alloy", "熔融锡铁合金"),
            Map.entry("ultimet", "熔融哈氏合金"),
            Map.entry("vanadium_gallium", "熔融钒镓合金"),
            Map.entry("vanadium_steel", "熔融钒钢"),
            Map.entry("niobium_titanium", "熔融铌钛合金"),
            Map.entry("borosilicate_glass", "熔融硼硅玻璃"),
            Map.entry("glass", "熔融玻璃"),
            // 超导材料 / 特种陶瓷
            Map.entry("gallium_arsenide", "熔融砷化镓"),
            Map.entry("indium_gallium_phosphide", "熔融磷化铟镓"),
            Map.entry("nickel_zinc_ferrite", "熔融镍锌铁氧体"),
            Map.entry("magnesium_diboride", "熔融二硼化镁"),
            Map.entry("yttrium_barium_cuprate", "熔融钇钡铜氧化物"),
            Map.entry("mercury_barium_calcium_cuprate", "熔融汞钡钙铜氧化物"),
            Map.entry("uranium_triplatinum", "熔融三铂化铀"),
            Map.entry("samarium_iron_arsenic_oxide", "熔融钐铁砷氧化物"),
            Map.entry("indium_tin_barium_titanium_cuprate", "熔融铟锡钡钛铜氧化物"),
            // 特殊液体
            Map.entry("ice", "冰"),
            Map.entry("oil", "石油"),
            Map.entry("raw_oil", "原油"),
            Map.entry("heavy_oil", "重油"),
            Map.entry("light_oil", "轻油"),
            Map.entry("naphtha", "石脑油"),
            Map.entry("sulfuric_naphtha", "含硫石脑油"),
            Map.entry("light_fuel", "轻燃油"),
            Map.entry("sulfuric_light_fuel", "含硫轻燃油"),
            Map.entry("lightly_hydro_cracked_light_fuel", "轻度加氢裂化轻燃油"),
            Map.entry("lightly_steam_cracked_light_fuel", "轻度蒸汽裂化轻燃油"),
            Map.entry("severely_hydro_cracked_light_fuel", "重度加氢裂化轻燃油"),
            Map.entry("severely_steam_cracked_light_fuel", "重度蒸汽裂化轻燃油"),
            Map.entry("lightly_hydro_cracked_naphtha", "轻度加氢裂化石脑油"),
            Map.entry("lightly_steam_cracked_naphtha", "轻度蒸汽裂化石脑油"),
            Map.entry("severely_hydro_cracked_naphtha", "重度加氢裂化石脑油"),
            Map.entry("severely_steam_cracked_naphtha", "重度蒸汽裂化石脑油"),
            Map.entry("heavy_fuel", "重燃油"),
            Map.entry("sulfuric_heavy_fuel", "含硫重燃油"),
            Map.entry("lightly_hydro_cracked_heavy_fuel", "轻度加氢裂化重燃油"),
            Map.entry("lightly_steam_cracked_heavy_fuel", "轻度蒸汽裂化重燃油"),
            Map.entry("severely_hydro_cracked_heavy_fuel", "重度加氢裂化重燃油"),
            Map.entry("severely_steam_cracked_heavy_fuel", "重度蒸汽裂化重燃油"),
            Map.entry("diesel", "柴油"),
            Map.entry("cetane_boosted_diesel", "高十六烷值柴油"),
            Map.entry("lpg", "液化石油气"),
            Map.entry("lubricant", "润滑油"),
            Map.entry("creosote", "杂酚油"),
            Map.entry("biomass", "生物质"),
            Map.entry("fermented_biomass", "发酵生物质"),
            Map.entry("cracked_bauxite_slurry", "裂化铝土浆液"),
            Map.entry("concrete", "混凝土"),
            Map.entry("glue", "胶水"),
            Map.entry("milk", "牛奶"),
            Map.entry("seed_oil", "种子油"),
            Map.entry("liquid_air", "液态空气"),
            Map.entry("rubber", "橡胶"),
            Map.entry("silicone_rubber", "硅橡胶"),
            Map.entry("styrene_butadiene_rubber", "丁苯橡胶"),
            Map.entry("uranium_235", "熔融铀-235"),
            Map.entry("uranium_238", "熔融铀-238"),
            Map.entry("plutonium_239", "熔融钚-239"),
            Map.entry("plutonium_241", "熔融钚-241"),
            // 气体
            Map.entry("air", "空气"),
            Map.entry("nitric_oxide", "一氧化氮"),
            Map.entry("nitrogen_dioxide", "二氧化氮"),
            Map.entry("nitrous_oxide", "一氧化二氮"),
            Map.entry("dinitrogen_tetroxide", "四氧化二氮"),
            Map.entry("nitrosyl_chloride", "亚硝酰氯"),
            Map.entry("monochloramine", "氯胺"),
            Map.entry("fluorine", "氟气"),
            Map.entry("neon", "氖气"),
            Map.entry("krypton", "氪气"),
            Map.entry("xenon", "氙气"),
            Map.entry("radon", "氡气"),
            Map.entry("deuterium", "氘气"),
            Map.entry("tritium", "氚气"),
            Map.entry("helium_3", "氦-3"),
            Map.entry("sulfuric_gas", "含硫炼油气"),
            Map.entry("refinery_gas", "炼油气"),
            Map.entry("natural_gas", "天然气"),
            Map.entry("coal_gas", "煤气"),
            Map.entry("wood_gas", "木煤气"),
            Map.entry("hydro_cracked_butadiene", "加氢裂化丁二烯"),
            Map.entry("hydro_cracked_butane", "加氢裂化丁烷"),
            Map.entry("hydro_cracked_butene", "加氢裂化丁烯"),
            Map.entry("hydro_cracked_ethane", "加氢裂化乙烷"),
            Map.entry("hydro_cracked_ethylene", "加氢裂化乙烯"),
            Map.entry("hydro_cracked_propane", "加氢裂化丙烷"),
            Map.entry("hydro_cracked_propene", "加氢裂化丙烯"),
            Map.entry("steam_cracked_butadiene", "蒸汽裂化丁二烯"),
            Map.entry("steam_cracked_butane", "蒸汽裂化丁烷"),
            Map.entry("steam_cracked_butene", "蒸汽裂化丁烯"),
            Map.entry("steam_cracked_ethane", "蒸汽裂化乙烷"),
            Map.entry("steam_cracked_ethylene", "蒸汽裂化乙烯"),
            Map.entry("steam_cracked_propane", "蒸汽裂化丙烷"),
            Map.entry("steam_cracked_propene", "蒸汽裂化丙烯"),
            Map.entry("lightly_hydro_cracked_gas", "轻度加氢裂化炼油气"),
            Map.entry("lightly_steam_cracked_gas", "轻度蒸汽裂化炼油气"),
            Map.entry("severely_hydro_cracked_gas", "重度加氢裂化炼油气"),
            Map.entry("severely_steam_cracked_gas", "重度蒸汽裂化炼油气"),
            Map.entry("uranium_hexafluoride", "六氟化铀"),
            Map.entry("enriched_uranium_hexafluoride", "富集六氟化铀"),
            Map.entry("depleted_uranium_hexafluoride", "枯竭六氟化铀")
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
        // 科技树
        add("key.poly_mech.tech_tree", "科技树");

        add("techtree.poly_mech.tech_steam", "蒸汽动力");
        add("techtree.poly_mech.tech_steam.desc", "点燃煤炭，驱动你的第一台蒸汽机器。");
        add("techtree.poly_mech.tech_electric", "电气化");
        add("techtree.poly_mech.tech_electric.desc", "将蒸汽动力升级为电力网络。");
        add("techtree.poly_mech.step.place_and_power", "放置并供能即可运行。");

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

            // 宝石/晶体（宝石物品名直接用宝石名，如"钻石"；粉为"钻石粉"）
            var gemItem = ModItems.getMaterialItem(ModItemTypes.GEM, materialName);
            if (gemItem != null) {
                add(gemItem.get(), zhName);
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
            
            // 线材
            var wireItem = ModItems.getMaterialItem(ModItemTypes.WIRE, materialName);
            if (wireItem != null) {
                add(wireItem.get(), zhName + "线");
            }

        }

        // 真实矿物：矿石方块（全部岩种变体，群峦式命名）与粗矿物（矿物学中文名）
        for (com.mss.polymech.worldgen.ModMinerals.MineralDefinition def : com.mss.polymech.worldgen.ModMinerals.getDefinitions()) {
            String mineralZh = MINERAL_ZH_NAMES.getOrDefault(def.mineral(), def.mineral());
            var oreSet = ModBlocks.MINERAL_ORES.get(def.mineral());
            if (oreSet != null) {
                for (var variantEntry : oreSet.byRock().entrySet()) {
                    String host = variantEntry.getKey();
                    String name = switch (host) {
                        case "stone" -> mineralZh + "矿石";
                        case "deepslate" -> "深层" + mineralZh + "矿石";
                        case "netherrack" -> "下界岩" + mineralZh + "矿石";
                        case "end_stone" -> "末地石" + mineralZh + "矿石";
                        default -> mineralZh + ROCK_ZH_NAMES.getOrDefault(host, host) + "矿石";
                    };
                    add(variantEntry.getValue().get(), name);
                }
            }
            var rawItem = ModItems.getRawMineral(def.mineral());
            if (rawItem != null) {
                add(rawItem.get(), "粗" + mineralZh);
            }
            // 矿物加工中间产物：粉碎矿/洗净矿（煤炭等直接产物不加工）
            if (def.kind() != com.mss.polymech.worldgen.ModMinerals.ProductKind.COAL) {
                var crushed = ModItems.getMineralItem(com.mss.polymech.api.item.ModItemTypes.CRUSHED, def.mineral());
                var purified = ModItems.getMineralItem(com.mss.polymech.api.item.ModItemTypes.PURIFIED, def.mineral());
                if (crushed != null) add(crushed.get(), "粉碎" + mineralZh);
                if (purified != null) add(purified.get(), "洗净" + mineralZh);
            }
        }

        add(ModItems.WRENCH.get(), "扳手");
        add(ModItems.PROSPECTOR.get(), "探矿仪");
        add("gui.poly_mech.prospector.title", "探矿仪");
        add("gui.poly_mech.prospector.hint", "岩石类型（底色）+ 矿物矿石（叠加色）。红框=所在区块。");
        add("gui.poly_mech.prospector.legend", "深度标记：白点=浅层，灰点=中层，黑点=深层");

        add(ModBlocks.COKE_OVEN_BRICK.get(), "焦炉砖");
        add(ModBlocks.FLUID_TANK.get(), "流体储罐");
        add(ModItems.TELEPORTER.get(), "星际传送器");
        add(ModBlocks.MERCURY_STONE.get(), "水星地表岩");
        add(ModBlocks.VENUS_STONE.get(), "金星地表岩");
        add(ModBlocks.MOON_STONE.get(), "月球地表岩");
        add(ModBlocks.MARS_STONE.get(), "火星地表岩");
        add(ModBlocks.GLACIO_STONE.get(), "冰卫星地表岩");

        // 区域岩石（群峦岩种，与ROCK_ZH_NAMES同源）
        for (var rockEntry : ModBlocks.ROCKS.entrySet()) {
            add(rockEntry.getValue().get(), ROCK_ZH_NAMES.getOrDefault(rockEntry.getKey(), rockEntry.getKey()));
        }

        // 勘探命令套件（世界生成测试工具）
        add("command.poly_mech.rock.predicted", "此处预测岩种：%s");
        add("command.poly_mech.rock.actual", "脚下实际方块：%s，位于 %s");
        add("command.poly_mech.rock.none", "脚下64格内未找到岩石（只有空气或流体）");
        add("command.poly_mech.veins.header", "=== PolyMech 矿脉定义 ===");
        add("command.poly_mech.veins.entry", "- %s：平均每1/%d区块一条，Y %d~%d，尺寸 %d，密度 %s，宿主岩：%s");
        add("command.poly_mech.veins.shape", "  类型：%s");
        add("command.poly_mech.veins.composition", "  主矿 %s / 次矿 %s / 夹层 %s / 零星 %s");
        add("command.poly_mech.scan.result", "%s：%d 块，最近处 %s");
        add("command.poly_mech.scan.total", "共 %d 个矿石方块");
        add("command.poly_mech.scan.none", "扫描范围内未找到本模组矿石");
        add("command.poly_mech.scan.unloaded", "（%d 列因区块未加载被跳过）");
        add("command.poly_mech.find.found", "最近的 %1$s 矿石：%2$d 格外 %3$s");
        add("command.poly_mech.find.none", "半径 %2$d 格内未找到 %1$s 矿石");
        add("command.poly_mech.find.invalid", "未知矿石材料：%s（可用：%s）");
        add("command.poly_mech.expose.done", "已清除 %1$d 个方块（以玩家为中心、半径 %2$d 的立方体范围）");
        add("command.poly_mech.vein.cassiterite", "锡石脉");
        add("command.poly_mech.vein.sphalerite", "闪锌矿脉");
        add("command.poly_mech.vein.galena", "方铅矿脉");
        add("command.poly_mech.vein.bauxite", "铝土矿脉");
        add("command.poly_mech.vein.laterite", "红土镍矿脉");
        add("command.poly_mech.vein.wolframite", "黑钨矿脉");
        add(ModBlocks.HORIZONTAL_STEAM_BOILER.mainBlock().get(), "卧式蒸汽锅炉");

        // 卧式蒸汽锅炉 GUI 翻译
        add("gui.poly_mech.input_liquid", "输入液体");
        add("gui.poly_mech.fuel", "燃料");
        add("gui.poly_mech.output_liquid", "输出液体");
        add("gui.poly_mech.output_ash", "灰烬");
        add("gui.poly_mech.button.enable", "开机");
        add("gui.poly_mech.button.disable", "关机");

        // 锅炉/加工机器状态与进度条悬停提示
        add("gui.poly_mech.status.running", "运行中");
        add("gui.poly_mech.status.stopped", "已停止");
        add("gui.poly_mech.status.idle", "待机");
        add("gui.poly_mech.machine.generation", "发电: %d /t");
        add("gui.poly_mech.machine.progress", "运行中: %d / %d");
        add("gui.poly_mech.boiler.tooltip.temperature", "温度: %d K / %d K");
        add("gui.poly_mech.boiler.tooltip.steam_output", "产汽: %d mB/t");
        add("gui.poly_mech.boiler.tooltip.water_level", "水位: %d / %d mB");
        add("gui.poly_mech.boiler.tooltip.steam", "蒸汽: %d / %d mB");
        add("gui.poly_mech.boiler.tooltip.steam_rate", "产汽速率: %d mB/t");
        add("gui.poly_mech.boiler.temperature", "温度: %d K");
        add("gui.poly_mech.boiler.efficiency", "预期效率: %d mB/t");
        add("gui.poly_mech.boiler.burn_time", "燃烧: %d s");

        // 蒸汽流体
        add("fluid.poly_mech.steam", "蒸汽");
        add("item.poly_mech.steam_bucket", "蒸汽桶");
        add("fluid.poly_mech.petroleum", "石油");
        add("item.poly_mech.petroleum_bucket", "石油桶");
        add("block.poly_mech.petroleum", "石油");

        // 化学流体（真实存在的化学物质，不可放置）
        for (ChemicalFluid chem : ChemicalFluid.values()) {
            String zhName = CHEMICAL_ZH.get(chem.getId());
            add("fluid.poly_mech." + chem.getId(), zhName);
            // 所有化学流体都有桶（气体/等离子体为不可放置的桶）
            add("item.poly_mech." + chem.getId() + "_bucket", zhName + "桶");
        }

        // 熔融金属（每种材料一条，温度≈熔点，带桶）
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            String zhName = MATERIAL_ZH_NAMES.getOrDefault(materialName, materialName);
            add("fluid.poly_mech.molten_" + materialName, "熔融" + zhName);
            add("item.poly_mech.molten_" + materialName + "_bucket", "熔融" + zhName + "桶");
        }

        // 等离子体（周期表全118元素，也有可盛装桶）
        for (ModElements element : ModElements.values()) {
            String zhName = ELEMENT_ZH.getOrDefault(element.getId(), element.getSymbol());
            add("fluid.poly_mech." + element.getId() + "_plasma", zhName + "等离子体");
            add("item.poly_mech." + element.getId() + "_plasma_bucket", zhName + "等离子体桶");
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
        // 化学式成分百分比（Shift显示）
        add("tooltip.poly_mech.formula.shift_hint", "按住 Shift 查看成分比例");
        add("tooltip.poly_mech.formula.composition", "成分比例：");
        add("tooltip.poly_mech.mineral.properties", "莫氏硬度：%s | 密度：%s g/cm³ | 晶系：%s | 成因：%s");
        add("tooltip.poly_mech.mineral.process", "工艺路线：%s");
        add("tooltip.poly_mech.crystal.cubic", "等轴晶系");
        add("tooltip.poly_mech.crystal.tetragonal", "四方晶系");
        add("tooltip.poly_mech.crystal.hexagonal", "六方晶系");
        add("tooltip.poly_mech.crystal.orthorhombic", "斜方晶系");
        add("tooltip.poly_mech.crystal.monoclinic", "单斜晶系");
        add("tooltip.poly_mech.crystal.triclinic", "三斜晶系");
        add("tooltip.poly_mech.crystal.amorphous", "非晶质");
        add("tooltip.poly_mech.crystal.unknown", "未知");
        add("tooltip.poly_mech.genesis.magmatic", "岩浆成因");
        add("tooltip.poly_mech.genesis.hydrothermal", "热液成因");
        add("tooltip.poly_mech.genesis.sedimentary", "沉积成因");
        add("tooltip.poly_mech.genesis.metamorphic", "变质成因");
        add("tooltip.poly_mech.genesis.weathering", "风化成因");
        add("tooltip.poly_mech.genesis.placer", "砂矿成因");
        add("tooltip.poly_mech.genesis.evaporite", "蒸发岩成因");
        add("tooltip.poly_mech.genesis.volcanic_hydrothermal", "火山-热液成因");

        // 侧面方块类型
        add("side_type.poly_mech.normal", "机器外壳");
        add("side_type.poly_mech.fluid_input", "流体输入仓");
        add("side_type.poly_mech.fluid_output", "流体输出仓");
        add("side_type.poly_mech.item_input", "物品输入仓");
        add("side_type.poly_mech.item_output", "物品输出仓");
        
        // 添加蓝图工具的翻译
        add(ModItems.BLUEPRINT.get(), "蓝图");
        add(ModItems.COKE.get(), "焦煤");

        // 电网（真实电线电网系统）
        add(ModBlocks.CONNECTOR.get(), "连接器");
        add(ModBlocks.CONCRETE_POLE.get(), "混凝土电杆");
        // 线轴（数据驱动：金属名共用MATERIAL_ZH_NAMES，绝缘变体加“绝缘”前缀）
        for (GridWireType wireType : GridWireType.values()) {
            String metalZh = MATERIAL_ZH_NAMES.getOrDefault(wireType.metalName(), wireType.metalName());
            String name = wireType.isInsulated() ? metalZh + "绝缘线轴" : metalZh + "线轴";
            add("item.poly_mech." + wireType.spoolItemName(), name);
        }
        add(ModItems.EMPTY_SPOOL.get(), "空线轴");
        add(ModItems.WIRE_CUTTER.get(), "剪线钳");
        add(ModItems.CLAMP_METER.get(), "钳形表");
        // 连接器tooltip
        add("tooltip.poly_mech.connector.node", "电网接入点，可与电线相连");
        add("tooltip.poly_mech.connector.stack", "右键已放置的连接器可堆叠至 4 个");
        add("tooltip.poly_mech.connector.wire", "使用线轴右键拉线接入电网");
        // 线轴电气参数tooltip
        add("tooltip.poly_mech.wire.tier", "电压等级：%s");
        add("tooltip.poly_mech.wire.max_voltage", "最大电压：%d FE/t");
        add("tooltip.poly_mech.wire.max_amperage", "最大电流：%d A");
        add("tooltip.poly_mech.wire.max_power", "最大传输功率：%d FE/t");
        add("tooltip.poly_mech.wire.resistance", "线损电阻：%s Ω/格");
        add("tooltip.poly_mech.wire.loss_note", "线损 = 电流² × 总电阻（随线长累积）");
        add("tooltip.poly_mech.wire.max_length", "最大拉线长度：%d 格");

        // 剪线钳
        add("tooltip.poly_mech.wire_cutter", "右键瞄准电线可查看并剪断连接");
        add("gui.poly_mech.wire_cutter.length", "长度: %s 格");
        add("gui.poly_mech.wire_cutter.total_resistance", "总电阻: %s Ω");
        add("gui.poly_mech.wire_cutter.nodes", "端点: %s ⇔ %s");
        add("gui.poly_mech.wire_cutter.hint", "右键剪断");

        // 钳形表
        add("tooltip.poly_mech.clamp_meter", "对准电线右击测量电流/电压");
        add("gui.poly_mech.clamp_meter.prompt", "对准电线右键测量");
        add("gui.poly_mech.clamp_meter.wire", "电线: %s");
        add("gui.poly_mech.clamp_meter.measuring", "测量中...");
        add("gui.poly_mech.clamp_meter.voltage", "电压: %d FE/t");
        add("gui.poly_mech.clamp_meter.current", "电流: %s A");
        add("gui.poly_mech.clamp_meter.power", "功率: %s FE/t");

        add("tooltip.poly_mech.wire.insulated", "绝缘线");

        // 蓄电池
        add(ModBlocks.BATTERY.get(), "蓄电池");
        add(ModBlocks.CREATIVE_BATTERY.get(), "创造模式蓄电池");
        add("gui.poly_mech.battery.energy", "储能: %d / %d FE");
        add("gui.poly_mech.battery.voltage", "电压等级: %s (%d FE/t)");
        add("gui.poly_mech.battery.grid_voltage", "电网电压: %d FE/t");
        add("gui.poly_mech.battery.rated_voltage", "额定电压: %s (%d FE/t)");
        add("gui.poly_mech.battery.input_rate", "输入速率: %d FE/t");
        add("gui.poly_mech.battery.output_rate", "输出速率: %d FE/t");
        add("gui.poly_mech.battery.tooltip_enable", "点击开机/关机");
        add("gui.poly_mech.battery.energy_stored", "储能: %s %s");
        add("gui.poly_mech.battery.input_rate_u", "输入速率: %s %s/t");
        add("gui.poly_mech.battery.output_rate_u", "输出速率: %s %s/t");
        add("gui.poly_mech.battery.energy_tab", "点击切换单位（当前: %s）");

        // 电压等级
        add("voltage_tier.poly_mech.ulv", "ULV");
        add("voltage_tier.poly_mech.lv", "LV");
        add("voltage_tier.poly_mech.mv", "MV");
        add("voltage_tier.poly_mech.hv", "HV");
        add("voltage_tier.poly_mech.ev", "EV");
        add("voltage_tier.poly_mech.iv", "IV");
        add("voltage_tier.poly_mech.luv", "LuV");
        add("voltage_tier.poly_mech.zpm", "ZPM");
        add("voltage_tier.poly_mech.uv", "UV");
        add("voltage_tier.poly_mech.uhv", "UHV");

        // 面配置
        add("gui.poly_mech.side_config.title", "面配置");
        add("gui.poly_mech.side_config.config_type", "配置类型：%s");
        add("gui.poly_mech.side_config.eject", "自动弹出：%s");
        add("gui.poly_mech.side_config.eject_on", "开");
        add("gui.poly_mech.side_config.eject_off", "关");
        add("gui.poly_mech.side_config.no_eject", "无自动弹出");
        add("gui.poly_mech.side_config.auto_eject", "自动弹出");
        add("gui.poly_mech.side_config.clear", "清除面");
        add("gui.poly_mech.side_config.clear_all", "清除所有类型的所有面");
        add("gui.poly_mech.side_config.increment", "递增");
        add("gui.poly_mech.side_config.cannot_eject", "此类型无法自动弹出");
        add("gui.poly_mech.side_config.tab", "打开面配置");
        add("gui.poly_mech.side_config.tab_energy", "能源");
        add("gui.poly_mech.side_config.tab_item", "物品");
        add("gui.poly_mech.side_config.tab_fluid", "流体");
        add("gui.poly_mech.side_config.none", "无");
        add("gui.poly_mech.side_config.in", "输入");
        add("gui.poly_mech.side_config.out", "输出");
        add("gui.poly_mech.side_config.face.up", "顶面");
        add("gui.poly_mech.side_config.face.down", "底面");
        add("gui.poly_mech.side_config.face.north", "北面");
        add("gui.poly_mech.side_config.face.south", "南面");
        add("gui.poly_mech.side_config.face.east", "东面");
        add("gui.poly_mech.side_config.face.west", "西面");
        add("gui.poly_mech.side_config.back", "返回");
        add("gui.poly_mech.side_config.close", "关闭");
        add("gui.poly_mech.side_config.bottom_label", "槽位");

        // 线轴交互提示
        add("message.poly_mech.wire_spool.cancelled", "已取消选中起点");
        add("message.poly_mech.wire_spool.selected", "已选中节点：%s");
        add("message.poly_mech.wire_spool.same_node", "不能将节点连接到自身！");
        add("message.poly_mech.wire_spool.already_connected", "这两个节点已经连接了！");
        add("message.poly_mech.wire_spool.too_far", "距离太远！最大拉线长度：%s 格");
        add("message.poly_mech.wire_spool.connected", "电线已连接！");
        add("message.poly_mech.empty_spool.disconnected", "已断开 %d 根电线");
        add("message.poly_mech.empty_spool.no_wire", "该节点没有连接电线");
        add("message.poly_mech.wire_cutter.cut", "已剪断电线连接");

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
        add("itemGroup.mineral_tab", "Ploy Mech:矿物");
        add("itemGroup.pipe_tab", "Ploy Mech:管道与物流相关");
        add("itemGroup.tool_tab", "Ploy Mech:工具");
        add("itemGroup.fluid_cell_tab", "Ploy Mech:流体单元");
        add("itemGroup.bucket_tab", "Ploy Mech:流体桶");
    }
}