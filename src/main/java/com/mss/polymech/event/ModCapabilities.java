package com.mss.polymech.event;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.entity.FluidTankBlockEntity;
import com.mss.polymech.block.entity.ModBlockEntities;
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
    }
}
