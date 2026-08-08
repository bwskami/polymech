package com.mss.polymech.fluid;

/**
 * 现实中真实存在的化学物质流体定义（参考GregTech Modern的物质清单）。
 * <p>
 * 每个枚举项只定义与语言无关的数据：注册名、化学式、显示颜色、
 * 用于{@link net.neoforged.neoforge.fluids.FluidType}的物理参数（温度/密度/粘度）、
 * 物态（液体/气体/等离子体）以及危险性标记。
 * 显示名称通过翻译键 {@code fluid.poly_mech.<id>} 解析（各语言由lang文件提供，
 * 中英文由datagen语言Provider生成），代码中不硬编码任何自然语言文本。
 * 对应的流体注册（无方块、不可放置，参考GregTech做法）见{@link ModChemicalFluids}。
 * </p>
 */
public enum ChemicalFluid implements FluidInfo {

    // ========== 酸 / 碱 / 氧化剂（液体，颜色已提高饱和度便于在流体单元中区分）==========
    SULFURIC_ACID("sulfuric_acid", "H2SO4", 0xFFE09000, 295, 1830, 2000, State.LIQUID, true),
    NITRIC_ACID("nitric_acid", "HNO3", 0xFFF0E040, 295, 1510, 1100, State.LIQUID, true),
    HYDROCHLORIC_ACID("hydrochloric_acid", "HCl", 0xFF8AD43C, 295, 1180, 1100, State.LIQUID, true),
    HYDROFLUORIC_ACID("hydrofluoric_acid", "HF", 0xFF30D5C8, 295, 1150, 1100, State.LIQUID, true),
    HYDROGEN_PEROXIDE("hydrogen_peroxide", "H2O2", 0xFF60A8FF, 295, 1450, 1200, State.LIQUID, true),
    SODIUM_HYDROXIDE("sodium_hydroxide", "NaOH", 0xFF2E8B8B, 295, 2130, 1500, State.LIQUID, true),
    AMMONIA_WATER("ammonia_water", "NH3.H2O", 0xFF9FC8F0, 295, 900, 900, State.LIQUID, true),
    ACETIC_ACID("acetic_acid", "CH3COOH", 0xFFD8B060, 295, 1049, 1200, State.LIQUID, false),

    // ========== 有机溶剂（液体）==========
    ETHANOL("ethanol", "C2H5OH", 0xFF98B040, 295, 789, 1200, State.LIQUID, false),
    METHANOL("methanol", "CH3OH", 0xFFC080E0, 295, 792, 1100, State.LIQUID, true),
    ACETONE("acetone", "C3H6O", 0xFF80D0F0, 295, 784, 1000, State.LIQUID, true),
    GLYCEROL("glycerol", "C3H8O3", 0xFFE8C060, 295, 1260, 14000, State.LIQUID, false),
    BENZENE("benzene", "C6H6", 0xFFA86830, 295, 876, 1100, State.LIQUID, true),
    TOLUENE("toluene", "C7H8", 0xFF784820, 295, 867, 1100, State.LIQUID, true),
    PHENOL("phenol", "C6H5OH", 0xFFE880B0, 295, 1070, 1300, State.LIQUID, true),
    NITROBENZENE("nitrobenzene", "C6H5NO2", 0xFFB8A020, 295, 1200, 1500, State.LIQUID, true),

    // ========== 单质 / 氧化物（液体）==========
    BROMINE("bromine", "Br2", 0xFF8C1A00, 295, 3103, 1300, State.LIQUID, true),
    MERCURY("mercury", "Hg", 0xFFB0B8C8, 295, 13534, 1500, State.LIQUID, true),
    SULFUR_TRIOXIDE("sulfur_trioxide", "SO3", 0xFFF0F0E8, 295, 1920, 1200, State.LIQUID, true),

    // ========== 气体 ==========
    HYDROGEN("hydrogen", "H2", 0xFFE8F4FF, 295, 90, 300, State.GAS, true),
    NITROGEN("nitrogen", "N2", 0xFFC0C8E0, 295, 125, 300, State.GAS, false),
    OXYGEN("oxygen", "O2", 0xFF7EB8FF, 295, 143, 300, State.GAS, false),
    CHLORINE("chlorine", "Cl2", 0xFFC0E030, 295, 320, 400, State.GAS, true),
    AMMONIA("ammonia", "NH3", 0xFF60D0E8, 295, 730, 400, State.GAS, true),
    METHANE("methane", "CH4", 0xFF90E090, 295, 660, 300, State.GAS, true),
    PROPANE("propane", "C3H8", 0xFFE0A060, 295, 493, 350, State.GAS, true),
    BUTANE("butane", "C4H10", 0xFFE07840, 295, 573, 350, State.GAS, true),
    CARBON_DIOXIDE("carbon_dioxide", "CO2", 0xFFD0D0D0, 295, 198, 350, State.GAS, false),
    CARBON_MONOXIDE("carbon_monoxide", "CO", 0xFF706858, 295, 125, 350, State.GAS, true),
    SULFUR_DIOXIDE("sulfur_dioxide", "SO2", 0xFFF0C830, 295, 293, 400, State.GAS, true),
    HYDROGEN_SULFIDE("hydrogen_sulfide", "H2S", 0xFFD8D040, 295, 136, 350, State.GAS, true),
    HELIUM("helium", "He", 0xFFFFC0C0, 295, 18, 200, State.GAS, false),
    ARGON("argon", "Ar", 0xFFC090F0, 295, 178, 300, State.GAS, false),

    // ========== 无机酸 / 盐溶液 / 混合液（液体，对齐GregTech Modern清单）==========
    ACIDIC_OSMIUM_SOLUTION("acidic_osmium_solution", "H3ClO5Os", 0xFFDAC5C5, 295, 1300, 1200, State.LIQUID, true),
    AQUA_REGIA("aqua_regia", "H3Cl2NO3", 0xFFFFB132, 295, 1400, 1200, State.LIQUID, true),
    DILUTED_HYDROCHLORIC_ACID("diluted_hydrochloric_acid", "H3ClO", 0xFF99A7A3, 295, 1050, 1000, State.LIQUID, true),
    DILUTED_SULFURIC_ACID("diluted_sulfuric_acid", "H6O9S2", 0xFFC07820, 295, 1100, 1100, State.LIQUID, true),
    PHOSPHORIC_ACID("phosphoric_acid", "H3O4P", 0xFFDCDC01, 295, 1685, 1400, State.LIQUID, true),
    PHTHALIC_ACID("phthalic_acid", "C8H6O4", 0xFFD1D1D1, 295, 1590, 1400, State.LIQUID, true),
    FORMIC_ACID("formic_acid", "CH2O2", 0xFFA6A6A6, 295, 1220, 1100, State.LIQUID, true),
    HYPOCHLOROUS_ACID("hypochlorous_acid", "HClO", 0xFF6F8A91, 295, 1200, 1000, State.LIQUID, true),
    FLUOROANTIMONIC_ACID("fluoroantimonic_acid", "H2F7Sb", 0xFFB0E0E0, 295, 2100, 1200, State.LIQUID, true),
    NITRATION_MIXTURE("nitration_mixture", "H3NO7S", 0xFFE6E2AB, 295, 1500, 1200, State.LIQUID, true),
    SULFURIC_COPPER_SOLUTION("sulfuric_copper_solution", "H2CuO5S", 0xFF48A5C0, 295, 1200, 1200, State.LIQUID, true),
    SULFURIC_NICKEL_SOLUTION("sulfuric_nickel_solution", "H2NiO5S", 0xFF3EB640, 295, 1200, 1200, State.LIQUID, true),
    SODIUM_PERSULFATE("sodium_persulfate", "Na2O8S2", 0xFFC0D8E8, 295, 1400, 1200, State.LIQUID, true),
    RHODIUM_SULFATE("rhodium_sulfate", "O12Rh2S3", 0xFFEEAA55, 1128, 3200, 2500, State.LIQUID, true),
    TITANIUM_TETRACHLORIDE("titanium_tetrachloride", "Cl4Ti", 0xFFD40D5C, 295, 1730, 1200, State.LIQUID, true),
    IRON_II_CHLORIDE("iron_ii_chloride", "Cl2Fe", 0xFFE8E0BE, 295, 1900, 1400, State.LIQUID, true),
    IRON_III_CHLORIDE("iron_iii_chloride", "Cl3Fe", 0xFF060B0B, 295, 1800, 1400, State.LIQUID, true),
    AMMONIUM_FORMATE("ammonium_formate", "CH5NO2", 0xFF93BADB, 295, 1050, 350, State.GAS, false),

    // ========== 有机单体 / 中间体（液体）==========
    FORMALDEHYDE("formaldehyde", "CH2O", 0xFFDDECED, 295, 815, 900, State.LIQUID, true),
    FORMAMIDE("formamide", "CH3NO", 0xFF5CCCB6, 295, 1130, 1400, State.LIQUID, false),
    CHLOROMETHANE("chloromethane", "CH3Cl", 0xFFC82CA0, 295, 911, 350, State.GAS, true),
    DICHLORETHANE("dichloroethane", "C2H4Cl2", 0xFFAFC979, 295, 1250, 1000, State.LIQUID, true),
    GLYCOLONITRILE("glycolonitrile", "C2H3NO", 0xFF5B8C8F, 295, 1100, 1100, State.LIQUID, true),
    DIMETHYLAMINE("dimethylamine", "C2H7N", 0xFF554469, 295, 660, 350, State.GAS, true),
    DIETHYLENETRIAMINE("diethylenetriamine", "C4H13N3", 0xFFA9D9A7, 295, 850, 1000, State.LIQUID, true),
    DIETHYLENETRIAMINE_PENTAACETONITRILE("diethylenetriamine_pentaacetonitrile", "C14H18N8", 0xFFCBBFD6, 295, 1000, 1200, State.LIQUID, true),
    ETHANE("ethane", "C2H6", 0xFFC8C8FF, 295, 544, 350, State.GAS, true),
    ETHYLENE("ethylene", "C2H4", 0xFFE1E1E1, 295, 570, 350, State.GAS, true),
    PROPENE("propene", "C3H6", 0xFFFFDD55, 295, 610, 350, State.GAS, true),
    BUTENE("butene", "C4H8", 0xFFCF5005, 295, 625, 350, State.GAS, true),
    BUTADIENE("butadiene", "C4H6", 0xFFB55A10, 295, 620, 350, State.GAS, true),
    CUMENE("cumene", "C9H12", 0xFF552200, 295, 862, 350, State.GAS, true),
    VINYL_CHLORIDE("vinyl_chloride", "C2H3Cl", 0xFFE1F0F0, 295, 910, 350, State.GAS, true),
    TETRAFLUOROETHYLENE("tetrafluoroethylene", "C2F4", 0xFF7D7D7D, 295, 1500, 350, State.GAS, true),
    DIMETHYLDICHLOROSILANE("dimethyldichlorosilane", "C2H6Cl2Si", 0xFF441650, 295, 1070, 350, State.GAS, true),
    EPICHLOROHYDRIN("epichlorohydrin", "C3H5ClO", 0xFF712400, 295, 1180, 1100, State.LIQUID, true),
    HYDROGEN_CYANIDE("hydrogen_cyanide", "HCN", 0xFF72DBD4, 295, 690, 350, State.GAS, true),
    GLYCERYL_TRINITRATE("glyceryl_trinitrate", "C3H5N3O9", 0xFFE8D080, 295, 1600, 1300, State.LIQUID, true),
    LEAD_ZINC_SOLUTION("lead_zinc_solution", "H2AgOPbS3Zn", 0xFF9090A0, 295, 1500, 1300, State.LIQUID, true),
    INDIUM_CONCENTRATE("indium_concentrate", "", 0xFF0E2950, 295, 1300, 1200, State.LIQUID, false),

    // ========== 聚合物（液体）==========
    EPOXY("epoxy", "C21H25ClO5", 0xFFF6FABD, 400, 1200, 6000, State.LIQUID, false),
    REINFORCED_EPOXY_RESIN("reinforced_epoxy_resin", "C6H4O", 0xFF9ECAAD, 600, 1300, 7000, State.LIQUID, false),
    POLYETHYLENE("polyethylene", "C2H4", 0xFFC8C8C8, 408, 950, 6000, State.LIQUID, false),
    POLYTETRAFLUOROETHYLENE("polytetrafluoroethylene", "C2F4", 0xFF6E6E6E, 600, 2200, 7000, State.LIQUID, false),
    POLYVINYL_CHLORIDE("polyvinyl_chloride", "C2H3Cl", 0xFFFF9955, 373, 1400, 6000, State.LIQUID, false),
    POLYBENZIMIDAZOLE("polybenzimidazole", "C20H12N4", 0xFF464441, 1450, 1300, 8000, State.LIQUID, false),
    POLYCAPROLACTAM("polycaprolactam", "C6H11NO", 0xFF3F3D2D, 493, 1100, 6000, State.LIQUID, false),
    POLYPHENYLENE_SULFIDE("polyphenylene_sulfide", "C6H4S", 0xFF5E5E08, 500, 1350, 7000, State.LIQUID, false),

    // ========== 熔融金属（单质，molten_*之外的缺失项）==========
    MOLTEN_COPPER("copper", "Cu", 0xFFE77C56, 1358, 8000, 4000, State.LIQUID, false),
    MOLTEN_GOLD("gold", "Au", 0xFFFDF55F, 1337, 8000, 4000, State.LIQUID, false),
    MOLTEN_IRON("iron", "Fe", 0xFFEEEEEE, 1811, 8000, 4000, State.LIQUID, false),
    MOLTEN_ANNEALED_COPPER("annealed_copper", "Cu", 0xFFF2C079, 1358, 8000, 4000, State.LIQUID, false),
    MOLTEN_WROUGHT_IRON("wrought_iron", "Fe", 0xFFBCBCBC, 2011, 8000, 4000, State.LIQUID, false),
    MOLTEN_STEEL("steel", "Fe", 0xFFA7A7A7, 2046, 8000, 4000, State.LIQUID, false),
    MOLTEN_ARSENIC("arsenic", "As", 0xFF9C9C8D, 887, 5700, 4000, State.LIQUID, true),
    MOLTEN_CARBON("carbon", "C", 0xFF333030, 4600, 2200, 4000, State.LIQUID, false),

    // ========== 熔融合金 ==========
    MOLTEN_BATTERY_ALLOY("battery_alloy", "Pb4Sb", 0xFFCAC0FF, 660, 9000, 4000, State.LIQUID, true),
    MOLTEN_BISMUTH_BRONZE("bismuth_bronze", "BiCu3Zn", 0xFFFFD26F, 1036, 8500, 4000, State.LIQUID, true),
    MOLTEN_BLACK_BRONZE("black_bronze", "AgAuCu3", 0xFF8B7C70, 1328, 8800, 4000, State.LIQUID, false),
    MOLTEN_COBALT_BRASS("cobalt_brass", "AlCoCu21Zn7", 0xFFBAA365, 1202, 8400, 4000, State.LIQUID, false),
    MOLTEN_KANTHAL("kanthal", "AlCrFe", 0xFFC2D2DF, 1708, 7800, 4000, State.LIQUID, false),
    MOLTEN_MAGNALIUM("magnalium", "Al2Mg", 0xFF98B9E9, 929, 2500, 4000, State.LIQUID, false),
    MOLTEN_MANGANESE_PHOSPHIDE("manganese_phosphide", "MnP", 0xFFE1B454, 1368, 6500, 4000, State.LIQUID, false),
    MOLTEN_NICHROME("nichrome", "CrNi4", 0xFFAF94B2, 1818, 8400, 4000, State.LIQUID, false),
    MOLTEN_OSMIRIDIUM("osmiridium", "Ir3Os", 0xFF47ADB6, 3012, 21000, 4000, State.LIQUID, false),
    MOLTEN_POTIN("potin", "Cu6PbSn2", 0xFFAAADA3, 1084, 8900, 4000, State.LIQUID, true),
    MOLTEN_ROSE_GOLD("rose_gold", "Au4Cu", 0xFFECD5B8, 1341, 15000, 4000, State.LIQUID, false),
    MOLTEN_SOLDERING_ALLOY("soldering_alloy", "Pb3SbSn6", 0xFF8C8CA7, 544, 8500, 4000, State.LIQUID, true),
    MOLTEN_STERLING_SILVER("sterling_silver", "Ag4Cu", 0xFFFAF4DC, 1258, 10400, 4000, State.LIQUID, false),
    MOLTEN_TIN_ALLOY("tin_alloy", "FeSn", 0xFFC8C8C8, 1258, 7800, 4000, State.LIQUID, false),
    MOLTEN_ULTIMET("ultimet", "Co5Cr2MoNi", 0xFF9F9FB1, 1980, 8700, 4000, State.LIQUID, false),
    MOLTEN_VANADIUM_GALLIUM("vanadium_gallium", "GaV3", 0xFF89AEEC, 1712, 6200, 4000, State.LIQUID, false),
    MOLTEN_VANADIUM_STEEL("vanadium_steel", "CrFe7V", 0xFFB59FCC, 2073, 7900, 4000, State.LIQUID, false),
    MOLTEN_NIOBIUM_TITANIUM("niobium_titanium", "NbTi", 0xFFD2D9F9, 2345, 6600, 4000, State.LIQUID, false),
    MOLTEN_BOROSILICATE_GLASS("borosilicate_glass", "BO14Si7", 0xFFFAFAFA, 1921, 2200, 5000, State.LIQUID, false),

    // ========== 超导材料 / 特种陶瓷（熔融）==========
    MOLTEN_GALLIUM_ARSENIDE("gallium_arsenide", "AsGa", 0xFF938FFF, 1511, 5300, 4000, State.LIQUID, true),
    MOLTEN_INDIUM_GALLIUM_PHOSPHIDE("indium_gallium_phosphide", "GaInP", 0xFFA77BD7, 350, 4800, 4000, State.LIQUID, true),
    MOLTEN_NICKEL_ZINC_FERRITE("nickel_zinc_ferrite", "Fe4NiO8Zn", 0xFF3F2821, 1410, 5300, 4000, State.LIQUID, false),
    MOLTEN_MAGNESIUM_DIBORIDE("magnesium_diboride", "B2Mg", 0xFF603C1A, 1103, 2600, 4000, State.LIQUID, false),
    MOLTEN_YTTRIUM_BARIUM_CUPRATE("yttrium_barium_cuprate", "Ba2Cu3O7Y", 0xFF796D72, 1799, 6400, 4000, State.LIQUID, false),
    MOLTEN_MERCURY_BARIUM_CALCIUM_CUPRATE("mercury_barium_calcium_cuprate", "Ba2Ca2Cu3HgO8", 0xFF928547, 1075, 7500, 4000, State.LIQUID, true),
    MOLTEN_URANIUM_TRIPLATINUM("uranium_triplatinum", "Pt3U238", 0xFF457045, 1882, 19000, 4000, State.LIQUID, true),
    MOLTEN_SAMARIUM_IRON_ARSENIC_OXIDE("samarium_iron_arsenic_oxide", "AsFeOSm", 0xFF850E85, 1347, 7800, 4000, State.LIQUID, true),
    MOLTEN_INDIUM_TIN_BARIUM_TITANIUM_CUPRATE("indium_tin_barium_titanium_cuprate", "Ba2Cu7In4O14Sn2Ti", 0xFF686760, 1012, 7000, 4000, State.LIQUID, false),

    // ========== 特殊液体（油品 / MC物质 / 放射性熔融物等）==========
    ICE("ice", "H2O", 0xFFD0F0FF, 273, 920, 1000, State.LIQUID, false),
    MOLTEN_GLASS("glass", "O2Si", 0xFFFFFFFF, 1200, 2500, 5000, State.LIQUID, false),
    // 石化馏分均为混合物，化学式取其代表性组分（便于显示结构式）
    OIL("oil", "C16H34", 0xFF0A0A0A, 295, 850, 3000, State.LIQUID, true),
    RAW_OIL("raw_oil", "C16H34", 0xFF0A0A0A, 295, 870, 3500, State.LIQUID, true),
    HEAVY_OIL("heavy_oil", "C18H38", 0xFF0A0A0A, 295, 950, 5000, State.LIQUID, true),
    LIGHT_OIL("light_oil", "C8H18", 0xFF0A0A0A, 295, 800, 2500, State.LIQUID, true),
    NAPHTHA("naphtha", "C6H14", 0xFFFFE0A0, 295, 720, 900, State.LIQUID, true),
    SULFURIC_NAPHTHA("sulfuric_naphtha", "", 0xFFE0C080, 295, 750, 900, State.LIQUID, true),
    LIGHT_FUEL("light_fuel", "C8H18", 0xFFFFC860, 295, 780, 1000, State.LIQUID, true),
    SULFURIC_LIGHT_FUEL("sulfuric_light_fuel", "", 0xFFE0B050, 295, 800, 1000, State.LIQUID, true),
    LIGHTLY_HYDRO_CRACKED_LIGHT_FUEL("lightly_hydro_cracked_light_fuel", "C8H18", 0xFFB7AF08, 775, 780, 1000, State.LIQUID, true),
    LIGHTLY_STEAM_CRACKED_LIGHT_FUEL("lightly_steam_cracked_light_fuel", "C8H18", 0xFFB7AF08, 775, 780, 1000, State.LIQUID, true),
    SEVERELY_HYDRO_CRACKED_LIGHT_FUEL("severely_hydro_cracked_light_fuel", "C8H18", 0xFFB7AF08, 775, 780, 1000, State.LIQUID, true),
    SEVERELY_STEAM_CRACKED_LIGHT_FUEL("severely_steam_cracked_light_fuel", "C8H18", 0xFFB7AF08, 775, 780, 1000, State.LIQUID, true),
    LIGHTLY_HYDRO_CRACKED_NAPHTHA("lightly_hydro_cracked_naphtha", "C6H14", 0xFFBFB608, 775, 720, 900, State.LIQUID, true),
    LIGHTLY_STEAM_CRACKED_NAPHTHA("lightly_steam_cracked_naphtha", "C6H14", 0xFFBFB608, 775, 720, 900, State.LIQUID, true),
    SEVERELY_HYDRO_CRACKED_NAPHTHA("severely_hydro_cracked_naphtha", "C6H14", 0xFFBFB608, 775, 720, 900, State.LIQUID, true),
    SEVERELY_STEAM_CRACKED_NAPHTHA("severely_steam_cracked_naphtha", "C6H14", 0xFFBFB608, 775, 720, 900, State.LIQUID, true),
    HEAVY_FUEL("heavy_fuel", "C18H38", 0xFF906030, 295, 900, 2000, State.LIQUID, true),
    SULFURIC_HEAVY_FUEL("sulfuric_heavy_fuel", "", 0xFF805020, 295, 920, 2000, State.LIQUID, true),
    LIGHTLY_HYDRO_CRACKED_HEAVY_FUEL("lightly_hydro_cracked_heavy_fuel", "C18H38", 0xFFFFFF00, 775, 900, 2000, State.LIQUID, true),
    LIGHTLY_STEAM_CRACKED_HEAVY_FUEL("lightly_steam_cracked_heavy_fuel", "C18H38", 0xFFE0E060, 775, 900, 2000, State.LIQUID, true),
    SEVERELY_HYDRO_CRACKED_HEAVY_FUEL("severely_hydro_cracked_heavy_fuel", "C18H38", 0xFFFFFF00, 775, 900, 2000, State.LIQUID, true),
    SEVERELY_STEAM_CRACKED_HEAVY_FUEL("severely_steam_cracked_heavy_fuel", "C18H38", 0xFFE0E060, 775, 900, 2000, State.LIQUID, true),
    DIESEL("diesel", "C12H26", 0xFFD0A040, 295, 850, 1200, State.LIQUID, true),
    CETANE_BOOSTED_DIESEL("cetane_boosted_diesel", "C12H26", 0xFFC8FF00, 295, 850, 1200, State.LIQUID, true),
    LPG("lpg", "C4H10", 0xFFFCFCAC, 295, 550, 800, State.LIQUID, true),
    LUBRICANT("lubricant", "C20H42", 0xFFFFD080, 295, 900, 8000, State.LIQUID, false),
    CREOSOTE("creosote", "C10H8", 0xFF804000, 295, 1050, 1500, State.LIQUID, true),
    BIOMASS("biomass", "", 0xFF00FF00, 295, 900, 1500, State.LIQUID, false),
    FERMENTED_BIOMASS("fermented_biomass", "", 0xFF445500, 300, 950, 1500, State.LIQUID, false),
    CRACKED_BAUXITE_SLURRY("cracked_bauxite_slurry", "", 0xFF052C50, 295, 1400, 3000, State.LIQUID, false),
    CONCRETE("concrete", "", 0xFFFAF3E8, 286, 2300, 6000, State.LIQUID, false),
    GLUE("glue", "", 0xFFE0D8B0, 295, 1050, 5000, State.LIQUID, false),
    MILK("milk", "", 0xFFFFFBF0, 295, 1030, 1100, State.LIQUID, false),
    SEED_OIL("seed_oil", "", 0xFFF0F0D0, 295, 920, 1500, State.LIQUID, false),
    LIQUID_AIR("liquid_air", "C5H2ArHe2N70O33", 0xFFA9D0F5, 97, 900, 800, State.LIQUID, false),
    RUBBER("rubber", "C5H8", 0xFF353529, 400, 920, 5000, State.LIQUID, false),
    SILICONE_RUBBER("silicone_rubber", "C2H6OSi", 0xFFF0F0F0, 900, 980, 5000, State.LIQUID, false),
    STYRENE_BUTADIENE_RUBBER("styrene_butadiene_rubber", "C20H26", 0xFF34312B, 1000, 940, 5000, State.LIQUID, false),
    MOLTEN_URANIUM_235("uranium_235", "U235", 0xFF46FA46, 1405, 19000, 4000, State.LIQUID, true),
    MOLTEN_URANIUM_238("uranium_238", "U238", 0xFF1D891D, 1405, 19000, 4000, State.LIQUID, true),
    MOLTEN_PLUTONIUM_239("plutonium_239", "Pu239", 0xFFBA2727, 913, 19800, 4000, State.LIQUID, true),
    MOLTEN_PLUTONIUM_241("plutonium_241", "Pu241", 0xFFFF4C4C, 913, 19800, 4000, State.LIQUID, true),

    // ========== 新增气体 ==========
    AIR("air", "Ar9N78O21", 0xFFA9D0F5, 295, 129, 300, State.GAS, false),
    NITRIC_OXIDE("nitric_oxide", "NO", 0xFF7DC8F0, 295, 134, 350, State.GAS, true),
    NITROGEN_DIOXIDE("nitrogen_dioxide", "NO2", 0xFF85FCFF, 295, 188, 350, State.GAS, true),
    NITROUS_OXIDE("nitrous_oxide", "N2O", 0xFF7DC8FF, 295, 198, 350, State.GAS, false),
    DINITROGEN_TETROXIDE("dinitrogen_tetroxide", "N2O4", 0xFF004184, 295, 350, 400, State.GAS, true),
    NITROSYL_CHLORIDE("nitrosyl_chloride", "ClNO", 0xFFF3F100, 295, 216, 350, State.GAS, true),
    MONOCHLORAMINE("monochloramine", "H2ClN", 0xFF3F9F80, 295, 160, 350, State.GAS, true),
    FLUORINE("fluorine", "F2", 0xFFD0F0A0, 295, 170, 350, State.GAS, true),
    NEON("neon", "Ne", 0xFFFAB4B4, 295, 90, 250, State.GAS, false),
    KRYPTON("krypton", "Kr", 0xFF80FF80, 295, 350, 300, State.GAS, false),
    XENON("xenon", "Xe", 0xFF00FFFF, 295, 540, 300, State.GAS, false),
    RADON("radon", "Rn", 0xFFFF39FF, 295, 920, 300, State.GAS, true),
    DEUTERIUM("deuterium", "D2", 0xFFB0C8FF, 295, 180, 300, State.GAS, true),
    TRITIUM("tritium", "T2", 0xFFFF316B, 295, 270, 300, State.GAS, true),
    HELIUM_3("helium_3", "He3", 0xFFFFE0A0, 295, 13, 200, State.GAS, false),
    SULFURIC_GAS("sulfuric_gas", "", 0xFFECDCCC, 295, 200, 350, State.GAS, true),
    REFINERY_GAS("refinery_gas", "", 0xFFB4B4B4, 295, 150, 350, State.GAS, true),
    NATURAL_GAS("natural_gas", "CH4", 0xFFD0E0C0, 295, 720, 300, State.GAS, true),
    COAL_GAS("coal_gas", "CO", 0xFF333333, 295, 100, 350, State.GAS, true),
    WOOD_GAS("wood_gas", "H2", 0xFFDECD87, 295, 110, 350, State.GAS, true),
    HYDRO_CRACKED_BUTADIENE("hydro_cracked_butadiene", "C4H6", 0xFFAD5203, 775, 620, 350, State.GAS, true),
    HYDRO_CRACKED_BUTANE("hydro_cracked_butane", "C4H10", 0xFF852C18, 775, 573, 350, State.GAS, true),
    HYDRO_CRACKED_BUTENE("hydro_cracked_butene", "C4H8", 0xFF993E05, 775, 625, 350, State.GAS, true),
    HYDRO_CRACKED_ETHANE("hydro_cracked_ethane", "C2H6", 0xFF9696BC, 775, 544, 350, State.GAS, true),
    HYDRO_CRACKED_ETHYLENE("hydro_cracked_ethylene", "C2H4", 0xFFA3A3A0, 775, 570, 350, State.GAS, true),
    HYDRO_CRACKED_PROPANE("hydro_cracked_propane", "C3H8", 0xFFBEA540, 775, 493, 350, State.GAS, true),
    HYDRO_CRACKED_PROPENE("hydro_cracked_propene", "C3H6", 0xFFBEA540, 775, 610, 350, State.GAS, true),
    STEAM_CRACKED_BUTADIENE("steam_cracked_butadiene", "C4H6", 0xFFAD5203, 775, 620, 350, State.GAS, true),
    STEAM_CRACKED_BUTANE("steam_cracked_butane", "C4H10", 0xFF852C18, 775, 573, 350, State.GAS, true),
    STEAM_CRACKED_BUTENE("steam_cracked_butene", "C4H8", 0xFF993E05, 775, 625, 350, State.GAS, true),
    STEAM_CRACKED_ETHANE("steam_cracked_ethane", "C2H6", 0xFF9696BC, 775, 544, 350, State.GAS, true),
    STEAM_CRACKED_ETHYLENE("steam_cracked_ethylene", "C2H4", 0xFFA3A3A0, 775, 570, 350, State.GAS, true),
    STEAM_CRACKED_PROPANE("steam_cracked_propane", "C3H8", 0xFFBEA540, 775, 493, 350, State.GAS, true),
    STEAM_CRACKED_PROPENE("steam_cracked_propene", "C3H6", 0xFFBEA540, 775, 610, 350, State.GAS, true),
    LIGHTLY_HYDRO_CRACKED_GAS("lightly_hydro_cracked_gas", "", 0xFFA0A0A0, 775, 150, 350, State.GAS, true),
    LIGHTLY_STEAM_CRACKED_GAS("lightly_steam_cracked_gas", "", 0xFFE0E0E0, 775, 150, 350, State.GAS, true),
    SEVERELY_HYDRO_CRACKED_GAS("severely_hydro_cracked_gas", "", 0xFFC8C8C8, 775, 150, 350, State.GAS, true),
    SEVERELY_STEAM_CRACKED_GAS("severely_steam_cracked_gas", "", 0xFFE0E0E0, 775, 150, 350, State.GAS, true),
    URANIUM_HEXAFLUORIDE("uranium_hexafluoride", "F6U238", 0xFF42D126, 295, 510, 350, State.GAS, true),
    ENRICHED_URANIUM_HEXAFLUORIDE("enriched_uranium_hexafluoride", "F6U235", 0xFF4BF52A, 295, 510, 350, State.GAS, true),
    DEPLETED_URANIUM_HEXAFLUORIDE("depleted_uranium_hexafluoride", "F6U238", 0xFF74BA66, 295, 510, 350, State.GAS, true);

    /** 物态（参考GregTech的FluidState，不含方块形态故无"固体"） */
    public enum State {
        LIQUID, GAS, PLASMA
    }

    /** 注册名（流体id，液体桶为 id_bucket） */
    private final String id;
    /** 化学式（纯文本，如 H2SO4，显示时统一转下标） */
    private final String formula;
    /** 流体渲染颜色（ARGB） */
    private final int color;
    /** 温度（开尔文） */
    private final int temperature;
    /** 密度（kg/m^3，MC中水约为1000） */
    private final int density;
    /** 粘度（MC单位，水为1000） */
    private final int viscosity;
    /** 物态 */
    private final State state;
    /** 是否为危险物质（显示红色警示tooltip） */
    private final boolean hazardous;

    ChemicalFluid(String id, String formula, int color, int temperature, int density, int viscosity,
                  State state, boolean hazardous) {
        this.id = id;
        this.formula = formula;
        this.color = color;
        this.temperature = temperature;
        this.density = density;
        this.viscosity = viscosity;
        this.state = state;
        this.hazardous = hazardous;
    }

    public String getId() {
        return id;
    }

    public String getFormula() {
        return formula;
    }

    public int getColor() {
        return color;
    }

    public int getTemperature() {
        return temperature;
    }

    public int getDensity() {
        return density;
    }

    public int getViscosity() {
        return viscosity;
    }

    public State getState() {
        return state;
    }

    /** 是否为液体（只有液体注册桶物品） */
    public boolean isLiquid() {
        return state == State.LIQUID;
    }

    public boolean isHazardous() {
        return hazardous;
    }
}
