package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Scanner;
import java.util.function.Supplier;

public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Polymech.MOD_ID);
    public static final Supplier<CreativeModeTab> MATERIAL_TAB =
            CREATIVE_MODE_TABS.register("material_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.STEEL_INGOT.get()))
                    .title(Component.translatable("itemGroup.material_tab"))
                    .displayItems((parameters, output) -> {
                        //这一行以下写锭（单质）
                        output.accept(ModItems.TEST_INGOT);
                        output.accept(ModItems.STEEL_INGOT);
                        output.accept(ModItems.ALUMINIUM_INGOT);
                        output.accept(ModItems.NICKEL_INGOT);
                        output.accept(ModItems.TIN_INGOT);
                        output.accept(ModItems.ZINC_INGOT);
                        //这一行以下写锭（合金）
                        output.accept(ModItems.BRASS_INGOT);
                        output.accept(ModItems.BRONZE_INGOT);
                        output.accept(ModItems.IVAR_INGOT);
                        output.accept(ModItems.CUPRONICKEL_INGOT);
                        output.accept(ModItems.STAINLESS_STEEL_INGOT);

                    }).build());
    public static final Supplier<CreativeModeTab> BLOCK_TAB =
            CREATIVE_MODE_TABS.register("block_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.COKE_OVEN_BRICK.get()))
                    .title(Component.translatable("itemGroup.block_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.COKE_OVEN_BRICK.get());
                        output.accept(ModBlocks.FLUID_TANK.get());
                    }).build());
    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
