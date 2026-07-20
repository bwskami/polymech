package com.mss.polymech.block.entity;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.entity.large.HorizontalSteamBoilerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Polymech.MOD_ID);

    public static final Supplier<BlockEntityType<FluidTankBlockEntity>> FLUID_TANK =
            BLOCK_ENTITIES.register("fluid_tank", () ->
                    BlockEntityType.Builder.of(FluidTankBlockEntity::new,
                            ModBlocks.FLUID_TANK.get()).build(null));

    public static final Supplier<BlockEntityType<ConveyorBlockEntity>> CONVEYOR =
            BLOCK_ENTITIES.register("conveyor", () ->
                    BlockEntityType.Builder.of(ConveyorBlockEntity::new,
                            ModBlocks.CONVEYOR.get()).build(null));

    public static final Supplier<BlockEntityType<HorizontalSteamBoilerBlockEntity>> HORIZONTAL_STEAM_BOILER =
            BLOCK_ENTITIES.register("horizontal_steam_boiler", () ->
                    BlockEntityType.Builder.of(HorizontalSteamBoilerBlockEntity::new,
                            ModBlocks.HORIZONTAL_STEAM_BOILER.get()).build(null));

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
