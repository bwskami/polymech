package com.mss.polymech.block.entity;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.MachineRegistry;
import com.mss.polymech.machine.production.FillingUnitBlockEntity;
import com.mss.polymech.machine.production.FillingUnitSideBlockEntity;
import com.mss.polymech.machine.production.HorizontalSteamBoilerBlockEntity;
import com.mss.polymech.machine.production.HorizontalSteamBoilerSideBlockEntity;
import com.mss.polymech.machine.production.BeehiveCokeOvenBlockEntity;
import com.mss.polymech.machine.production.BeehiveCokeOvenSideBlockEntity;
import com.mss.polymech.machine.production.PrimitiveBlastFurnaceBlockEntity;
import com.mss.polymech.machine.production.PrimitiveBlastFurnaceSideBlockEntity;
import com.mss.polymech.machine.production.SteamRollerCrusherBlockEntity;
import com.mss.polymech.machine.production.SteamRollerCrusherSideBlockEntity;
import com.mss.polymech.machine.production.SteamTurbineGeneratorBlockEntity;
import com.mss.polymech.machine.production.SteamTurbineGeneratorSideBlockEntity;
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


    public static final Supplier<BlockEntityType<FillingUnitBlockEntity>> FILLING_UNIT =
            BLOCK_ENTITIES.register("filling_unit", () ->
                    BlockEntityType.Builder.of(FillingUnitBlockEntity::new,
                            ModBlocks.FILLING_UNIT.get()).build(null));

    public static final Supplier<BlockEntityType<FillingUnitSideBlockEntity>> FILLING_UNIT_SIDE =
            BLOCK_ENTITIES.register("filling_unit_side", () ->
                    BlockEntityType.Builder.of(FillingUnitSideBlockEntity::new,
                            ModBlocks.FILLING_UNIT_SIDE.get()).build(null));

    public static final Supplier<BlockEntityType<HorizontalSteamBoilerBlockEntity>> HORIZONTAL_STEAM_BOILER =
            BLOCK_ENTITIES.register("horizontal_steam_boiler", () ->
                    BlockEntityType.Builder.of(HorizontalSteamBoilerBlockEntity::new,
                            ModBlocks.HORIZONTAL_STEAM_BOILER.get()).build(null));

    public static final Supplier<BlockEntityType<HorizontalSteamBoilerSideBlockEntity>> HORIZONTAL_STEAM_BOILER_SIDE =
            BLOCK_ENTITIES.register("horizontal_steam_boiler_side", () ->
                    BlockEntityType.Builder.of(HorizontalSteamBoilerSideBlockEntity::new,
                            ModBlocks.HORIZONTAL_STEAM_BOILER_SIDE.get()).build(null));

    public static final Supplier<BlockEntityType<BeehiveCokeOvenBlockEntity>> BEEHIVE_COKE_OVEN =
            BLOCK_ENTITIES.register("beehive_coke_oven", () ->
                    BlockEntityType.Builder.of(BeehiveCokeOvenBlockEntity::new,
                            ModBlocks.BEEHIVE_COKE_OVEN.get()).build(null));

    public static final Supplier<BlockEntityType<BeehiveCokeOvenSideBlockEntity>> BEEHIVE_COKE_OVEN_SIDE =
            BLOCK_ENTITIES.register("beehive_coke_oven_side", () ->
                    BlockEntityType.Builder.of(BeehiveCokeOvenSideBlockEntity::new,
                            ModBlocks.BEEHIVE_COKE_OVEN_SIDE.get()).build(null));

    public static final Supplier<BlockEntityType<PrimitiveBlastFurnaceBlockEntity>> PRIMITIVE_BLAST_FURNACE =
            BLOCK_ENTITIES.register("primitive_blast_furnace", () ->
                    BlockEntityType.Builder.of(PrimitiveBlastFurnaceBlockEntity::new,
                            ModBlocks.PRIMITIVE_BLAST_FURNACE.get()).build(null));

    public static final Supplier<BlockEntityType<PrimitiveBlastFurnaceSideBlockEntity>> PRIMITIVE_BLAST_FURNACE_SIDE =
            BLOCK_ENTITIES.register("primitive_blast_furnace_side", () ->
                    BlockEntityType.Builder.of(PrimitiveBlastFurnaceSideBlockEntity::new,
                            ModBlocks.PRIMITIVE_BLAST_FURNACE_SIDE.get()).build(null));

    public static final Supplier<BlockEntityType<SteamRollerCrusherBlockEntity>> STEAM_ROLLER_CRUSHER =
            BLOCK_ENTITIES.register("steam_roller_crusher", () ->
                    BlockEntityType.Builder.of(SteamRollerCrusherBlockEntity::new,
                            ModBlocks.STEAM_ROLLER_CRUSHER.get()).build(null));

    public static final Supplier<BlockEntityType<SteamRollerCrusherSideBlockEntity>> STEAM_ROLLER_CRUSHER_SIDE =
            BLOCK_ENTITIES.register("steam_roller_crusher_side", () ->
                    BlockEntityType.Builder.of(SteamRollerCrusherSideBlockEntity::new,
                            ModBlocks.STEAM_ROLLER_CRUSHER_SIDE.get()).build(null));

    public static final Supplier<BlockEntityType<SteamTurbineGeneratorBlockEntity>> STEAM_TURBINE_GENERATOR =
            BLOCK_ENTITIES.register("steam_turbine_generator", () ->
                    BlockEntityType.Builder.of(SteamTurbineGeneratorBlockEntity::new,
                            ModBlocks.STEAM_TURBINE_GENERATOR.get()).build(null));

    public static final Supplier<BlockEntityType<SteamTurbineGeneratorSideBlockEntity>> STEAM_TURBINE_GENERATOR_SIDE =
            BLOCK_ENTITIES.register("steam_turbine_generator_side", () ->
                    BlockEntityType.Builder.of(SteamTurbineGeneratorSideBlockEntity::new,
                            ModBlocks.STEAM_TURBINE_GENERATOR_SIDE.get()).build(null));

    // ========== 回填MachineRegistry中的方块实体类型 ==========

    static {
        MachineRegistry.MachineEntry entry;
        entry = MachineRegistry.getEntry("filling_unit");
        if (entry != null) entry.setBlockEntities(FILLING_UNIT, FILLING_UNIT_SIDE);
        entry = MachineRegistry.getEntry("horizontal_steam_boiler");
        if (entry != null) entry.setBlockEntities(HORIZONTAL_STEAM_BOILER, HORIZONTAL_STEAM_BOILER_SIDE);
        entry = MachineRegistry.getEntry("beehive_coke_oven");
        if (entry != null) entry.setBlockEntities(BEEHIVE_COKE_OVEN, BEEHIVE_COKE_OVEN_SIDE);
        entry = MachineRegistry.getEntry("primitive_blast_furnace");
        if (entry != null) entry.setBlockEntities(PRIMITIVE_BLAST_FURNACE, PRIMITIVE_BLAST_FURNACE_SIDE);
        entry = MachineRegistry.getEntry("steam_roller_crusher");
        if (entry != null) entry.setBlockEntities(STEAM_ROLLER_CRUSHER, STEAM_ROLLER_CRUSHER_SIDE);
        entry = MachineRegistry.getEntry("steam_turbine_generator");
        if (entry != null) entry.setBlockEntities(STEAM_TURBINE_GENERATOR, STEAM_TURBINE_GENERATOR_SIDE);
    }

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
