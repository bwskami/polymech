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
    ARGON("argon", "Ar", 0xFFC090F0, 295, 178, 300, State.GAS, false);

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
