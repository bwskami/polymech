package com.mss.polymech.event;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.entity.FluidTankBlockEntity;
import com.mss.polymech.block.entity.ModBlockEntities;
import com.mss.polymech.machine.production.BatteryBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@EventBusSubscriber(modid = Polymech.MOD_ID)
public class ModCapabilities {
    @SubscribeEvent
    static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                Capabilities.FluidHandler.BLOCK,
                ModBlockEntities.FLUID_TANK.get(),
                (blockEntity, side) -> blockEntity.getFluidHandler()
        );
        event.registerBlockEntity(
                Capabilities.ItemHandler.BLOCK,
                ModBlockEntities.FLUID_TANK.get(),
                (blockEntity, side) -> blockEntity.getBucketHandler()
        );
        // 蓄电池 IEnergyStorage 能力（尊重面配置）
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.BATTERY.get(),
                (BatteryBlockEntity blockEntity, @org.jetbrains.annotations.Nullable net.minecraft.core.Direction side) ->
                        blockEntity.getEnergyStorage(side)
        );
        event.registerBlockEntity(
                Capabilities.EnergyStorage.BLOCK,
                ModBlockEntities.CREATIVE_BATTERY.get(),
                (BatteryBlockEntity blockEntity, @org.jetbrains.annotations.Nullable net.minecraft.core.Direction side) ->
                        blockEntity.getEnergyStorage(side)
        );
    }
}
