package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.fluid.ChemicalFluid;
import com.mss.polymech.fluid.ElementFluid;
import com.mss.polymech.fluid.ModChemicalFluids;
import com.mss.polymech.fluid.ModElementFluids;
import com.mss.polymech.fluid.ModFluids;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
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
                        // 焦煤（焦炉产物，非材料前缀物品，手动加入）
                        output.accept(ModItems.COKE.get());

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
     * 通用流体单元标签页，包含四种规格的空单元与所有流体的满装单元。
     * <p>
     * 自动遍历注册表中的每种流体（跳过空流体与流动态非源流体），
     * 为每种流体×每种单元规格生成一个满装单元条目。
     * </p>
     */
    public static final Supplier<CreativeModeTab> FLUID_CELL_TAB =
            CREATIVE_MODE_TABS.register("fluid_cell_tab", () -> CreativeModeTab.builder()
                    .icon(() -> FluidCellItem.getFilledCellStack(Fluids.WATER, FluidCellItem.CAPACITY))
                    .title(Component.translatable("itemGroup.fluid_cell_tab"))
                    .displayItems((parameters, output) -> {
                        // 空单元（四种规格，按容量从小到大）
                        for (var cell : ModItems.ALL_FLUID_CELLS) {
                            output.accept(cell.get());
                        }
                        // 自动为每种流体×每种规格生成满装单元
                        for (var cell : ModItems.ALL_FLUID_CELLS) {
                            int capacity = cell.get().getMaxCapacity();
                            for (Fluid fluid : BuiltInRegistries.FLUID) {
                                // 跳过空流体与流动态（非源）流体，避免重复条目
                                if (fluid == Fluids.EMPTY) continue;
                                if (fluid instanceof FlowingFluid flowing && !flowing.isSource(fluid.defaultFluidState())) continue;
                                output.accept(FluidCellItem.getFilledCellStack(cell.get(), fluid, capacity));
                            }
                        }
                    }).build());
    
    /*
     * 方块标签页，包含所有非管道方块与金属存储块。
     */
    public static final Supplier<CreativeModeTab> BLOCK_TAB =
            CREATIVE_MODE_TABS.register("block_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.COKE_OVEN_BRICK.get()))
                    .title(Component.translatable("itemGroup.block_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.COKE_OVEN_BRICK.get());
                        output.accept(ModBlocks.FLUID_TANK.get());

                        // 金属存储块（按材料名遍历）
                        for (var entry : ModBlocks.MATERIAL_BLOCKS.entrySet()) {
                            output.accept(entry.getValue().get());
                        }

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
     * 流体桶标签页，包含蒸汽桶、化学流体桶与熔融金属桶。
     */
    public static final Supplier<CreativeModeTab> BUCKET_TAB =
            CREATIVE_MODE_TABS.register("bucket_tab", () -> CreativeModeTab.builder()
                    .icon(() -> {
                        // 图标：熔融钢桶
                        for (ElementFluid def : ModElementFluids.getDefinitions()) {
                            if (def.isLiquid() && "steel".equals(def.getMaterialName())) {
                                return new ItemStack(ModElementFluids.getBucket(def));
                            }
                        }
                        return new ItemStack(ModFluids.STEAM_BUCKET.get());
                    })
                    .title(Component.translatable("itemGroup.bucket_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(ModFluids.STEAM_BUCKET.get());

                        // 化学流体桶（仅液体有桶）
                        for (ChemicalFluid chem : ChemicalFluid.values()) {
                            if (chem.isLiquid()) {
                                output.accept(ModChemicalFluids.getBucket(chem));
                            }
                        }

                        // 熔融金属桶（仅液体有桶，等离子体无桶）
                        for (ElementFluid def : ModElementFluids.getDefinitions()) {
                            if (def.isLiquid()) {
                                output.accept(ModElementFluids.getBucket(def));
                            }
                        }
                    }).build());

    /*
     * 管道与物流标签页，包含所有管道方块和传送带。
     */
    public static final Supplier<CreativeModeTab> PIPE_TAB =
            CREATIVE_MODE_TABS.register("pipe_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModBlocks.getPipe(PipeMaterial.IRON, PipeBlock.PipeSize.NORMAL).get()))
                    .title(Component.translatable("itemGroup.pipe_tab"))
                    .displayItems((parameters, output) -> {
                        for (var pipe : ModBlocks.PIPE_BLOCKS) {
                            output.accept(pipe.get());
                        }

                        // 传送带（所有材质）
                        for (var conveyor : ModBlocks.CONVEYOR_BLOCKS) {
                            output.accept(conveyor.get());
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
