package com.mss.polymech.menu;

import com.mss.polymech.Polymech;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, Polymech.MOD_ID);

    public static final Supplier<MenuType<FluidTankMenu>> FLUID_TANK_MENU =
            MENUS.register("fluid_tank_menu", () ->
                    IMenuTypeExtension.create((windowId, inv, data) -> {
                        BlockPos pos = data.readBlockPos();
                        var level = inv.player.level();
                        if (level.getBlockEntity(pos) instanceof com.mss.polymech.block.entity.FluidTankBlockEntity tank) {
                            return new FluidTankMenu(windowId, inv, tank);
                        }
                        return null;
                    }));

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
