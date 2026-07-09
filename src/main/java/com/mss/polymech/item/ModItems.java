package com.mss.polymech.item;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.api.item.ItemTagPrefix;
import com.mss.polymech.api.item.ModItemTypes;
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
 * // 1. 遍历所有物品类型前缀（INGOT, RAW_ORE等）
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
