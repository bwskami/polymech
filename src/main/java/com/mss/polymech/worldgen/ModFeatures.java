package com.mss.polymech.worldgen;

import com.mss.polymech.Polymech;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/*
 * 世界生成特征注册中心。
 * <p>
 * 注册两个自定义Feature：
 * <ul>
 *   <li>{@link #ORE_VEIN}：矿脉（跨区块密度采样椭球+两级宿主过滤+地表矿苗）</li>
 *   <li>{@link #ROCK_REGION}：区域岩层替换</li>
 * </ul>
 * 必须在Polymech构造函数中调用{@link #register}挂到模组事件总线。
 * </p>
 */
public class ModFeatures {

    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, Polymech.MOD_ID);

    /** 矿脉特征：poly_mech:ore_vein（跨区块密度采样椭球+两级宿主过滤+地表矿苗） */
    public static final DeferredHolder<Feature<?>, OreVeinFeature> ORE_VEIN =
            FEATURES.register("ore_vein", () -> new OreVeinFeature(OreVeinConfiguration.CODEC));

    /** 区域岩层替换特征：poly_mech:rock_region */
    public static final DeferredHolder<Feature<?>, RockRegionFeature> ROCK_REGION =
            FEATURES.register("rock_region", RockRegionFeature::new);

    public static void register(IEventBus eventBus) {
        FEATURES.register(eventBus);
    }
}
