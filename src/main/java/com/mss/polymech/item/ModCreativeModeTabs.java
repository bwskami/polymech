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
import com.mss.polymech.fluid.ModFluidBuckets;
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

                        // 粗矿物（真实矿物系统，非材料前缀物品，手动加入）
                        for (var rawMineral : ModItems.ALL_RAW_MINERAL_ITEMS) {
                            output.accept(rawMineral.get());
                        }

                        // 矿物加工中间产物：粉碎矿/洗净矿（数据驱动注册）
                        for (var mineralProduct : ModItems.ALL_MINERAL_ITEMS) {
                            output.accept(mineralProduct.get());
                        }
                    }).build());

    /*
     * 矿物标签页：真实矿物系统的专属栏目。
     * <p>
     * 包含：全部矿物×岩种矿石方块、粗矿、粉碎矿、洗净矿、宝石。
     * 与材料标签页分离——这里聚焦"矿物"（采掘/选矿），材料标签页聚焦"材料"（冶炼/加工）。
     * </p>
     */
    public static final Supplier<CreativeModeTab> MINERAL_TAB =
            CREATIVE_MODE_TABS.register("mineral_tab", () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(ModItems.getRawMineral("cassiterite").get()))
                    .title(Component.translatable("itemGroup.mineral_tab"))
                    .displayItems((parameters, output) -> {
                        // 矿石方块：全部矿物×全部岩种变体（石头/深板岩/21种群峦岩种）
                        for (var oreSet : ModBlocks.MINERAL_ORES.values()) {
                            for (var oreBlock : oreSet.all()) {
                                output.accept(oreBlock.get());
                            }
                        }
                        // 粗矿（采掘直接产物）
                        for (var rawMineral : ModItems.ALL_RAW_MINERAL_ITEMS) {
                            output.accept(rawMineral.get());
                        }
                        // 粉碎矿/洗净矿（选矿中间产物）
                        for (var mineralProduct : ModItems.ALL_MINERAL_ITEMS) {
                            output.accept(mineralProduct.get());
                        }
                        // 宝石/晶体（宝石矿产物）
                        for (String gem : com.mss.polymech.api.material.GemMaterials.getGems()) {
                            var gemItem = ModItems.getMaterialItem(ModItemTypes.GEM, gem);
                            if (gemItem != null) output.accept(gemItem.get());
                        }
                        // 矿物碎块（地表/地下指示矿）
                        for (var surfaceRock : ModBlocks.SURFACE_ROCK_LIST) {
                            output.accept(surfaceRock.get());
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

                        // 矿石方块：每种矿物的全部岩种变体（石头/深板岩/21种群峦岩种）
                        for (var oreSet : ModBlocks.MINERAL_ORES.values()) {
                            for (var oreBlock : oreSet.all()) {
                                output.accept(oreBlock.get());
                            }
                        }

                        // 区域岩石（岩层系统的五种岩种）
                        for (var rock : ModBlocks.ROCKS.values()) {
                            output.accept(rock.get());
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
                        return new ItemStack(ModFluids.OIL_BUCKET.get());
                    })
                    .title(Component.translatable("itemGroup.bucket_tab"))
                    .displayItems((parameters, output) -> {
                        // 所有流体桶：统一从 ModFluidBuckets 数据驱动获取
                        for (ModFluidBuckets.Entry bucket : ModFluidBuckets.getAll()) {
                            output.accept(bucket.item());
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

                        // 电网方块（连接器/混凝土电杆）
                        output.accept(ModBlocks.CONNECTOR.get());
                        output.accept(ModBlocks.CONCRETE_POLE.get());

                        // 电网线轴（铜/铁/银金合金）+ 空线轴
                        for (var spool : ModItems.ALL_WIRE_SPOOLS) {
                            output.accept(spool.get());
                        }
                        output.accept(ModItems.EMPTY_SPOOL.get());
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
                        output.accept(ModItems.NETWORK_TOOL.get());
                        output.accept(ModItems.WIRE_CUTTER.get());
                        output.accept(ModItems.CLAMP_METER.get());
                        output.accept(ModItems.PROSPECTOR.get());
                        
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
