package com.mss.polymech.api.item;

import com.mss.polymech.Polymech;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

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
 * ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));
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
    
    /* 单质金属材料列表 */
    private static final Set<String> PURE_METALS = Set.of(
        "steel", "aluminium", "nickel", "tin", "zinc"
    );
    
    /* 合金材料列表 */
    private static final Set<String> ALLOYS = Set.of(
        "brass", "bronze", "ivar", "cupronickel", "stainless_steel"
    );

    /*
     * 锭类型物品前缀（仅适用于单质金属）。
     * <p>
     * 生成格式：{material}_ingot，如steel_ingot、aluminium_ingot
     * 本地化格式：{Material} Ingot，如Steel Ingot
     * </p>
     */
    public static final ItemTagPrefix INGOT = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "ingot"),
        "%s_ingot",
        "%s Ingot"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL)
     .materialFilter(PURE_METALS::contains));

    /*
     * 合金锭类型物品前缀（仅适用于合金）。
     * <p>
     * 生成格式：{material}_alloy_ingot，如brass_alloy_ingot、bronze_alloy_ingot
     * 本地化格式：{Material} Alloy Ingot，如Brass Alloy Ingot
     * </p>
     */
    public static final ItemTagPrefix ALLOY_INGOT = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "alloy_ingot"),
        "%s_alloy_ingot",
        "%s Alloy Ingot"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL)
     .materialFilter(ALLOYS::contains));

    /*
     * 粒类型物品前缀。
     * <p>
     * 生成格式：{material}_nugget，如steel_nugget
     * 本地化格式：{Material} Nugget，如Steel Nugget
     * </p>
     */
    public static final ItemTagPrefix NUGGET = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "nugget"),
        "%s_nugget",
        "%s Nugget"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 粉类型物品前缀。
     * <p>
     * 生成格式：{material}_dust，如steel_dust
     * 本地化格式：{Material} Dust，如Steel Dust
     * </p>
     */
    public static final ItemTagPrefix DUST = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "dust"),
        "%s_dust",
        "%s Dust"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 板类型物品前缀。
     * <p>
     * 生成格式：{material}_plate，如steel_plate
     * 本地化格式：{Material} Plate，如Steel Plate
     * </p>
     */
    public static final ItemTagPrefix PLATE = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "ingot_plate"),
        "%s_plate",
        "%s Plate"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 箔类型物品前缀。
     * <p>
     * 生成格式：{material}_foil，如steel_foil
     * 本地化格式：{Material} Foil，如Steel Foil
     * </p>
     */
    public static final ItemTagPrefix FOIL = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "foil"),
        "%s_foil",
        "%s Foil"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 杆类型物品前缀。
     * <p>
     * 生成格式：{material}_stick，如steel_stick
     * 本地化格式：{Material} Stick，如Steel Stick
     * </p>
     */
    public static final ItemTagPrefix STICK = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "stick"),
        "%s_stick",
        "%s Stick"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 齿轮类型物品前缀。
     * <p>
     * 生成格式：{material}_gear，如steel_gear
     * 本地化格式：{Material} Gear，如Steel Gear
     * </p>
     */
    public static final ItemTagPrefix GEAR = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "gear"),
        "%s_gear",
        "%s Gear"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 小齿轮类型物品前缀。
     * <p>
     * 生成格式：{material}_small_gear，如steel_small_gear
     * 本地化格式：{Material} Small Gear，如Steel Small Gear
     * </p>
     */
    public static final ItemTagPrefix SMALL_GEAR = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "small_gear"),
        "%s_small_gear",
        "%s Small Gear"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 弹簧类型物品前缀。
     * <p>
     * 生成格式：{material}_spring，如steel_spring
     * 本地化格式：{Material} Spring，如Steel Spring
     * </p>
     */
    public static final ItemTagPrefix SPRING = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "spring"),
        "%s_spring",
        "%s Spring"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 螺丝类型物品前缀。
     * <p>
     * 生成格式：{material}_screw，如steel_screw
     * 本地化格式：{Material} Screw，如Steel Screw
     * </p>
     */
    public static final ItemTagPrefix SCREW = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "screw"),
        "%s_screw",
        "%s Screw"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 螺栓类型物品前缀。
     * <p>
     * 生成格式：{material}_bolt，如steel_bolt
     * 本地化格式：{Material} Bolt，如Steel Bolt
     * </p>
     */
    public static final ItemTagPrefix BOLT = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "bolt"),
        "%s_bolt",
        "%s Bolt"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));

    /*
     * 环类型物品前缀。
     * <p>
     * 生成格式：{material}_ring，如steel_ring
     * 本地化格式：{Material} Ring，如Steel Ring
     * </p>
     */
    public static final ItemTagPrefix RING = register(new ItemTagPrefix(
        ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "ring"),
        "%s_ring",
        "%s Ring"
    ).creativeTab(ItemTagPrefix.CreativeTabTarget.MATERIAL));


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