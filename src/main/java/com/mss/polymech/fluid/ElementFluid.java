package com.mss.polymech.fluid;

/**
 * 元素类流体定义（与语言无关的数据对象）。
 * <p>
 * 覆盖两类由数据驱动批量生成的流体：
 * <ul>
 *   <li><b>熔融金属</b>：每种含锭材料一条，id形如 {@code molten_steel}，物态LIQUID，有桶；</li>
 *   <li><b>元素等离子体</b>：周期表全118元素各一条，id形如 {@code iron_plasma}，物态PLASMA，无桶。</li>
 * </ul>
 * 显示名称通过翻译键 {@code fluid.poly_mech.<id>} 解析，注册见{@link ModElementFluids}。
 * </p>
 */
public class ElementFluid implements FluidInfo {

    /** 注册名（流体id，液体桶为 id_bucket） */
    private final String id;
    /** 化学式（熔融金属=材料化学式；等离子体=元素符号） */
    private final String formula;
    /** 流体渲染颜色（ARGB） */
    private final int color;
    /** 温度（开尔文，熔融金属≈熔点，等离子体≈10000K） */
    private final int temperature;
    /** 密度（kg/m^3） */
    private final int density;
    /** 粘度（MC单位，水为1000） */
    private final int viscosity;
    /** 物态 */
    private final ChemicalFluid.State state;
    /** 是否为危险物质（放射性金属/等离子体） */
    private final boolean hazardous;
    /** 熔融流体对应的材料名（等离子体为null） */
    private final String materialName;

    public ElementFluid(String id, String formula, int color, int temperature, int density,
                        int viscosity, ChemicalFluid.State state, boolean hazardous, String materialName) {
        this.id = id;
        this.formula = formula;
        this.color = color;
        this.temperature = temperature;
        this.density = density;
        this.viscosity = viscosity;
        this.state = state;
        this.hazardous = hazardous;
        this.materialName = materialName;
    }

    public String getId() {
        return id;
    }

    @Override
    public String getFormula() {
        return formula;
    }

    public int getColor() {
        return color;
    }

    @Override
    public int getTemperature() {
        return temperature;
    }

    public int getDensity() {
        return density;
    }

    public int getViscosity() {
        return viscosity;
    }

    @Override
    public ChemicalFluid.State getState() {
        return state;
    }

    /** 是否为液体（只有液体注册桶物品） */
    public boolean isLiquid() {
        return state == ChemicalFluid.State.LIQUID;
    }

    @Override
    public boolean isHazardous() {
        return hazardous;
    }

    /** 熔融流体对应的材料名；等离子体返回null */
    public String getMaterialName() {
        return materialName;
    }
}
