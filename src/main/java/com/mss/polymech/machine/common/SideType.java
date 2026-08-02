package com.mss.polymech.machine.common;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

/**
 * 侧面方块的功能类型。
 * <p>
 * 用于区分大型机器侧面方块的不同用途——
 * 普通占位、流体输入/输出、物品输入/输出。
 * </p>
 */
public enum SideType implements StringRepresentable {
    /** 普通占位方块，仅用于填充多方块结构 */
    NORMAL("normal"),
    /** 流体输入仓，将流体从外部输入到机器内部 */
    FLUID_INPUT("fluid_input"),
    /** 流体输出仓，将流体从机器内部输出到外部 */
    FLUID_OUTPUT("fluid_output"),
    /** 物品输入仓，将物品从外部输入到机器内部 */
    ITEM_INPUT("item_input"),
    /** 物品输出仓，将物品从机器内部输出到外部 */
    ITEM_OUTPUT("item_output");

    private final String name;

    SideType(String name) {
        this.name = name;
    }

    @Override
    @NotNull
    public String getSerializedName() {
        return name;
    }

    public boolean isFluid() {
        return this == FLUID_INPUT || this == FLUID_OUTPUT;
    }

    public boolean isItem() {
        return this == ITEM_INPUT || this == ITEM_OUTPUT;
    }

    public boolean isInput() {
        return this == FLUID_INPUT || this == ITEM_INPUT;
    }

    public boolean isOutput() {
        return this == FLUID_OUTPUT || this == ITEM_OUTPUT;
    }
}
