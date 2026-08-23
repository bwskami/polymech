package com.mss.polymech.api.material;

import com.mss.polymech.fluid.ModElements;

import java.util.*;

/*
 * 材料注册表，集中管理模组中所有可用的材料定义。
 * <p>
 * 该类采用数据驱动设计，维护一个材料名称列表。
 * 这些材料名称会与{@link com.mss.polymech.api.item.ItemTagPrefix}组合，
 * 自动生成对应的物品（如steel_ingot、brass_ingot等）。
 * </p>
 * 
 * <h2>添加新材料：</h2>
 * <pre>{@code
 * // 在static块中添加新材料名称
 * MATERIAL_NAMES.add("copper");
 * 
 * // 系统会自动生成：
 * // - copper_ingot（如果INGOT前缀存在）
 * // - copper_ingot（INGOT前缀，shouldGenerate检查材料是否有锭）
 * }</pre>
 * 
 * <h2>材料命名规范：</h2>
 * <ul>
 *   <li>使用小写字母和下划线</li>
 *   <li>合金材料使用完整名称，如stainless_steel</li>
 *   <li>避免使用特殊字符和空格</li>
 * </ul>
 * 
 * @see com.mss.polymech.api.item.ModItemTypes
 * @see com.mss.polymech.item.ModItems
 */
public class MaterialRegistry {
    /** 存储所有已注册材料名称的列表 */
    private static final List<String> MATERIAL_NAMES = new ArrayList<>();

    static {
        // ========== 单质金属 ==========
        MATERIAL_NAMES.add("steel");           // 钢
        MATERIAL_NAMES.add("aluminium");       // 铝
        MATERIAL_NAMES.add("nickel");          // 镍
        MATERIAL_NAMES.add("tin");             // 锡
        MATERIAL_NAMES.add("zinc");            // 锌

        // ========== 从GregTech Modern抄录的真实单质金属（跳过虚构元素与核燃料） ==========
        MATERIAL_NAMES.add("silver");          // 银 Ag
        MATERIAL_NAMES.add("lead");            // 铅 Pb
        MATERIAL_NAMES.add("chromium");        // 铬 Cr
        MATERIAL_NAMES.add("titanium");        // 钛 Ti
        MATERIAL_NAMES.add("tungsten");        // 钨 W
        MATERIAL_NAMES.add("platinum");        // 铂 Pt
        MATERIAL_NAMES.add("osmium");          // 锇 Os
        MATERIAL_NAMES.add("iridium");         // 铱 Ir
        MATERIAL_NAMES.add("palladium");       // 钯 Pd
        MATERIAL_NAMES.add("cobalt");          // 钴 Co
        MATERIAL_NAMES.add("manganese");       // 锰 Mn
        MATERIAL_NAMES.add("molybdenum");      // 钼 Mo
        MATERIAL_NAMES.add("silicon");         // 硅 Si
        MATERIAL_NAMES.add("bismuth");         // 铋 Bi
        MATERIAL_NAMES.add("antimony");        // 锑 Sb
        MATERIAL_NAMES.add("gallium");         // 镓 Ga
        MATERIAL_NAMES.add("indium");          // 铟 In
        MATERIAL_NAMES.add("tantalum");        // 钽 Ta
        MATERIAL_NAMES.add("niobium");         // 铌 Nb
        MATERIAL_NAMES.add("vanadium");        // 钒 V
        MATERIAL_NAMES.add("neodymium");       // 钕 Nd
        MATERIAL_NAMES.add("beryllium");       // 铍 Be
        // 第二批补全的周期表真实金属
        MATERIAL_NAMES.add("europium");        // 铕 Eu
        MATERIAL_NAMES.add("samarium");        // 钐 Sm
        MATERIAL_NAMES.add("yttrium");         // 钇 Y
        MATERIAL_NAMES.add("rhodium");         // 铑 Rh
        MATERIAL_NAMES.add("ruthenium");       // 钌 Ru
        MATERIAL_NAMES.add("thorium");         // 钍 Th
        MATERIAL_NAMES.add("uranium");         // 铀 U
        MATERIAL_NAMES.add("plutonium");       // 钚 Pu
        // 粉状金属（周期表上的碱金属/碱土金属/稀土等，只生成粉，不生成锭）
        MATERIAL_NAMES.add("lithium");         // 锂 Li
        MATERIAL_NAMES.add("sodium");          // 钠 Na
        MATERIAL_NAMES.add("potassium");       // 钾 K
        MATERIAL_NAMES.add("rubidium");        // 铷 Rb
        MATERIAL_NAMES.add("caesium");         // 铯 Cs
        MATERIAL_NAMES.add("francium");        // 钫 Fr
        MATERIAL_NAMES.add("magnesium");       // 镁 Mg
        MATERIAL_NAMES.add("calcium");         // 钙 Ca
        MATERIAL_NAMES.add("strontium");       // 锶 Sr
        MATERIAL_NAMES.add("barium");          // 钡 Ba
        MATERIAL_NAMES.add("radium");          // 镭 Ra
        MATERIAL_NAMES.add("scandium");        // 钪 Sc
        MATERIAL_NAMES.add("hafnium");         // 铪 Hf
        MATERIAL_NAMES.add("zirconium");       // 锆 Zr
        MATERIAL_NAMES.add("rhenium");         // 铼 Re
        MATERIAL_NAMES.add("cadmium");         // 镉 Cd
        MATERIAL_NAMES.add("lanthanum");       // 镧 La
        MATERIAL_NAMES.add("cerium");          // 铈 Ce
        MATERIAL_NAMES.add("praseodymium");    // 镨 Pr
        MATERIAL_NAMES.add("promethium");      // 钷 Pm
        MATERIAL_NAMES.add("gadolinium");      // 钆 Gd
        MATERIAL_NAMES.add("terbium");         // 铽 Tb
        MATERIAL_NAMES.add("dysprosium");      // 镝 Dy
        MATERIAL_NAMES.add("holmium");         // 钬 Ho
        MATERIAL_NAMES.add("erbium");          // 铒 Er
        MATERIAL_NAMES.add("thulium");         // 铥 Tm
        MATERIAL_NAMES.add("ytterbium");       // 镱 Yb
        MATERIAL_NAMES.add("lutetium");        // 镥 Lu
        MATERIAL_NAMES.add("actinium");        // 锕 Ac
        MATERIAL_NAMES.add("protactinium");    // 镤 Pa
        MATERIAL_NAMES.add("neptunium");       // 镎 Np
        MATERIAL_NAMES.add("americium");       // 镅 Am

        // ========== 原版三大金属（只产粉，不产锭：锭由原版提供） ==========
        MATERIAL_NAMES.add("iron");            // 铁 Fe（粗矿破碎→铁粉→熔炼原版铁锭）
        MATERIAL_NAMES.add("copper");          // 铜 Cu
        MATERIAL_NAMES.add("gold");            // 金 Au

        // ========== 非金属工业矿物（矿石直接产粉，格雷式） ==========
        MATERIAL_NAMES.add("sulfur");          // 硫磺 S
        MATERIAL_NAMES.add("graphite");        // 石墨 C
        MATERIAL_NAMES.add("saltpeter");       // 硝石 KNO3
        MATERIAL_NAMES.add("sylvite");         // 钾石盐 KCl
        MATERIAL_NAMES.add("salt");            // 盐 NaCl（岩盐/salt共用）
        MATERIAL_NAMES.add("gypsum");          // 石膏 CaSO4·2H2O
        MATERIAL_NAMES.add("cinnabar");        // 朱砂 HgS
        MATERIAL_NAMES.add("cryolite");        // 冰晶石 Na3AlF6
        MATERIAL_NAMES.add("borax");           // 硼砂 Na2B4O7·10H2O
        MATERIAL_NAMES.add("calcite");         // 方解石 CaCO3
        MATERIAL_NAMES.add("barite");          // 重晶石 BaSO4
        MATERIAL_NAMES.add("asbestos");        // 石棉
        MATERIAL_NAMES.add("mica");            // 云母
        MATERIAL_NAMES.add("talc");            // 滑石（皂石共用）
        MATERIAL_NAMES.add("kyanite");         // 蓝晶石 Al2SiO5
        MATERIAL_NAMES.add("diatomite");       // 硅藻土
        MATERIAL_NAMES.add("bentonite");       // 膨润土
        MATERIAL_NAMES.add("fullers_earth");   // 漂白土
        MATERIAL_NAMES.add("zeolite");         // 沸石
        MATERIAL_NAMES.add("phosphate");       // 磷酸盐（磷酸三钙产粉）
        MATERIAL_NAMES.add("pyrite");          // 黄铁矿 FeS2
        MATERIAL_NAMES.add("olivine");         // 橄榄石

        // ========== 合金 ==========
        MATERIAL_NAMES.add("brass");           // 黄铜（铜锌合金）
        MATERIAL_NAMES.add("bronze");          // 青铜（铜锡合金）
        MATERIAL_NAMES.add("invar");           // 因瓦合金（铁镍合金）
        MATERIAL_NAMES.add("cupronickel");     // 白铜（铜镍合金）
        MATERIAL_NAMES.add("stainless_steel"); // 不锈钢
        MATERIAL_NAMES.add("electrum");        // 琥珀金（金银合金，GTM抄录）

        // ========== 宝石/晶体 ==========
        // 形态为宝石(gem)与粉(dust)，不生成锭/板等金属形态（见GemMaterials）
        for (String gem : GemMaterials.getGems()) {
            MATERIAL_NAMES.add(gem);
        }

    }

    /**
     * 材料化学式表（材料名→化学式，参考GregTech Modern的材料化学式属性）。
     * <p>
     * 用于材料物品（锭、粉、板等）的tooltip展示；与语言无关，
     * 化学式本身是国际通用符号，无需翻译。
     * </p>
     */
    private static final Map<String, String> MATERIAL_FORMULAS = Map.ofEntries(
            Map.entry("steel", "Fe"),                       // 钢（以铁为主）
            Map.entry("aluminium", "Al"),                   // 铝
            Map.entry("nickel", "Ni"),                      // 镍
            Map.entry("tin", "Sn"),                         // 锡
            Map.entry("zinc", "Zn"),                        // 锌
            // 原版三大金属（只产粉）
            Map.entry("iron", "Fe"),
            Map.entry("copper", "Cu"),
            Map.entry("gold", "Au"),
            // 非金属工业矿物
            Map.entry("sulfur", "S"),
            Map.entry("graphite", "C"),
            Map.entry("saltpeter", "KNO3"),
            Map.entry("sylvite", "KCl"),
            Map.entry("salt", "NaCl"),
            Map.entry("gypsum", "CaSO4·2H2O"),
            Map.entry("cinnabar", "HgS"),
            Map.entry("cryolite", "Na3AlF6"),
            Map.entry("borax", "Na2B4O7·10H2O"),
            Map.entry("calcite", "CaCO3"),
            Map.entry("barite", "BaSO4"),
            Map.entry("asbestos", "Mg3Si2O5(OH)4"),
            Map.entry("mica", "KAl2(AlSi3O10)(F,OH)2"),
            Map.entry("talc", "Mg3Si4O10(OH)2"),
            Map.entry("kyanite", "Al2SiO5"),
            Map.entry("diatomite", "SiO2"),
            Map.entry("bentonite", "(Na,Ca)(Al,Mg)2Si4O10·nH2O"),
            Map.entry("fullers_earth", "(Mg,Al)2Si4O10·nH2O"),
            Map.entry("zeolite", "Na2Al2Si3O10·nH2O"),
            Map.entry("phosphate", "Ca3(PO4)2"),
            Map.entry("pyrite", "FeS2"),
            Map.entry("olivine", "(Mg,Fe)2SiO4"),
            // 抄录的真实单质金属
            Map.entry("silver", "Ag"),
            Map.entry("lead", "Pb"),
            Map.entry("chromium", "Cr"),
            Map.entry("titanium", "Ti"),
            Map.entry("tungsten", "W"),
            Map.entry("platinum", "Pt"),
            Map.entry("osmium", "Os"),
            Map.entry("iridium", "Ir"),
            Map.entry("palladium", "Pd"),
            Map.entry("cobalt", "Co"),
            Map.entry("manganese", "Mn"),
            Map.entry("molybdenum", "Mo"),
            Map.entry("silicon", "Si"),
            Map.entry("bismuth", "Bi"),
            Map.entry("antimony", "Sb"),
            Map.entry("gallium", "Ga"),
            Map.entry("indium", "In"),
            Map.entry("tantalum", "Ta"),
            Map.entry("niobium", "Nb"),
            Map.entry("vanadium", "V"),
            Map.entry("neodymium", "Nd"),
            Map.entry("beryllium", "Be"),
            // 第二批补全的锭状金属
            Map.entry("europium", "Eu"),
            Map.entry("samarium", "Sm"),
            Map.entry("yttrium", "Y"),
            Map.entry("rhodium", "Rh"),
            Map.entry("ruthenium", "Ru"),
            Map.entry("thorium", "Th"),
            Map.entry("uranium", "U"),
            Map.entry("plutonium", "Pu"),
            // 粉状金属
            Map.entry("lithium", "Li"),
            Map.entry("sodium", "Na"),
            Map.entry("potassium", "K"),
            Map.entry("rubidium", "Rb"),
            Map.entry("caesium", "Cs"),
            Map.entry("francium", "Fr"),
            Map.entry("magnesium", "Mg"),
            Map.entry("calcium", "Ca"),
            Map.entry("strontium", "Sr"),
            Map.entry("barium", "Ba"),
            Map.entry("radium", "Ra"),
            Map.entry("scandium", "Sc"),
            Map.entry("hafnium", "Hf"),
            Map.entry("zirconium", "Zr"),
            Map.entry("rhenium", "Re"),
            Map.entry("cadmium", "Cd"),
            Map.entry("lanthanum", "La"),
            Map.entry("cerium", "Ce"),
            Map.entry("praseodymium", "Pr"),
            Map.entry("promethium", "Pm"),
            Map.entry("gadolinium", "Gd"),
            Map.entry("terbium", "Tb"),
            Map.entry("dysprosium", "Dy"),
            Map.entry("holmium", "Ho"),
            Map.entry("erbium", "Er"),
            Map.entry("thulium", "Tm"),
            Map.entry("ytterbium", "Yb"),
            Map.entry("lutetium", "Lu"),
            Map.entry("actinium", "Ac"),
            Map.entry("protactinium", "Pa"),
            Map.entry("neptunium", "Np"),
            Map.entry("americium", "Am"),
            // 合金
            Map.entry("brass", "CuZn"),                     // 黄铜
            Map.entry("bronze", "Cu3Sn"),                   // 青铜
            Map.entry("invar", "Fe2Ni"),                    // 因瓦合金
            Map.entry("cupronickel", "CuNi"),               // 白铜
            Map.entry("stainless_steel", "Fe6CrMnNi"),      // 不锈钢
            Map.entry("electrum", "AuAg")                   // 琥珀金
    );

    /**
     * 材料平均相对原子质量表（单质金属=元素质量；合金=组分平均值）。
     * <p>
     * 用于金属存储块选择normal/heavy贴图（阈值标准见ModBlocks.MASS_THRESHOLD）；
     * 粉状金属与未列入的材料可从{@link ModElements}按名称反查。
     * </p>
     */
    private static final Map<String, Double> MATERIAL_ATOMIC_MASS = Map.ofEntries(
            Map.entry("steel", 55.845),
            Map.entry("aluminium", 26.982),
            Map.entry("nickel", 58.693),
            Map.entry("tin", 118.710),
            Map.entry("zinc", 65.38),
            Map.entry("silver", 107.868),
            Map.entry("lead", 207.2),
            Map.entry("chromium", 51.996),
            Map.entry("titanium", 47.867),
            Map.entry("tungsten", 183.84),
            Map.entry("platinum", 195.084),
            Map.entry("osmium", 190.23),
            Map.entry("iridium", 192.217),
            Map.entry("palladium", 106.42),
            Map.entry("cobalt", 58.933),
            Map.entry("manganese", 54.938),
            Map.entry("molybdenum", 95.95),
            Map.entry("silicon", 28.085),
            Map.entry("bismuth", 208.980),
            Map.entry("antimony", 121.760),
            Map.entry("gallium", 69.723),
            Map.entry("indium", 114.818),
            Map.entry("tantalum", 180.948),
            Map.entry("niobium", 92.906),
            Map.entry("vanadium", 50.942),
            Map.entry("neodymium", 144.242),
            Map.entry("beryllium", 9.012),
            Map.entry("europium", 151.964),
            Map.entry("samarium", 150.36),
            Map.entry("yttrium", 88.906),
            Map.entry("rhodium", 102.906),
            Map.entry("ruthenium", 101.07),
            Map.entry("thorium", 232.038),
            Map.entry("uranium", 238.029),
            Map.entry("plutonium", 244.0),
            // 合金：组分平均原子质量
            Map.entry("brass", 64.46),
            Map.entry("bronze", 77.39),
            Map.entry("invar", 56.80),
            Map.entry("cupronickel", 61.12),
            Map.entry("stainless_steel", 56.25),
            Map.entry("electrum", 152.43)
    );

    /*
     * 获取材料的平均相对原子质量：先查显式表，再按名称从周期表反查。
     *
     * @param materialName 材料名称
     * @return 原子质量；无法确定时返回-1
     */
    public static double getAtomicMass(String materialName) {
        Double mass = MATERIAL_ATOMIC_MASS.get(materialName);
        if (mass != null) return mass;
        for (ModElements element : ModElements.values()) {
            if (element.getId().equals(materialName)) {
                return element.getAtomicMass();
            }
        }
        return -1;
    }

    /*
     * 获取指定材料的化学式。
     *
     * @param materialName 材料名称
     * @return 化学式字符串；未定义时返回null
     */
    public static String getFormula(String materialName) {
        return MATERIAL_FORMULAS.get(materialName);
    }

    /*
     * 获取所有已注册的材料名称列表。
     * <p>
     * 返回不可修改的列表视图，防止外部代码意外修改注册表。
     * 该列表用于数据驱动的物品生成系统。
     * </p>
     * 
     * @return 所有材料名称的只读列表
     */
    public static List<String> getMaterialNames() {
        return Collections.unmodifiableList(MATERIAL_NAMES);
    }
}
