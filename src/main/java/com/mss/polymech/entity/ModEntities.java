package com.mss.polymech.entity;

import com.mss.polymech.Polymech;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 模组实体注册中心。
 */
public class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Polymech.MOD_ID);

    /**
     * 传送带物品实体。
     * MobCategory.MISC 分类，不参与生物生成。
     */
    public static final Supplier<EntityType<ConveyorItemEntity>> CONVEYOR_ITEM =
            ENTITIES.register("conveyor_item", () ->
                    EntityType.Builder.<ConveyorItemEntity>of(ConveyorItemEntity::new, MobCategory.MISC)
                            .sized(0.8F, 0.3F)
                            .clientTrackingRange(10)
                            .updateInterval(1)  // 每 tick 同步，提高平滑度
                            .build("conveyor_item")
            );

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}