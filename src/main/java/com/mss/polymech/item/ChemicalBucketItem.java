package com.mss.polymech.item;

import com.mss.polymech.fluid.ChemicalFluid;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

/**
 * 无方块流体桶（化学物质液体与熔融金属通用）。
 * <p>
 * 对应流体无方块形态（不可在世界中放置），因此右键使用桶不会倒出液体。
 * 化学式、物态、温度、危险警示等tooltip统一由
 * {@link com.mss.polymech.tooltip.ModTooltipCenter} 处理，此处不重复追加。
 * </p>
 */
public class ChemicalBucketItem extends BucketItem {

    @Nullable
    private final ChemicalFluid chemical;

    public ChemicalBucketItem(Fluid fluid, Properties properties, ChemicalFluid chemical) {
        super(fluid, properties);
        this.chemical = chemical;
    }

    /** 无化学物质定义的通用构造（熔融金属桶使用） */
    public ChemicalBucketItem(Fluid fluid, Properties properties) {
        this(fluid, properties, null);
    }

    @Nullable
    public ChemicalFluid getChemical() {
        return chemical;
    }
}
