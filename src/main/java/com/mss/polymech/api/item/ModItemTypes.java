package com.mss.polymech.api.item;

import com.mss.polymech.Polymech;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/*
 * 模组物品类型定义类，集中管理所有物品标签前缀。
 * <p>
 * 该类采用数据驱动设计，定义模组中所有可用的物品类型（如锭、粗矿等）。
 * 每个类型通过{@link ItemTagPrefix}定义，并与材料组合自动生成物品。
 * </p>
 * 
 * <h2>扩展新物品类型：</h2>
 * <pre>{@code
 * // 1. 定义新的前缀常量
 * public static final ItemTagPrefix PLATE = register(new ItemTagPrefix(
 *     ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "plate"),
 *     "%s_plate",
 *     "%s Plate"
 * ));
 * 
 * // 2. 在MaterialRegistry中添加支持的材料
 * // 3. 物品会自动生成并注册
 * }</pre>
 * 
 * @see ItemTagPrefix
 * @see MaterialRegistry
 */
public class ModItemTypes {
    /* 存储所有已注册的物品前缀列表 */
    private static final List<ItemTagPrefix> ALL_PREFIXES = new ArrayList<>();

    /*
     * 锭类型物品前缀。
     * <p>
     * 生成格式：{material}_ingot，如steel_ingot、brass_ingot
     * 本地化格式：{Material} Ingot，如Steel Ingot
     * </p>
     */
    public static final ItemTagPrefix INGOT = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "ingot"),
        "%s_ingot",
        "%s Ingot"
    ));

    /*
     * 粗矿类型物品前缀。
     * <p>
     * 生成格式：raw_{material}，如raw_test
     * 本地化格式：Raw {Material}，如Raw Test
     * </p>
     */
    public static final ItemTagPrefix RAW_ORE = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "raw_ore"),
        "raw_%s",
        "Raw %s"
    ));

    /*
     * 测试物品类型前缀（仅用于开发测试）。
     * <p>
     * 生成格式：test_item{number}，如test_item1、test_item2
     * 本地化格式：Test Item {Number}，如Test Item 1
     * </p>
     */
    public static final ItemTagPrefix TEST_ITEM = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "test_item"),
        "test_item%s",
        "Test Item %s"
    ));

    /*
     * 注册物品前缀到全局列表。
     * <p>
     * 所有前缀必须通过此方法注册，以确保被纳入数据驱动生成流程。
     * </p>
     * 
     * @param prefix 要注册的物品前缀
     * @return 注册后的前缀（便于链式调用）
     */
    private static ItemTagPrefix register(ItemTagPrefix prefix) {
        ALL_PREFIXES.add(prefix);
        return prefix;
    }

    /*
     * 获取所有已注册的物品前缀列表。
     * <p>
     * 返回不可修改的列表视图，防止外部代码意外修改注册表。
     * </p>
     * 
     * @return 所有物品前缀的只读列表
     */
    public static List<ItemTagPrefix> getAllPrefixes() {
        return Collections.unmodifiableList(ALL_PREFIXES);
    }
}
