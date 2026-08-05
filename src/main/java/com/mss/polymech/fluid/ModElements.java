package com.mss.polymech.fluid;

/**
 * 元素周期表全118元素数据（与语言无关的纯数据）。
 * <p>
 * 每个枚举项：注册名（小写英文名，用作流体id前缀）、元素符号、原子序数、相对原子质量。
 * 主要用途：
 * <ul>
 *   <li>为每种元素生成等离子体流体（{@link ModElementFluids}）；</li>
 *   <li>为金属存储块提供原子质量，用于选择normal/heavy贴图（质量阈值标准见{@code ModBlocks}）。</li>
 * </ul>
 * 显示名称通过翻译键解析（元素中文名由datagen语言Provider提供）。
 * </p>
 */
public enum ModElements {

    HYDROGEN("hydrogen", "H", 1, 1.008),
    HELIUM("helium", "He", 2, 4.003),
    LITHIUM("lithium", "Li", 3, 6.94),
    BERYLLIUM("beryllium", "Be", 4, 9.012),
    BORON("boron", "B", 5, 10.81),
    CARBON("carbon", "C", 6, 12.011),
    NITROGEN("nitrogen", "N", 7, 14.007),
    OXYGEN("oxygen", "O", 8, 15.999),
    FLUORINE("fluorine", "F", 9, 18.998),
    NEON("neon", "Ne", 10, 20.180),
    SODIUM("sodium", "Na", 11, 22.990),
    MAGNESIUM("magnesium", "Mg", 12, 24.305),
    ALUMINIUM("aluminium", "Al", 13, 26.982),
    SILICON("silicon", "Si", 14, 28.085),
    PHOSPHORUS("phosphorus", "P", 15, 30.974),
    SULFUR("sulfur", "S", 16, 32.06),
    CHLORINE("chlorine", "Cl", 17, 35.45),
    ARGON("argon", "Ar", 18, 39.948),
    POTASSIUM("potassium", "K", 19, 39.098),
    CALCIUM("calcium", "Ca", 20, 40.078),
    SCANDIUM("scandium", "Sc", 21, 44.956),
    TITANIUM("titanium", "Ti", 22, 47.867),
    VANADIUM("vanadium", "V", 23, 50.942),
    CHROMIUM("chromium", "Cr", 24, 51.996),
    MANGANESE("manganese", "Mn", 25, 54.938),
    IRON("iron", "Fe", 26, 55.845),
    COBALT("cobalt", "Co", 27, 58.933),
    NICKEL("nickel", "Ni", 28, 58.693),
    COPPER("copper", "Cu", 29, 63.546),
    ZINC("zinc", "Zn", 30, 65.38),
    GALLIUM("gallium", "Ga", 31, 69.723),
    GERMANIUM("germanium", "Ge", 32, 72.630),
    ARSENIC("arsenic", "As", 33, 74.922),
    SELENIUM("selenium", "Se", 34, 78.971),
    BROMINE("bromine", "Br", 35, 79.904),
    KRYPTON("krypton", "Kr", 36, 83.798),
    RUBIDIUM("rubidium", "Rb", 37, 85.468),
    STRONTIUM("strontium", "Sr", 38, 87.62),
    YTTRIUM("yttrium", "Y", 39, 88.906),
    ZIRCONIUM("zirconium", "Zr", 40, 91.224),
    NIOBIUM("niobium", "Nb", 41, 92.906),
    MOLYBDENUM("molybdenum", "Mo", 42, 95.95),
    TECHNETIUM("technetium", "Tc", 43, 98.0),
    RUTHENIUM("ruthenium", "Ru", 44, 101.07),
    RHODIUM("rhodium", "Rh", 45, 102.906),
    PALLADIUM("palladium", "Pd", 46, 106.42),
    SILVER("silver", "Ag", 47, 107.868),
    CADMIUM("cadmium", "Cd", 48, 112.414),
    INDIUM("indium", "In", 49, 114.818),
    TIN("tin", "Sn", 50, 118.710),
    ANTIMONY("antimony", "Sb", 51, 121.760),
    TELLURIUM("tellurium", "Te", 52, 127.60),
    IODINE("iodine", "I", 53, 126.904),
    XENON("xenon", "Xe", 54, 131.293),
    CAESIUM("caesium", "Cs", 55, 132.905),
    BARIUM("barium", "Ba", 56, 137.327),
    LANTHANUM("lanthanum", "La", 57, 138.905),
    CERIUM("cerium", "Ce", 58, 140.116),
    PRASEODYMIUM("praseodymium", "Pr", 59, 140.908),
    NEODYMIUM("neodymium", "Nd", 60, 144.242),
    PROMETHIUM("promethium", "Pm", 61, 145.0),
    SAMARIUM("samarium", "Sm", 62, 150.36),
    EUROPIUM("europium", "Eu", 63, 151.964),
    GADOLINIUM("gadolinium", "Gd", 64, 157.25),
    TERBIUM("terbium", "Tb", 65, 158.925),
    DYSPROSIUM("dysprosium", "Dy", 66, 162.500),
    HOLMIUM("holmium", "Ho", 67, 164.930),
    ERBIUM("erbium", "Er", 68, 167.259),
    THULIUM("thulium", "Tm", 69, 168.934),
    YTTERBIUM("ytterbium", "Yb", 70, 173.045),
    LUTETIUM("lutetium", "Lu", 71, 174.967),
    HAFNIUM("hafnium", "Hf", 72, 178.49),
    TANTALUM("tantalum", "Ta", 73, 180.948),
    TUNGSTEN("tungsten", "W", 74, 183.84),
    RHENIUM("rhenium", "Re", 75, 186.207),
    OSMIUM("osmium", "Os", 76, 190.23),
    IRIDIUM("iridium", "Ir", 77, 192.217),
    PLATINUM("platinum", "Pt", 78, 195.084),
    GOLD("gold", "Au", 79, 196.967),
    MERCURY("mercury", "Hg", 80, 200.592),
    THALLIUM("thallium", "Tl", 81, 204.38),
    LEAD("lead", "Pb", 82, 207.2),
    BISMUTH("bismuth", "Bi", 83, 208.980),
    POLONIUM("polonium", "Po", 84, 209.0),
    ASTATINE("astatine", "At", 85, 210.0),
    RADON("radon", "Rn", 86, 222.0),
    FRANCIUM("francium", "Fr", 87, 223.0),
    RADIUM("radium", "Ra", 88, 226.0),
    ACTINIUM("actinium", "Ac", 89, 227.0),
    THORIUM("thorium", "Th", 90, 232.038),
    PROTACTINIUM("protactinium", "Pa", 91, 231.036),
    URANIUM("uranium", "U", 92, 238.029),
    NEPTUNIUM("neptunium", "Np", 93, 237.0),
    PLUTONIUM("plutonium", "Pu", 94, 244.0),
    AMERICIUM("americium", "Am", 95, 243.0),
    CURIUM("curium", "Cm", 96, 247.0),
    BERKELIUM("berkelium", "Bk", 97, 247.0),
    CALIFORNIUM("californium", "Cf", 98, 251.0),
    EINSTEINIUM("einsteinium", "Es", 99, 252.0),
    FERMIUM("fermium", "Fm", 100, 257.0),
    MENDELEVIUM("mendelevium", "Md", 101, 258.0),
    NOBELIUM("nobelium", "No", 102, 259.0),
    LAWRENCIUM("lawrencium", "Lr", 103, 266.0),
    RUTHERFORDIUM("rutherfordium", "Rf", 104, 267.0),
    DUBNIUM("dubnium", "Db", 105, 268.0),
    SEABORGIUM("seaborgium", "Sg", 106, 269.0),
    BOHRIUM("bohrium", "Bh", 107, 270.0),
    HASSIUM("hassium", "Hs", 108, 277.0),
    MEITNERIUM("meitnerium", "Mt", 109, 278.0),
    DARMSTADTIUM("darmstadtium", "Ds", 110, 281.0),
    ROENTGENIUM("roentgenium", "Rg", 111, 282.0),
    COPERNICIUM("copernicium", "Cn", 112, 285.0),
    NIHONIUM("nihonium", "Nh", 113, 286.0),
    FLEROVIUM("flerovium", "Fl", 114, 289.0),
    MOSCOVIUM("moscovium", "Mc", 115, 290.0),
    LIVERMORIUM("livermorium", "Lv", 116, 293.0),
    TENNESSINE("tennessine", "Ts", 117, 294.0),
    OGANESSON("oganesson", "Og", 118, 294.0);

    /** 注册名（小写英文元素名，用作流体id前缀） */
    private final String id;
    /** 元素符号（如Fe） */
    private final String symbol;
    /** 原子序数 */
    private final int atomicNumber;
    /** 相对原子质量 */
    private final double atomicMass;

    ModElements(String id, String symbol, int atomicNumber, double atomicMass) {
        this.id = id;
        this.symbol = symbol;
        this.atomicNumber = atomicNumber;
        this.atomicMass = atomicMass;
    }

    public String getId() {
        return id;
    }

    public String getSymbol() {
        return symbol;
    }

    public int getAtomicNumber() {
        return atomicNumber;
    }

    public double getAtomicMass() {
        return atomicMass;
    }

    /** 是否为放射性元素（Tc/Pm及84号以后，tooltip显示危险警示） */
    public boolean isRadioactive() {
        return atomicNumber == 43 || atomicNumber == 61 || atomicNumber >= 84;
    }

    /**
     * 等离子体渲染颜色：黄金分割色相分布，保证118种等离子体颜色互不相同且饱和醒目。
     */
    public int getPlasmaColor() {
        float hue = (float) ((ordinal() * 0.6180339887) % 1.0);
        return 0xFF000000 | (java.awt.Color.HSBtoRGB(hue, 0.75f, 1.0f) & 0x00FFFFFF);
    }
}
