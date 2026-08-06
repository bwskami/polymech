package com.mss.polymech.entity;

import com.mss.polymech.Polymech;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 模组实体注册中心。
 * <p>
 * 注：传送带物品已改为数据驱动（{@code BeltItem} 存储于
 * {@code ConveyorBlockEntity}），不再使用实体，原 {@code conveyor_item}
 * 实体注册已移除。
 * </p>
 */
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Polymech.MOD_ID);

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}
