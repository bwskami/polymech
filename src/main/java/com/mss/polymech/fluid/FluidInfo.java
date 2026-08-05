package com.mss.polymech.fluid;

/**
 * 流体元数据通用接口。
 * <p>
 * 所有需要参与tooltip展示（化学式、物态、温度、危险性）的流体定义都实现此接口，
 * 包括：{@link ChemicalFluid}（真实化学物质）、{@link ElementFluid}（熔融金属与元素等离子体）。
 * {@link com.mss.polymech.tooltip.ModTooltipCenter} 仅依赖此接口，与具体定义类解耦。
 * </p>
 */
public interface FluidInfo {

    /** 化学式（纯文本，数字由tooltip中心统一转下标） */
    String getFormula();

    /** 物态（液体/气体/等离子体） */
    ChemicalFluid.State getState();

    /** 温度（开尔文） */
    int getTemperature();

    /** 是否为危险物质（tooltip显示红色警示） */
    boolean isHazardous();
}
