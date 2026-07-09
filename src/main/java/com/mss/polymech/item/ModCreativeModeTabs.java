package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.PipeBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Polymech.MOD_ID);
    
    public static final Supplier<CreativeModeTab> MATERIAL_TAB =
            CREATIVE_MODE_TABS.register("material_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.getMaterialItem(ModItemTypes.INGOT, "steel").get()))
                    .title(Component.translatable("itemGroup.material_tab"))
                    .displayItems((parameters, output) -> {
                        // 数据驱动：遍历所有材料的锭
                        for (String materialName : MaterialRegistry.getMaterialNames()) {
                            var ingotItem = ModItems.getMaterialItem(ModItemTypes.INGOT, materialName);
                            if (ingotItem != null) {
                                output.accept(ingotItem.get());
                            }
                        }
                        
                        // 添加测试物品
                        for (int i = 1; i <= 3; i++) {
                            var testItem = ModItems.getMaterialItem(ModItemTypes.TEST_ITEM, String.valueOf(i));
                            if (testItem != null) {
                                output.accept(testItem.get());
                            }
                        }
                        
                        // 添加粗矿
                        var rawItem = ModItems.getMaterialItem(ModItemTypes.RAW_ORE, "test");
                        if (rawItem != null) {
                            output.accept(rawItem.get());
                        }
                    }).build());
    
    public static final Supplier<CreativeModeTab> BLOCK_TAB =
            CREATIVE_MODE_TABS.register("block_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.COKE_OVEN_BRICK.get()))
                    .title(Component.translatable("itemGroup.block_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.COKE_OVEN_BRICK.get());
                        output.accept(ModBlocks.FLUID_TANK.get());
                    }).build());

    public static final Supplier<CreativeModeTab> PIPE_TAB =
            CREATIVE_MODE_TABS.register("pipe_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.getPipe(PipeMaterial.IRON, PipeBlock.PipeSize.NORMAL).get()))
                    .title(Component.translatable("itemGroup.pipe_tab"))
                    .displayItems((parameters, output) -> {
                        for (var pipe : ModBlocks.PIPE_BLOCKS) {
                            output.accept(pipe.get());
                        }
                    }).build());

    public static final Supplier<CreativeModeTab> TOOL_TAB =
            CREATIVE_MODE_TABS.register("tool_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.WRENCH.get()))
                    .title(Component.translatable("itemGroup.tool_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.WRENCH.get());
                    }).build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
