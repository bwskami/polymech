package com.mss.polymech.block;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.entity.FluidTankBlock;
import com.mss.polymech.item.*;
import com.mss.polymech.machine.production.FillingUnitBlock;
import com.mss.polymech.machine.production.FillingUnitSideBlock;

import com.mss.polymech.machine.production.HorizontalSteamBoilerBlock;
import com.mss.polymech.machine.production.HorizontalSteamBoilerSideBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.*;
import java.util.function.Supplier;

/*
 * 模组方块注册中心，负责所有方块及其对应物品的注册。
 * <p>
 * 该类实现了两种方块注册模式：
 * <ol>
 *   <li><b>手动注册</b>：用于普通方块（如焦炉砖、测试矿石）</li>
 *   <li><b>数据驱动批量注册</b>：用于管道方块（按材料和尺寸组合）</li>
 * </ol>
 * </p>
 * 
 * <h2>管道方块数据结构：</h2>
 * <pre>{@code
 * PIPE_TABLE: Map<PipeMaterial, Map<PipeSize, DeferredBlock<PipeBlock>>>
 * 
 * // 访问示例：
 * DeferredBlock<PipeBlock> ironNormalPipe = ModBlocks.getPipe(PipeMaterial.IRON, PipeSize.NORMAL);
 * }</pre>
 * 
 * <h2>自动注册方块物品：</h2>
 * <p>
 * 所有注册的方块都会自动创建对应的BlockItem，无需手动注册。
 * 管道方块使用特殊的PipeItem以支持右键放置预览功能。
 * </p>
 * 
 * @see PipeBlock
 * @see PipeMaterial
 */
public class ModBlocks {
    /** NeoForge延迟方块注册器 */
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Polymech.MOD_ID);

    // ========== 非管道方块 ==========
    
    /* 焦炉砖方块，用于构建焦炉结构 */
    public static final DeferredBlock<Block> COKE_OVEN_BRICK =
            registerBlocks("coke_oven_brick", () -> new Block(Block.Properties.ofFullCopy(Blocks.STONE)
                    .requiresCorrectToolForDrops()));
    
    /* 流体储罐方块，用于存储流体 */
    public static final DeferredBlock<FluidTankBlock> FLUID_TANK =
            registerBlocks("fluid_tank", () -> new FluidTankBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    /* 传送带方块，用于移动物品 */
    public static final DeferredBlock<ConveyorBlock> CONVEYOR =
            registerConveyor("conveyor", () -> new ConveyorBlock(Block.Properties.of()
                    .strength(2.0F, 3.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));


    /* 填充装置 - Endfield风格大型机器 */
    public static final DeferredBlock<FillingUnitBlock> FILLING_UNIT =
            registerMachine("filling_unit",
                    () -> new FillingUnitBlock(Block.Properties.of()
                            .strength(3.5F, 4.8F)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
                            .dynamicShape()),
                    FillingUnitItem::new);

    /* 填充装置 - 侧面方块 */
    public static final DeferredBlock<FillingUnitSideBlock> FILLING_UNIT_SIDE =
            BLOCKS.register("filling_unit_side", () -> new FillingUnitSideBlock(Block.Properties.of()
                    .strength(3.5F, 4.8F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .dynamicShape()));

    /* 水平蒸汽锅炉 - Endfield风格大型机器 */
    public static final DeferredBlock<HorizontalSteamBoilerBlock> HORIZONTAL_STEAM_BOILER =
            registerMachine("horizontal_steam_boiler",
                    () -> new HorizontalSteamBoilerBlock(Block.Properties.of()
                            .strength(3.5F, 4.8F)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()
                            .dynamicShape()),
                    HorizontalSteamBoilerItem::new);

    /* 水平蒸汽锅炉 - 侧面方块 */
    public static final DeferredBlock<HorizontalSteamBoilerSideBlock> HORIZONTAL_STEAM_BOILER_SIDE =
            BLOCKS.register("horizontal_steam_boiler_side", () -> new HorizontalSteamBoilerSideBlock(Block.Properties.of()
                    .strength(3.5F, 4.8F)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()
                    .dynamicShape()));

    // ========== 管道方块：数据驱动批量注册 ==========
    
    /* 内部管道查找表（构建期间使用） */
    private static final Map<PipeMaterial, Map<PipeBlock.PipeSize, DeferredBlock<PipeBlock>>> PIPE_TABLE_INTERNAL = new LinkedHashMap<>();
    
    /*
     * 管道方块查找表。
     * <p>
     * 结构：Map&lt;管道材料, Map&lt;管道尺寸, 方块引用&gt;&gt;
     * 用于快速查找特定材料和尺寸的管道方块。
     * </p>
     */
    public static final Map<PipeMaterial, Map<PipeBlock.PipeSize, DeferredBlock<PipeBlock>>> PIPE_TABLE;
    
    /*
     * 所有管道方块的扁平列表。
     * <p>
     * 包含所有通过数据驱动生成的管道方块，便于批量操作（如战利品表生成）。
     * </p>
     */
    public static final List<DeferredBlock<PipeBlock>> PIPE_BLOCKS;

    static {
        // 数据驱动批量注册流程
        List<DeferredBlock<PipeBlock>> allPipes = new ArrayList<>();
        
        // 遍历所有管道材料
        for (PipeMaterial material : PipeMaterial.values()) {
            Map<PipeBlock.PipeSize, DeferredBlock<PipeBlock>> sizeMap = new LinkedHashMap<>();
            
            // 遍历所有管道尺寸
            for (PipeBlock.PipeSize size : PipeBlock.PipeSize.values()) {
                // 生成注册名称（如：pipe, bronze_pipe, stainless_steel_small_pipe）
                String name = size.getRegistryName(material);
                
                // 注册管道方块
                DeferredBlock<PipeBlock> pipe = registerPipe(name,
                        () -> new PipeBlock(Block.Properties.of()
                                .strength(material.getStrength(), material.getResistance())
                                .sound(material.getSoundType())
                                .requiresCorrectToolForDrops()
                                .noOcclusion(),
                                size));
                
                sizeMap.put(size, pipe);
                allPipes.add(pipe);
            }
            PIPE_TABLE_INTERNAL.put(material, Collections.unmodifiableMap(sizeMap));
        }
        
        // 创建不可修改的公共视图
        PIPE_TABLE = Collections.unmodifiableMap(PIPE_TABLE_INTERNAL);
        PIPE_BLOCKS = Collections.unmodifiableList(allPipes);
    }

    /*
     * 获取指定材料和尺寸的管道方块引用。
     * 
     * @param material 管道材料
     * @param size 管道尺寸
     * @return 对应的管道方块引用
     * 
     * @throws NullPointerException 如果material或size为null
     */
    public static DeferredBlock<PipeBlock> getPipe(PipeMaterial material, PipeBlock.PipeSize size) {
        return PIPE_TABLE.get(material).get(size);
    }

    // ========== 注册工具方法 ==========
    
    /*
     * 为方块注册对应的物品。
     * <p>
     * 使用普通的BlockItem，适用于非管道方块。
     * </p>
     * 
     * @param name 物品注册名称
     * @param block 方块引用
     */
    private static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    /*
     * 注册普通方块及其物品。
     * 
     * @param name 方块注册名称
     * @param block 方块供应商
     * @return 注册的方块引用
     */
    private static <T extends Block> DeferredBlock<T> registerBlocks(String name, Supplier<T> block) {
        DeferredBlock<T> blocks = BLOCKS.register(name, block);
        registerBlockItems(name, blocks);
        return blocks;
    }

    private static <T extends Block, I extends BlockItem> DeferredBlock<T> registerMachine(
            String name, Supplier<T> block, java.util.function.BiFunction<T, Item.Properties, I> itemFactory) {
        DeferredBlock<T> blocks = BLOCKS.register(name, block);
        ModItems.ITEMS.register(name, () -> itemFactory.apply(blocks.get(), new Item.Properties()));
        return blocks;
    }

    /*
     * 注册管道方块及其特殊物品。
     * <p>
     * 管道使用PipeItem而非普通BlockItem，以支持右键放置预览功能。
     * </p>
     * 
     * @param name 方块注册名称
     * @param block 管道方块供应商
     * @return 注册的管道方块引用
     */
    private static DeferredBlock<PipeBlock> registerPipe(String name, Supplier<PipeBlock> block) {
        DeferredBlock<PipeBlock> pipe = BLOCKS.register(name, block);
        ModItems.ITEMS.register(name, () -> new PipeItem(pipe.get(), new Item.Properties()));
        return pipe;
    }

    /*
     * 注册传送带方块及其特殊物品。
     * <p>
     * 传送带使用ConveyorItem而非普通BlockItem，以支持右键连续铺设功能。
     * </p>
     *
     * @param name 方块注册名称
     * @param block 传送带方块供应商
     * @return 注册的传送带方块引用
     */
    private static DeferredBlock<ConveyorBlock> registerConveyor(String name, Supplier<ConveyorBlock> block) {
        DeferredBlock<ConveyorBlock> conveyor = BLOCKS.register(name, block);
        ModItems.ITEMS.register(name, () -> new ConveyorItem(conveyor.get(), new Item.Properties()));
        return conveyor;
    }

    /*
     * 向NeoForge事件总线注册方块注册器。
     * <p>
     * 必须在模组初始化阶段调用，通常在主类的构造函数中。
     * </p>
     * 
     * @param eventBus 模组事件总线
     */
    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
