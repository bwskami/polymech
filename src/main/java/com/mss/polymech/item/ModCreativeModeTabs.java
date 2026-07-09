package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ItemTagPrefix;
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

/*
 * 创造模式标签页注册中心。
 * <p>
 * 该类负责注册和管理所有自定义创造模式标签页。
 * 支持数据驱动的自动物品分类，根据{@link ItemTagPrefix#getCreativeTabTarget()}
 * 将物品自动分配到对应的标签页。
 * </p>
 * 
 * <h2>标签页分类规则：</h2>
 * <ul>
 *   <li><b>MATERIAL</b>: 材料类物品（锭、粉、宝石、粗矿等）</li>
 *   <li><b>BLOCK</b>: 方块类物品（存储方块等）</li>
 *   <li><b>TOOL</b>: 工具类物品（扳手、工具等）</li>
 *   <li><b>NONE</b>: 不添加到任何标签页</li>
 * </ul>
 * 
 * @see ItemTagPrefix.CreativeTabTarget
 */
public class ModCreativeModeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Polymech.MOD_ID);
    
    /*
     * 材料标签页，包含所有材料类物品。
     * <p>
     * 自动收集所有CreativeTabTarget为MATERIAL的物品。
     * </p>
     */
    public static final Supplier<CreativeModeTab> MATERIAL_TAB =
            CREATIVE_MODE_TABS.register("material_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.getMaterialItem(ModItemTypes.INGOT, "steel").get()))
                    .title(Component.translatable("itemGroup.material_tab"))
                    .displayItems((parameters, output) -> {
                        // 自动收集所有目标为MATERIAL的物品
                        for (ItemTagPrefix prefix : ModItemTypes.getAllPrefixes()) {
                            if (prefix.getCreativeTabTarget() == ItemTagPrefix.CreativeTabTarget.MATERIAL) {
                                for (String materialName : MaterialRegistry.getMaterialNames()) {
                                    var item = ModItems.getMaterialItem(prefix, materialName);
                                    if (item != null) {
                                        output.accept(item.get());
                                    }
                                }
                            }
                        }
                    }).build());
    
    /*
     * 方块标签页，包含所有非管道方块。
     */
    public static final Supplier<CreativeModeTab> BLOCK_TAB =
            CREATIVE_MODE_TABS.register("block_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.COKE_OVEN_BRICK.get()))
                    .title(Component.translatable("itemGroup.block_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.COKE_OVEN_BRICK.get());
                        output.accept(ModBlocks.FLUID_TANK.get());
                        
                        // 如果有目标为BLOCK的材料物品，也添加到这里
                        for (ItemTagPrefix prefix : ModItemTypes.getAllPrefixes()) {
                            if (prefix.getCreativeTabTarget() == ItemTagPrefix.CreativeTabTarget.BLOCK) {
                                for (String materialName : MaterialRegistry.getMaterialNames()) {
                                    var item = ModItems.getMaterialItem(prefix, materialName);
                                    if (item != null) {
                                        output.accept(item.get());
                                    }
                                }
                            }
                        }
                    }).build());

    /*
     * 管道标签页，包含所有管道方块。
     */
    public static final Supplier<CreativeModeTab> PIPE_TAB =
            CREATIVE_MODE_TABS.register("pipe_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.getPipe(PipeMaterial.IRON, PipeBlock.PipeSize.NORMAL).get()))
                    .title(Component.translatable("itemGroup.pipe_tab"))
                    .displayItems((parameters, output) -> {
                        for (var pipe : ModBlocks.PIPE_BLOCKS) {
                            output.accept(pipe.get());
                        }
                    }).build());

    /*
     * 工具标签页，包含所有工具类物品。
     */
    public static final Supplier<CreativeModeTab> TOOL_TAB =
            CREATIVE_MODE_TABS.register("tool_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.WRENCH.get()))
                    .title(Component.translatable("itemGroup.tool_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.WRENCH.get());
                        
                        // 如果有目标为TOOL的材料物品，也添加到这里
                        for (ItemTagPrefix prefix : ModItemTypes.getAllPrefixes()) {
                            if (prefix.getCreativeTabTarget() == ItemTagPrefix.CreativeTabTarget.TOOL) {
                                for (String materialName : MaterialRegistry.getMaterialNames()) {
                                    var item = ModItems.getMaterialItem(prefix, materialName);
                                    if (item != null) {
                                        output.accept(item.get());
                                    }
                                }
                            }
                        }
                    }).build());

    /*
     * 向NeoForge事件总线注册创造模式标签页注册器。
     * 
     * @param eventBus 模组事件总线
     */
    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
