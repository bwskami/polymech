package com.mss.polymech.block.entity;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.machine.common.MachineRegistrar;
import com.mss.polymech.machine.production.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Polymech.MOD_ID);

    // ========== 非大型机器方块实体 ==========

    public static final Supplier<BlockEntityType<FluidTankBlockEntity>> FLUID_TANK =
            BLOCK_ENTITIES.register("fluid_tank", () ->
                    BlockEntityType.Builder.of(FluidTankBlockEntity::new,
                            ModBlocks.FLUID_TANK.get()).build(null));

    public static final Supplier<BlockEntityType<ConveyorBlockEntity>> CONVEYOR =
            BLOCK_ENTITIES.register("conveyor", () ->
                    BlockEntityType.Builder.of(ConveyorBlockEntity::new,
                            ModBlocks.CONVEYOR.get()).build(null));

    // ========== Phase 2a：大型机器主方块实体（带泛型，类型安全） ==========

    public static final Supplier<BlockEntityType<FillingUnitBlockEntity>> FILLING_UNIT =
            MachineRegistrar.registerMainBE("filling_unit",
                    FillingUnitBlockEntity::new,
                    ModBlocks.FILLING_UNIT.mainBlock());

    public static final Supplier<BlockEntityType<HorizontalSteamBoilerBlockEntity>> HORIZONTAL_STEAM_BOILER =
            MachineRegistrar.registerMainBE("horizontal_steam_boiler",
                    HorizontalSteamBoilerBlockEntity::new,
                    ModBlocks.HORIZONTAL_STEAM_BOILER.mainBlock());

    public static final Supplier<BlockEntityType<BeehiveCokeOvenBlockEntity>> BEEHIVE_COKE_OVEN =
            MachineRegistrar.registerMainBE("beehive_coke_oven",
                    BeehiveCokeOvenBlockEntity::new,
                    ModBlocks.BEEHIVE_COKE_OVEN.mainBlock());

    public static final Supplier<BlockEntityType<PrimitiveBlastFurnaceBlockEntity>> PRIMITIVE_BLAST_FURNACE =
            MachineRegistrar.registerMainBE("primitive_blast_furnace",
                    PrimitiveBlastFurnaceBlockEntity::new,
                    ModBlocks.PRIMITIVE_BLAST_FURNACE.mainBlock());

    public static final Supplier<BlockEntityType<SteamRollerCrusherBlockEntity>> STEAM_ROLLER_CRUSHER =
            MachineRegistrar.registerMainBE("steam_roller_crusher",
                    SteamRollerCrusherBlockEntity::new,
                    ModBlocks.STEAM_ROLLER_CRUSHER.mainBlock());

    public static final Supplier<BlockEntityType<SteamTurbineGeneratorBlockEntity>> STEAM_TURBINE_GENERATOR =
            MachineRegistrar.registerMainBE("steam_turbine_generator",
                    SteamTurbineGeneratorBlockEntity::new,
                    ModBlocks.STEAM_TURBINE_GENERATOR.mainBlock());

    // ========== Phase 2b：自动注册 side BE + side block + 连线 ==========

    static {
        MachineRegistrar.wireMachine(ModBlocks.FILLING_UNIT, FILLING_UNIT);
        MachineRegistrar.wireMachine(ModBlocks.HORIZONTAL_STEAM_BOILER, HORIZONTAL_STEAM_BOILER);
        MachineRegistrar.wireMachine(ModBlocks.BEEHIVE_COKE_OVEN, BEEHIVE_COKE_OVEN);
        MachineRegistrar.wireMachine(ModBlocks.PRIMITIVE_BLAST_FURNACE, PRIMITIVE_BLAST_FURNACE);
        MachineRegistrar.wireMachine(ModBlocks.STEAM_ROLLER_CRUSHER, STEAM_ROLLER_CRUSHER);
        MachineRegistrar.wireMachine(ModBlocks.STEAM_TURBINE_GENERATOR, STEAM_TURBINE_GENERATOR);
    }

    public static void register(IEventBus eventBus) {
        BLOCK_ENTITIES.register(eventBus);
    }
}
