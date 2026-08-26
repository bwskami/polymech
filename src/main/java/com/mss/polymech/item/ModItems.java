package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.powergrid.GridWireType;
import com.mss.polymech.worldgen.ModMinerals;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.*;

/*
 * 模组物品注册中心，负责所有物品的注册和管理。
 * <p>
 * 该类实现了两种物品注册模式：
 * <ol>
 *   <li><b>手动注册</b>：用于工具等特殊物品（如扳手）</li>
 *   <li><b>数据驱动批量注册</b>：用于材料物品（如各种锭、粗矿）</li>
 * </ol>
 * </p>
 * 
 * <h2>数据驱动注册流程：</h2>
 * <pre>{@code
 * // 1. 遍历所有物品类型前缀（INGOT, PLATE等）
 * for (ItemTagPrefix prefix : ModItemTypes.getAllPrefixes()) {
 *     // 2. 遍历所有材料名称
 *     for (String materialName : MaterialRegistry.getMaterialNames()) {
 *         // 3. 检查是否应该生成
 *         if (prefix.shouldGenerate(materialName)) {
 *             // 4. 生成物品ID并注册
 *             String itemName = prefix.getIdPattern().formatted(materialName);
 *             ITEMS.register(itemName, () -> prefix.createItem(...));
 *         }
 *     }
 * }
 * }</pre>
 * 
 * <h2>获取已注册物品：</h2>
 * <pre>{@code
 * // 通过类型和材料名获取物品
 * DeferredItem<Item> steelIngot = ModItems.getMaterialItem(ModItemTypes.INGOT, "steel");
 * 
 * // 遍历所有材料物品
 * for (DeferredItem<Item> item : ModItems.ALL_MATERIAL_ITEMS) {
 *     // 处理物品...
 * }
 * }</pre>
 * 
 * @see ItemTagPrefix
 * @see ModItemTypes
 * @see MaterialRegistry
 */
public class ModItems {
    /** NeoForge延迟物品注册器 */
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Polymech.MOD_ID);

    // ========== 工具类物品（非数据驱动）==========
    
    /*
     * 扳手物品，用于管道连接操作。
     * <p>
     * 最大堆叠数为1，属于工具类物品。
     * </p>
     */
    public static final DeferredItem<WrenchItem> WRENCH =
            ITEMS.register("wrench", () -> new WrenchItem(new Item.Properties()
                    .stacksTo(1)));

    /*
     * 蓝图工具，用于建造多方块机器。
     * <p>
     * 最大堆叠数为1，用于打开多方块机器建造菜单。
     * </p>
     */
    public static final DeferredItem<BlueprintToolItem> BLUEPRINT =
            ITEMS.register("blueprint", () -> new BlueprintToolItem(new Item.Properties()));

    /*
     * 网络调试仪，手持时高亮显示传送带线路与管道网络（纯标记物品）。
     */
    public static final DeferredItem<NetworkToolItem> NETWORK_TOOL =
            ITEMS.register("network_tool", () -> new NetworkToolItem(new Item.Properties()
                    .stacksTo(1)));

    /*
     * 剪线钳：瞄准世界中的真实电线时显示高亮与详细信息，右键剪断连接。
     */
    public static final DeferredItem<WireCutterItem> WIRE_CUTTER =
            ITEMS.register("wire_cutter", () -> new WireCutterItem(new Item.Properties()
                    .stacksTo(1)));

    /*
     * 钳形表（万用表）：钳口对准电线右击即可测量电流/电压，第一人称有使用动画。
     */
    public static final DeferredItem<ClampMeterItem> CLAMP_METER =
            ITEMS.register("clamp_meter", () -> new ClampMeterItem(new Item.Properties()
                    .stacksTo(1)));

    /*
     * 探矿仪：格雷式勘探工具，右键打开勘探地图（岩石底图+矿物叠加）。
     */
    public static final DeferredItem<ProspectorItem> PROSPECTOR =
            ITEMS.register("prospector", () -> new ProspectorItem(new Item.Properties()
                    .stacksTo(1)));

    /*
     * 焦煤，蜂窝焦炉的产物，用于原始高炉炼钢与高级燃料。
     * 模型复用粉尘染色模板，颜色由 colors.json 的 coke 材质条目提供。
     */
    public static final DeferredItem<Item> COKE =
            ITEMS.register("coke", () -> new Item(new Item.Properties()));

    /*
     * 通用流体单元，单一物品即可盛装任意流体。
     * <p>
     * 流体内容存储在fluid_content数据组件中，流体能力在Polymech#registerCapabilities中注册。
     * 渲染使用neoforge:fluid_container模型加载器（底图不染色，流体层按流体颜色染色）。
     * 按容量分为四种规格：小型250 / 通用1000 / 中型4000 / 超大型16000 mB。
     * </p>
     */
    public static final DeferredItem<FluidCellItem> SMALL_FLUID_CELL =
            ITEMS.register("small_fluid_cell", () -> new FluidCellItem(new Item.Properties(), 250));

    public static final DeferredItem<FluidCellItem> UNIVERSAL_FLUID_CELL =
            ITEMS.register("universal_fluid_cell", () -> new FluidCellItem(new Item.Properties(), FluidCellItem.CAPACITY));

    public static final DeferredItem<FluidCellItem> MEDIUM_FLUID_CELL =
            ITEMS.register("medium_fluid_cell", () -> new FluidCellItem(new Item.Properties(), 4000));

    public static final DeferredItem<FluidCellItem> HUGE_FLUID_CELL =
            ITEMS.register("huge_fluid_cell", () -> new FluidCellItem(new Item.Properties(), 16000));

    /** 所有流体单元种类（按容量从小到大），供创造标签页/能力注册等遍历 */
    @SuppressWarnings("unchecked")
    public static final List<DeferredItem<FluidCellItem>> ALL_FLUID_CELLS = List.of(
            SMALL_FLUID_CELL, UNIVERSAL_FLUID_CELL, MEDIUM_FLUID_CELL, HUGE_FLUID_CELL);

    // ========== 电网物品（电线/线轴） ==========

    /*
     * 金属线轴：每种 {@link GridWireType} 对应一个线轴物品，数据驱动循环注册。
     * 右键电网节点拉线，Shift+右键取消选中。物品名/电气参数均由枚举定义。
     */
    private static final Map<GridWireType, DeferredItem<WireSpoolItem>> WIRE_SPOOL_TABLE =
            new EnumMap<>(GridWireType.class);

    /** 所有电网线轴（按枚举声明顺序），供创造标签页遍历 */
    public static final List<DeferredItem<WireSpoolItem>> ALL_WIRE_SPOOLS;

    static {
        for (GridWireType type : GridWireType.values()) {
            WIRE_SPOOL_TABLE.put(type, ITEMS.register(type.spoolItemName(),
                    () -> new WireSpoolItem(new Item.Properties().stacksTo(1), type)));
        }
        ALL_WIRE_SPOOLS = List.copyOf(WIRE_SPOOL_TABLE.values());
    }

    /* 空线轴：拆线工具，右键节点方块断开其全部电线连接 */
    public static final DeferredItem<EmptySpoolItem> EMPTY_SPOOL =
            ITEMS.register("empty_spool", () -> new EmptySpoolItem(new Item.Properties().stacksTo(16)));

    /**
     * 获取指定电线类型对应的线轴物品（GridWireType.getSpoolItem内部使用）。
     */
    public static Item getWireSpoolItem(GridWireType type) {
        return WIRE_SPOOL_TABLE.get(type).get();
    }


    // ========== 材料物品：数据驱动批量注册 ==========
    
    /*
     * 材料物品查找表。
     * <p>
     * 结构：Map&lt;物品类型, Map&lt;材料名, 物品引用&gt;&gt;
     * 用于快速查找特定类型和材料的物品。
     * </p>
     */
    private static final Map<ItemTagPrefix, Map<String, DeferredItem<Item>>> MATERIAL_ITEMS_TABLE = new LinkedHashMap<>();
    
    /*
     * 所有材料物品的扁平列表。
     * <p>
     * 包含所有通过数据驱动生成的物品，便于批量操作（如创造模式标签页填充）。
     * </p>
     */
    public static final List<DeferredItem<Item>> ALL_MATERIAL_ITEMS = new ArrayList<>();

    /*
     * 矿物加工产物查找表（粉碎矿/洗净矿）。
     * <p>
     * 结构与材料物品表相同，但键是矿物名（来自{@link ModMinerals}），
     * 与材料系统并行：粗矿/粉碎矿/洗净矿都属于矿物而非材料，
     * 化学式直接取自{@link com.mss.polymech.worldgen.ModMinerals.MineralDefinition#formula()}。
     * </p>
     */
    private static final Map<ItemTagPrefix, Map<String, DeferredItem<Item>>> MINERAL_ITEMS_TABLE = new LinkedHashMap<>();

    /** 所有矿物加工产物的扁平列表（创造模式标签页填充使用） */
    public static final List<DeferredItem<Item>> ALL_MINERAL_ITEMS = new ArrayList<>();

    static {
        // 数据驱动批量注册流程
        for (ItemTagPrefix prefix : ModItemTypes.getAllPrefixes()) {
            Map<String, DeferredItem<Item>> materialMap = new LinkedHashMap<>();
            for (String materialName : MaterialRegistry.getMaterialNames()) {
                // 检查是否应该为该材料生成此类型的物品
                if (prefix.shouldGenerate(materialName)) {
                    // 根据前缀模式和材料名生成物品ID
                    String itemName = prefix.getIdPattern().formatted(materialName);
                    
                    // 注册物品
                    DeferredItem<Item> item = ITEMS.register(itemName, 
                            () -> prefix.createItem(new Item.Properties()));
                    
                    materialMap.put(materialName, item);
                    ALL_MATERIAL_ITEMS.add(item);
                }
            }
            // 存储不可修改的材料映射
            MATERIAL_ITEMS_TABLE.put(prefix, Collections.unmodifiableMap(materialMap));
        }

        // 矿物加工中间产物（粉碎矿/洗净矿）：按矿物定义表批量注册。
        // 煤炭直接掉煤不加工；粉末类矿物本身已经是粉/矿物粉，不需要再洗选；
        // 只有 METAL 粗矿与 GEM 宝石矿才进入格雷式三级选矿链。
        for (ItemTagPrefix prefix : new ItemTagPrefix[]{ModItemTypes.CRUSHED, ModItemTypes.PURIFIED}) {
            Map<String, DeferredItem<Item>> mineralMap = new LinkedHashMap<>();
            for (com.mss.polymech.worldgen.ModMinerals.MineralDefinition def : com.mss.polymech.worldgen.ModMinerals.getDefinitions()) {
                if (def.kind() == com.mss.polymech.worldgen.ModMinerals.ProductKind.COAL
                        || def.kind() == com.mss.polymech.worldgen.ModMinerals.ProductKind.DUST) continue;
                String itemName = prefix.getIdPattern().formatted(def.mineral());
                DeferredItem<Item> item = ITEMS.register(itemName,
                        () -> prefix.createItem(new Item.Properties()));
                mineralMap.put(def.mineral(), item);
                ALL_MINERAL_ITEMS.add(item);
            }
            MINERAL_ITEMS_TABLE.put(prefix, Collections.unmodifiableMap(mineralMap));
        }
    }

    // ========== 粗矿物物品：矿物不属于材料系统，单独注册 ==========

    /**
     * 粗矿物物品查找表：矿物名 → 粗矿物物品（raw_{mineral}）。
     * <p>
     * 由{@link ModMinerals}定义表驱动。粗矿物是矿石方块的掉落物，
     * 经破碎机/跳汰机选矿产出对应金属粉末——金属是矿物加工的产物，
     * 不存在"粗锡石直接熔炼出锡锭"的捷径。
     * </p>
     */
    public static final Map<String, DeferredItem<Item>> RAW_MINERAL_ITEMS;

    /** 所有粗矿物物品的扁平列表（创造模式标签页填充使用） */
    public static final List<DeferredItem<Item>> ALL_RAW_MINERAL_ITEMS;

    static {
        Map<String, DeferredItem<Item>> rawMinerals = new LinkedHashMap<>();
        List<DeferredItem<Item>> rawMineralList = new ArrayList<>();
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            // 仅金属矿物产粗矿；宝石矿直接掉宝石、粉矿直接掉粉、煤矿掉煤炭
            if (def.kind() != ModMinerals.ProductKind.METAL) continue;
            DeferredItem<Item> item = ITEMS.register(def.rawItemName(),
                    () -> new Item(new Item.Properties()));
            rawMinerals.put(def.mineral(), item);
            rawMineralList.add(item);
        }
        RAW_MINERAL_ITEMS = Collections.unmodifiableMap(rawMinerals);
        ALL_RAW_MINERAL_ITEMS = Collections.unmodifiableList(rawMineralList);
    }

    /** 按矿物名获取粗矿物物品；不存在返回null */
    public static DeferredItem<Item> getRawMineral(String mineralName) {
        return RAW_MINERAL_ITEMS.get(mineralName);
    }

    /*
     * 获取指定类型和材料的物品引用。
     * <p>
     * 这是访问数据驱动物品的主要方式。
     * </p>
     * 
     * @param prefix 物品类型前缀
     * @param materialName 材料名称
     * @return 对应的物品引用，如果不存在则返回null
     * 
     * @throws NullPointerException 如果prefix或materialName为null
     */
    public static DeferredItem<Item> getMaterialItem(ItemTagPrefix prefix, String materialName) {
        return MATERIAL_ITEMS_TABLE.get(prefix).get(materialName);
    }

    /*
     * 获取指定矿物加工类型和矿物的物品引用（粉碎矿/洗净矿等）。
     *
     * @param prefix 矿物加工前缀（ModItemTypes.CRUSHED / PURIFIED）
     * @param mineralName 矿物名
     * @return 对应的物品引用；不存在返回null
     */
    public static DeferredItem<Item> getMineralItem(ItemTagPrefix prefix, String mineralName) {
        Map<String, DeferredItem<Item>> map = MINERAL_ITEMS_TABLE.get(prefix);
        return map == null ? null : map.get(mineralName);
    }

    /** 物品→材料名反查表（惰性构建，注册完成后才有意义） */
    private static volatile Map<Item, String> ITEM_MATERIAL_LOOKUP;
    private static volatile Map<Item, String> ITEM_MINERAL_LOOKUP;

    /*
     * 反查物品对应的材料名（类似GregTech的ChemicalHelper.getMaterialEntry）。
     * <p>
     * 仅覆盖数据驱动注册的材料物品（锭、粉、板等），
     * 机器、单元等功能性物品不在其中。非材料物品返回null。
     * </p>
     *
     * @param item 物品实例
     * @return 材料名；非材料物品返回null
     */
    public static String getMaterialOf(Item item) {
        Map<Item, String> lookup = ITEM_MATERIAL_LOOKUP;
        if (lookup == null) {
            lookup = new IdentityHashMap<>();
            for (Map<String, DeferredItem<Item>> materialMap : MATERIAL_ITEMS_TABLE.values()) {
                for (Map.Entry<String, DeferredItem<Item>> entry : materialMap.entrySet()) {
                    lookup.put(entry.getValue().get(), entry.getKey());
                }
            }
            ITEM_MATERIAL_LOOKUP = lookup;
        }
        return lookup.get(item);
    }

    /*
     * 反查物品对应的矿物名（类似GT ChemicalHelper，但针对矿物加工产物）。
     * <p>
     * 覆盖：粗矿raw_{mineral}、粉碎矿{mineral}_crushed、洗净矿{mineral}_purified。
     * 这些物品不属于材料系统（材料系统管金属/宝石/粉状单质），
     * 化学式来自{@link ModMinerals.MineralDefinition}。
     * </p>
     *
     * @param item 物品实例
     * @return 矿物名；非矿物物品返回null
     */
    public static String getMineralOf(Item item) {
        Map<Item, String> lookup = ITEM_MINERAL_LOOKUP;
        if (lookup == null) {
            lookup = new IdentityHashMap<>();
            // 粗矿物
            for (Map.Entry<String, DeferredItem<Item>> entry : RAW_MINERAL_ITEMS.entrySet()) {
                lookup.put(entry.getValue().get(), entry.getKey());
            }
            // 矿物加工产物（粉碎矿/洗净矿）
            for (Map<String, DeferredItem<Item>> mineralMap : MINERAL_ITEMS_TABLE.values()) {
                for (Map.Entry<String, DeferredItem<Item>> entry : mineralMap.entrySet()) {
                    lookup.put(entry.getValue().get(), entry.getKey());
                }
            }
            ITEM_MINERAL_LOOKUP = lookup;
        }
        return lookup.get(item);
    }

    /*
     * 向NeoForge事件总线注册物品注册器。
     * <p>
     * 必须在模组初始化阶段调用，通常在主类的构造函数中。
     * </p>
     * 
     * @param eventBus 模组事件总线
     */
    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
