package com.mss.polymech.fluid;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 统一流体桶注册表：聚合所有数据驱动流体桶。
 * <p>
 * 新增化学流体/熔融金属只需在对应 {@code ChemicalFluid}/{@code ElementFluid}
 * 数据列表中加一条，这里会自动被聚合，不需要为桶模型、颜色注册、创造标签页
 * 再写任何手动代码。
 * </p>
 */
public final class ModFluidBuckets {

    /** 一条桶注册：桶物品 + 对应源流体 + tooltip元数据 */
    public record Entry(Item item, Fluid source, FluidInfo info) {}

    private static List<Entry> all;

    private ModFluidBuckets() {
    }

    /** 所有流体桶条目（惰性构建，注册表冻结后调用） */
    public static List<Entry> getAll() {
        if (all == null) {
            List<Entry> list = new ArrayList<>();

            // ModFluids 中的真/假流体桶（蒸汽、石油）
            for (var entry : ModFluids.FLUID_BUCKET_ITEMS.getEntries()) {
                String path = entry.getId().getPath();
                Fluid fluid = switch (path) {
                    case "steam_bucket" -> ModFluids.STEAM_SOURCE.get();
                    case "petroleum_bucket" -> ModFluids.OIL_SOURCE.get();
                    default -> Fluids.EMPTY;
                };
                if (fluid != Fluids.EMPTY) {
                    list.add(new Entry(entry.get(), fluid,
                        "steam_bucket".equals(path) ? ModFluids.STEAM_INFO : ModFluids.PETROLEUM_INFO));
                }
            }

            // 化学流体桶（所有物态：液体/气体/等离子体）
            for (ChemicalFluid chem : ChemicalFluid.values()) {
                Item bucket = ModChemicalFluids.getBucket(chem);
                if (bucket != Items.AIR) {
                    list.add(new Entry(bucket, ModChemicalFluids.getSource(chem), chem));
                }
            }

            // 元素流体桶（熔融金属 + 等离子体）
            for (ElementFluid def : ModElementFluids.getDefinitions()) {
                Item bucket = ModElementFluids.getBucket(def);
                if (bucket != Items.AIR) {
                    list.add(new Entry(bucket, ModElementFluids.getSource(def), def));
                }
            }

            all = List.copyOf(list);
        }
        return all;
    }

    /** 按源流体反查 FluidInfo（用于桶/流体单元 tooltip 数据驱动展示） */
    @Nullable
    public static FluidInfo getInfo(Fluid fluid) {
        for (Entry entry : getAll()) {
            if (entry.source() == fluid) return entry.info();
        }
        return null;
    }
}
