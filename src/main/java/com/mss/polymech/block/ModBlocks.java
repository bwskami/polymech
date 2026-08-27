package com.mss.polymech.block;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.api.material.ConveyorMaterial;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.entity.FluidTankBlock;
import com.mss.polymech.machine.boiler.SmallSteamBoilerBlock;
import com.mss.polymech.machine.boiler.SmallSteamBoilerBlockEntity;
import com.mss.polymech.item.ConnectorItem;
import com.mss.polymech.item.MachineBlockItem;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.item.PipeItem;
import com.mss.polymech.item.ConveyorItem;
import com.mss.polymech.machine.common.MachineConfig;
import com.mss.polymech.machine.common.MachineRegistry;
import com.mss.polymech.machine.common.MachineRegistrar;
import com.mss.polymech.machine.production.*;
import com.mss.polymech.worldgen.ModMinerals;
import com.mss.polymech.worldgen.ModRocks;
import com.mss.polymech.powergrid.ConcretePoleBlock;
import com.mss.polymech.powergrid.ConnectorBlock;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.resources.ResourceLocation;
import com.mss.polymech.block.SurfaceRockBlock;
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

    /* 传送带方块（多材质数据驱动注册，见下方 CONVEYOR_TABLE） */

    /* 小型蒸汽锅炉（单方块机器） */
    public static final DeferredBlock<SmallSteamBoilerBlock> SMALL_STEAM_BOILER =
            registerBlocks("small_steam_boiler", () -> new SmallSteamBoilerBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // ========== 电网方块（真实电线电网） ==========

    /*
     * 电气连接器：可贴在任意面的小挂墙连接端子，电网基本接入点。
     * 节点位于方块中心，电线从中心向外拉出。
     */
    public static final DeferredBlock<ConnectorBlock> CONNECTOR =
            registerConnector("connector", () -> new ConnectorBlock(Block.Properties.of()
                    .strength(1.5F, 6.0F)
                    .sound(SoundType.COPPER)
                    .noOcclusion()));

    /* 混凝土电杆：高耸的输电线支撑杆，用于支撑架设跨距离的输电线路。
     * 纯支撑结构，本身不提供电网节点。
     */
    public static final DeferredBlock<ConcretePoleBlock> CONCRETE_POLE =
            registerBlocks("concrete_pole", () -> new ConcretePoleBlock(Block.Properties.of()
                    .strength(3.0F, 12.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    /* 蓄电池（普通 + 创造模式） */
    public static final DeferredBlock<BatteryBlock> BATTERY =
            registerBlocks("battery", () -> new BatteryBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    public static final DeferredBlock<CreativeBatteryBlock> CREATIVE_BATTERY =
            registerBlocks("creative_battery", () -> new CreativeBatteryBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));

    // ========== Phase 1：大型机器方块注册（侧面方块和BE在Phase 2中补充） ==========

    private static Block.Properties machineProps() {
        return Block.Properties.of()
                .strength(3.5F, 4.8F)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .dynamicShape();
    }

    /* 填充装置 */
    public static final MachineRegistrar.MachineRegistration FILLING_UNIT =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("filling_unit")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(FillingUnitBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    (block, props) -> new MachineBlockItem(block, props,
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "geo/filling_unit.geo.json"),
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "textures/block/filling_unit.png"),
                            ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "animations/filling_unit.animation.json")));

    /* 水平蒸汽锅炉 */
    public static final MachineRegistrar.MachineRegistration HORIZONTAL_STEAM_BOILER =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("horizontal_steam_boiler")
                            .blockFactory(HorizontalSteamBoilerBlock::new)
                            .blockProperties(machineProps())
                            .blockEntityFactory(HorizontalSteamBoilerBlockEntity::new)
                            .slotCount(5)   // 输入水桶、输出空桶、燃料、输出蒸汽桶、灰烬
                            .build(),
                    MachineBlockItem::new);

    /* 蜂巢焦炉 */
    public static final MachineRegistrar.MachineRegistration BEEHIVE_COKE_OVEN =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("beehive_coke_oven")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(BeehiveCokeOvenBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

    /* 原始高炉 */
    public static final MachineRegistrar.MachineRegistration PRIMITIVE_BLAST_FURNACE =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("primitive_blast_furnace")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(PrimitiveBlastFurnaceBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

    /* 蒸汽辊式破碎机 */
    public static final MachineRegistrar.MachineRegistration STEAM_ROLLER_CRUSHER =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("steam_roller_crusher")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(SteamRollerCrusherBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

    /* 蒸汽涡轮发电机 */
    public static final MachineRegistrar.MachineRegistration STEAM_TURBINE_GENERATOR =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("steam_turbine_generator")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(SteamTurbineGeneratorBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

    /* 火焰反射炉 */
    public static final MachineRegistrar.MachineRegistration FLAME_REVERBERATORY_FURNACE =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("flame_reverberatory_furnace")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(FlameReverberatoryFurnaceBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

    /* 燃气涡轮发电机 */
    public static final MachineRegistrar.MachineRegistration GAS_TURBINE_GENERATOR =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("gas_turbine_generator")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(GasTurbineGeneratorBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

    /* 蒸汽双联矿物跳汰机 */
    public static final MachineRegistrar.MachineRegistration STEAM_DUPLEX_MINERAL_JIG =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("steam_duplex_mineral_jig")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(SteamDuplexMineralJigBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

    /* 蒸汽锤 */
    public static final MachineRegistrar.MachineRegistration STEAM_HAMMER =
            MachineRegistrar.registerBlock(
                    MachineConfig.builder("steam_hammer")
                            .sideOffsets(MachineConfig.crossOffsets())
                            .blockProperties(machineProps())
                            .blockEntityFactory(SteamHammerBlockEntity::new)
                            .blockFactory(com.mss.polymech.machine.common.WorkableMachineBlock::new)
                            .build(),
                    MachineBlockItem::new);

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

    // ========== 传送带方块：数据驱动批量注册 ==========

    /* 内部传送带查找表（构建期间使用） */
    private static final Map<ConveyorMaterial, DeferredBlock<ConveyorBlock>> CONVEYOR_TABLE_INTERNAL = new LinkedHashMap<>();

    /*
     * 传送带方块查找表。
     * <p>
     * 结构：Map&lt;传送带材料, 方块引用&gt;
     * </p>
     */
    public static final Map<ConveyorMaterial, DeferredBlock<ConveyorBlock>> CONVEYOR_TABLE;

    /*
     * 所有传送带方块的扁平列表，便于批量操作（渲染层、颜色、战利品表等）。
     */
    public static final List<DeferredBlock<ConveyorBlock>> CONVEYOR_BLOCKS;

    // ========== 金属存储块：数据驱动批量注册 ==========

    /**
     * 存储块贴图选择标准（原子质量阈值）：
     * 材料平均原子质量 &gt;= 该值 → 普通贴图（block_normal）；低于该值 → 重型贴图（block_heavy）。
     * 阈值取55（铁附近），可按需调整。
     */
    public static final double MASS_THRESHOLD = 55.0;

    /**
     * 金属存储块查找表：材料名 → 存储块（仅含锭材料，如 steel_block、brass_block）。
     * 模型为三层染色模板（base/overlay/secondary，tintindex 0/1/2），
     * 颜色由 colors.json 的材料条目提供（自动推断 _block 后缀）。
     */
    public static final Map<String, DeferredBlock<Block>> MATERIAL_BLOCKS;

    /** 方块→材料名 反查表（存储块化学式tooltip使用） */
    private static final Map<Block, String> BLOCK_MATERIAL_LOOKUP = new IdentityHashMap<>();
    /** 矿石方块→矿物名反查（化学式tooltip用） */
    private static final Map<Block, String> BLOCK_MINERAL_LOOKUP = new IdentityHashMap<>();

    // ========== 矿石方块：数据驱动批量注册（格雷/群峦式岩种变体） ==========

    /** 石头宿主键（原版石头底图变体） */
    public static final String ORE_HOST_STONE = "stone";
    /** 深板岩宿主键（群峦无深板岩岩石，用原版深板岩底图染色） */
    public static final String ORE_HOST_DEEPSLATE = "deepslate";
    /** 下界岩宿主键（格雷/GTM 式下界矿石变体） */
    public static final String ORE_HOST_NETHERRACK = "netherrack";
    /** 末地石宿主键（格雷/GTM 式末地矿石变体） */
    public static final String ORE_HOST_END_STONE = "end_stone";

    /*
     * 矿石方块组：一种矿物对应每种宿主岩一个方块（岩种变体）。
     * <p>
     * 变体键 = {@link #ORE_HOST_STONE} / {@link #ORE_HOST_DEEPSLATE} /
     * {@link #ORE_HOST_NETHERRACK} / {@link #ORE_HOST_END_STONE} /
     * 21种群峦岩种名（{@link ModRocks}）：
     * <ul>
     *   <li>stone → {mineral}_ore（原版石头底图，不染色）</li>
     *   <li>deepslate → deepslate_{mineral}_ore（原版深板岩底图，染色）</li>
     *   <li>netherrack → {mineral}_netherrack_ore（原版下界岩底图，不染色）</li>
     *   <li>end_stone → {mineral}_end_stone_ore（原版末地石底图，不染色）</li>
     *   <li>{rock} → {mineral}_{rock}_ore（群峦岩石底图，不染色）</li>
     * </ul>
     * </p>
     *
     * @param mineral 矿物名
     * @param byRock 宿主键→矿石方块 不可变映射
     */
    public record OreBlockSet(String mineral, Map<String, DeferredBlock<Block>> byRock) {
        /** 石头矿方块（{mineral}_ore） */
        public DeferredBlock<Block> stone() {
            return byRock.get(ORE_HOST_STONE);
        }

        /** 深层矿方块（deepslate_{mineral}_ore） */
        public DeferredBlock<Block> deepslate() {
            return byRock.get(ORE_HOST_DEEPSLATE);
        }

        /** 下界岩矿方块（{mineral}_netherrack_ore） */
        public DeferredBlock<Block> netherrack() {
            return byRock.get(ORE_HOST_NETHERRACK);
        }

        /** 末地石矿方块（{mineral}_end_stone_ore） */
        public DeferredBlock<Block> endStone() {
            return byRock.get(ORE_HOST_END_STONE);
        }

        /** 按宿主岩名取岩种矿方块；不存在返回null */
        public DeferredBlock<Block> forRock(String rock) {
            return byRock.get(rock);
        }

        /** 全部岩种变体（含石头/深板岩/下界岩/末地石） */
        public java.util.Collection<DeferredBlock<Block>> all() {
            return byRock.values();
        }
    }

    /**
     * 矿石方块查找表：矿物名 → 矿石方块组（每矿物25个岩种变体）。
     * <p>
     * 由{@link ModMinerals}的定义表×{@link ModRocks}岩种表驱动生成，
     * 与粗矿物物品、世界生成、战利品表、配方等模块共享同一数据源。
     * </p>
     */
    public static final Map<String, OreBlockSet> MINERAL_ORES;

    /** 所有矿石方块的扁平列表（方块染色、渲染层设置等批量操作使用） */
    public static final List<DeferredBlock<Block>> MINERAL_ORE_LIST;

    // ========== 区域岩石方块：数据驱动批量注册 ==========

    /**
     * 区域岩石查找表：岩种名 → 岩石方块。
     * <p>
     * 由{@link ModRocks}的定义表驱动生成；岩石为单层染色方块
     * （原版石头底图×岩种配色，见colors.json）。
     * 注意：不加入 vanilla stone_ore_replaceables，避免原版小矿脉混入模组岩层，
     * 只保留模组自身的大矿脉（GT 式矿脉）作为主要矿物来源。
     * </p>
     */
    public static final Map<String, DeferredBlock<Block>> ROCKS;

    /** 所有岩石方块的扁平列表（方块染色等批量操作使用） */
    public static final List<DeferredBlock<Block>> ROCK_BLOCK_LIST;

    // ========== 地表碎石指示方块：矿物名 → SurfaceRockBlock ==========

    /** 地表碎石查找表：矿物名 → SurfaceRockBlock */
    public static final Map<String, DeferredBlock<SurfaceRockBlock>> SURFACE_ROCKS;

    /** 所有地表碎石方块的扁平列表（染色/渲染层批量操作） */
    public static final List<DeferredBlock<SurfaceRockBlock>> SURFACE_ROCK_LIST;

    static {
        // 数据驱动批量注册流程
        List<DeferredBlock<PipeBlock>> allPipes = new ArrayList<>();
        
        // 遍历所有管道材料（原版金属+全部含锭材料，自动注册）
        for (PipeMaterial material : PipeMaterial.getAll()) {
            Map<PipeBlock.PipeSize, DeferredBlock<PipeBlock>> sizeMap = new LinkedHashMap<>();
            
            // 遍历所有管道尺寸
            for (PipeBlock.PipeSize size : PipeBlock.PipeSize.values()) {
                // 生成注册名称（如：pipe, bronze_pipe, stainless_steel_small_pipe）
                String name = size.getRegistryName(material);
                
                // 注册管道方块（材质 + 尺寸，材质决定流速倍率）
                DeferredBlock<PipeBlock> pipe = registerPipe(name,
                        () -> new PipeBlock(Block.Properties.of()
                                .strength(material.getStrength(), material.getResistance())
                                .sound(material.getSoundType())
                                .requiresCorrectToolForDrops()
                                .noOcclusion(),
                                material,
                                size));
                
                sizeMap.put(size, pipe);
                allPipes.add(pipe);
            }
            PIPE_TABLE_INTERNAL.put(material, Collections.unmodifiableMap(sizeMap));
        }
        
        // 创建不可修改的公共视图
        PIPE_TABLE = Collections.unmodifiableMap(PIPE_TABLE_INTERNAL);
        PIPE_BLOCKS = Collections.unmodifiableList(allPipes);

        // 传送带：按材质批量注册（默认材质注册名为 conveyor，其余为 <material>_conveyor）
        List<DeferredBlock<ConveyorBlock>> allConveyors = new ArrayList<>();
        for (ConveyorMaterial material : ConveyorMaterial.values()) {
            DeferredBlock<ConveyorBlock> conveyor = registerConveyor(material.getConveyorRegistryName(),
                    () -> new ConveyorBlock(Block.Properties.of()
                            .strength(material.getStrength(), material.getResistance())
                            .sound(material.getSoundType())
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));
            CONVEYOR_TABLE_INTERNAL.put(material, conveyor);
            allConveyors.add(conveyor);
        }
        CONVEYOR_TABLE = Collections.unmodifiableMap(CONVEYOR_TABLE_INTERNAL);
        CONVEYOR_BLOCKS = Collections.unmodifiableList(allConveyors);

        // 金属存储块：为每种含锭材料注册 {material}_block
        Map<String, DeferredBlock<Block>> materialBlocks = new LinkedHashMap<>();
        for (String materialName : MaterialRegistry.getMaterialNames()) {
            if (!ModItemTypes.hasIngot(materialName)) continue;
            String registryName = materialName + "_block";
            DeferredBlock<Block> block = registerBlocks(registryName,
                    () -> new Block(Block.Properties.of()
                            .strength(5.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));
            materialBlocks.put(materialName, block);
        }
        MATERIAL_BLOCKS = Collections.unmodifiableMap(materialBlocks);

        // 矿石方块：为每种矿物×每种宿主岩注册岩种变体（格雷/群峦式）
        // 石头矿与原版矿石同硬度；深层矿更硬（参考原版深层铁矿 4.5）；
        // 岩种矿硬度与群峦原矿一致（3.0），掉落物由产物类型决定（见战利品表）
        Map<String, OreBlockSet> oreBlocks = new LinkedHashMap<>();
        List<DeferredBlock<Block>> oreBlockList = new ArrayList<>();
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            Map<String, DeferredBlock<Block>> byRock = new LinkedHashMap<>();

            // 石头变体（原版石头底图，兼容保留）
            DeferredBlock<Block> stoneOre = registerBlocks(def.stoneOreName(),
                    () -> new Block(Block.Properties.of()
                            .strength(3.0F, 3.0F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));
            byRock.put(ORE_HOST_STONE, stoneOre);

            // 深板岩变体（群峦无深板岩岩石→原版深板岩底图染色）
            DeferredBlock<Block> deepslateOre = registerBlocks(def.deepslateOreName(),
                    () -> new Block(Block.Properties.of()
                            .strength(4.5F, 3.0F)
                            .sound(SoundType.DEEPSLATE)
                            .requiresCorrectToolForDrops()));
            byRock.put(ORE_HOST_DEEPSLATE, deepslateOre);

            // 下界岩变体（GTM 式跨维度矿石）
            DeferredBlock<Block> netherrackOre = registerBlocks(def.netherrackOreName(),
                    () -> new Block(Block.Properties.of()
                            .strength(2.0F, 3.0F)
                            .sound(SoundType.NETHERRACK)
                            .requiresCorrectToolForDrops()));
            byRock.put(ORE_HOST_NETHERRACK, netherrackOre);

            // 末地石变体（GTM 式跨维度矿石）
            DeferredBlock<Block> endStoneOre = registerBlocks(def.endStoneOreName(),
                    () -> new Block(Block.Properties.of()
                            .strength(3.0F, 9.0F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));
            byRock.put(ORE_HOST_END_STONE, endStoneOre);

            // 21种群峦岩种变体（群峦式"看岩认矿"）
            for (ModRocks.RockType rock : ModRocks.ROCK_TYPES) {
                DeferredBlock<Block> rockOre = registerBlocks(def.rockOreName(rock.name()),
                        () -> new Block(Block.Properties.of()
                                .strength(3.0F, 6.0F)
                                .sound(SoundType.STONE)
                                .requiresCorrectToolForDrops()));
                byRock.put(rock.name(), rockOre);
            }

            oreBlocks.put(def.mineral(), new OreBlockSet(def.mineral(),
                    Collections.unmodifiableMap(byRock)));
            oreBlockList.addAll(byRock.values());
        }
        MINERAL_ORES = Collections.unmodifiableMap(oreBlocks);
        MINERAL_ORE_LIST = Collections.unmodifiableList(oreBlockList);

        // 区域岩石：为每种岩种注册单层染色石头方块
        Map<String, DeferredBlock<Block>> rocks = new LinkedHashMap<>();
        List<DeferredBlock<Block>> rockList = new ArrayList<>();
        for (ModRocks.RockType rock : ModRocks.ROCK_TYPES) {
            DeferredBlock<Block> rockBlock = registerBlocks(rock.name(),
                    () -> new Block(Block.Properties.of()
                            .strength(2.0F, 6.0F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));
            rocks.put(rock.name(), rockBlock);
            rockList.add(rockBlock);
        }
        ROCKS = Collections.unmodifiableMap(rocks);
        ROCK_BLOCK_LIST = Collections.unmodifiableList(rockList);

        // 地表碎石指示方块：为每种矿物注册一个 SurfaceRockBlock
        // GTM风格：薄片方块，贴在地表，用tintindex染色区分矿物颜色
        Map<String, DeferredBlock<SurfaceRockBlock>> surfaceRocks = new LinkedHashMap<>();
        List<DeferredBlock<SurfaceRockBlock>> surfaceRockList = new ArrayList<>();
        String[] SURFACE_ROCK_MINERALS = {
            "alunite", "amethyst", "apatite", "barite", "basaltic_mineral_sand",
            "bastnasite", "bauxite", "beryllium", "bismuthinite", "bituminous_coal",
            "borax", "cassiterite", "cassiterite_sand", "chalcopyrite", "chromite",
            "cinnabar", "cooperite", "cryolite", "diamond", "emerald",
            "galena", "garnierite", "goethite", "graphite", "grossular",
            "gypsum", "hematite", "kyanite", "lapis_lazuli", "lignite",
            "limonite", "magnetite", "malachite", "molybdenite", "native_copper",
            "native_gold", "native_silver", "oilsands", "olivine", "opal",
            "pitchblende", "pyrite", "realgar", "red_garnet", "redstone",
            "rock_salt", "ruby", "saltpeter", "sapphire", "sphalerite",
            "stibnite", "sulfur", "sylvite", "talc", "tetrahedrite",
            "thorium", "topaz", "vanadium_magnetite", "wolframite", "zeolite"
        };
        for (String mineral : SURFACE_ROCK_MINERALS) {
            final String mineralName = mineral;
            DeferredBlock<SurfaceRockBlock> rock = registerSurfaceRock(mineral,
                    () -> new SurfaceRockBlock(Block.Properties.of()
                            .strength(0.05F, 0.0F)
                            .sound(SoundType.NETHER_ORE)
                            .noCollission()
                            .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY),
                            mineralName));
            surfaceRocks.put(mineral, rock);
            surfaceRockList.add(rock);
        }
        SURFACE_ROCKS = Collections.unmodifiableMap(surfaceRocks);
        SURFACE_ROCK_LIST = Collections.unmodifiableList(surfaceRockList);
    }

    /**
     * 方块→材料名反查（用于存储块化学式tooltip）；非材料存储块返回null。
     */
    public static String getMaterialOfBlock(Block block) {
        if (BLOCK_MATERIAL_LOOKUP.isEmpty() && !MATERIAL_BLOCKS.isEmpty()) {
            for (Map.Entry<String, DeferredBlock<Block>> entry : MATERIAL_BLOCKS.entrySet()) {
                BLOCK_MATERIAL_LOOKUP.put(entry.getValue().get(), entry.getKey());
            }
        }
        return BLOCK_MATERIAL_LOOKUP.get(block);
    }

    /**
     * 方块→矿物名反查（用于矿石方块化学式tooltip）；非矿石方块返回null。
     * <p>
     * 矿石方块按矿物×宿主岩全量注册，全部变体（石头/深板岩/21种群峦岩种）
     * 都映射回矿物名，化学式取自{@link ModMinerals.MineralDefinition}。
     * </p>
     */
    public static String getMineralOfBlock(Block block) {
        if (BLOCK_MINERAL_LOOKUP.isEmpty() && !MINERAL_ORES.isEmpty()) {
            for (var entry : MINERAL_ORES.entrySet()) {
                for (var oreBlock : entry.getValue().all()) {
                    BLOCK_MINERAL_LOOKUP.put(oreBlock.get(), entry.getKey());
                }
            }
        }
        return BLOCK_MINERAL_LOOKUP.get(block);
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

    /*
     * 获取指定材料的传送带方块引用。
     *
     * @param material 传送带材料
     * @return 对应的传送带方块引用
     */
    public static DeferredBlock<ConveyorBlock> getConveyor(ConveyorMaterial material) {
        return CONVEYOR_TABLE.get(material);
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
     * 注册地表碎石方块（同时注册BlockItem，可在创造模式背包中使用）。
     */
    private static <T extends Block> DeferredBlock<T> registerSurfaceRock(String name, Supplier<T> block) {
        DeferredBlock<T> registered = BLOCKS.register(name, block);
        ModItems.ITEMS.register(name, () -> new BlockItem(registered.get(), new Item.Properties()));
        return registered;
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

    /*
     * 注册连接器方块及其物品。
     * <p>
     * 连接器使用ConnectorItem而非普通BlockItem，以添加电网接入点说明tooltip。
     * </p>
     */
    private static DeferredBlock<ConnectorBlock> registerConnector(String name, Supplier<ConnectorBlock> block) {
        DeferredBlock<ConnectorBlock> connector = BLOCKS.register(name, block);
        ModItems.ITEMS.register(name, () -> new ConnectorItem(connector.get(), new Item.Properties()));
        return connector;
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
