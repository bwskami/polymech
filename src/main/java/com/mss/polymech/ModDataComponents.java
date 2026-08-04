package com.mss.polymech;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.fluids.SimpleFluidContent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/*
 * 模组数据组件注册中心。
 * <p>
 * 目前包含通用流体单元使用的流体内容组件（fluid_content），
 * 使用NeoForge的SimpleFluidContent编解码器实现持久化与网络同步。
 * </p>
 */
public class ModDataComponents {
    /** 数据组件延迟注册器 */
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Polymech.MOD_ID);

    /*
     * 流体内容数据组件。
     * <p>
     * 用于通用流体单元存储当前盛装的流体（类型+数量）。
     * </p>
     */
    public static final Supplier<DataComponentType<SimpleFluidContent>> FLUID_CONTENT =
            DATA_COMPONENTS.registerComponentType("fluid_content", builder -> builder
                    .persistent(SimpleFluidContent.CODEC)
                    .networkSynchronized(SimpleFluidContent.STREAM_CODEC));

    /*
     * 容量上限数据组件。
     * <p>
     * 用于通用流体单元存储玩家设置的容量上限（mB）。
     * 未设置（组件不存在）时，单元以其种类的默认最大容量工作。
     * </p>
     */
    public static final Supplier<DataComponentType<Integer>> CAPACITY_LIMIT =
            DATA_COMPONENTS.registerComponentType("capacity_limit", builder -> builder
                    .persistent(com.mojang.serialization.Codec.intRange(0, Integer.MAX_VALUE))
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    /*
     * 向NeoForge事件总线注册数据组件注册器。
     *
     * @param eventBus 模组事件总线
     */
    public static void register(IEventBus eventBus) {
        DATA_COMPONENTS.register(eventBus);
    }
}
