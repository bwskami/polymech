package com.mss.polymech.api.item;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.function.Predicate;

/*
 * 物品标签前缀类，用于数据驱动的物品注册系统。
 * <p>
 * 该类定义了物品的类型前缀（如锭、粗矿等），与材料名称组合生成完整的物品ID。
 * 类似于GregTech Modern中的TagPrefix概念，实现物品的批量自动生成。
 * </p>
 * 
 * <h2>使用示例：</h2>
 * <pre>{@code
 * // 创建锭类型的前缀
 * ItemTagPrefix INGOT = new ItemTagPrefix(
 *     ResourceLocation.fromNamespaceAndPath("poly_mech", "ingot"),
 *     "%s_ingot",      // ID模式：材料名_ingot
 *     "%s Ingot"       // 本地化键模式
 * );
 * 
 * // 生成物品时：steel + ingot -> steel_ingot
 * String itemName = prefix.getIdPattern().formatted("steel"); // "steel_ingot"
 * }</pre>
 * 
 * @see ModItemTypes
 * @see ModItems#getMaterialItem(ItemTagPrefix, String)
 */
public class ItemTagPrefix {
    /* 前缀的唯一标识符 */
    private final ResourceLocation id;
    
    /* 前缀名称（从id中提取的路径部分） */
    private final String name;
    
    /* 物品ID生成模式，包含%s占位符用于替换材料名 */
    private final String idPattern;
    
    /* 本地化文本生成模式，包含%s占位符用于替换材料显示名 */
    private final String langValue;
    
    /* 该前缀物品应归属的创造模式标签页 */
    private CreativeTabTarget creativeTabTarget = CreativeTabTarget.MATERIAL;
    
    /* 材料过滤器，决定哪些材料应该生成此类型的物品 */
    private Predicate<String> materialFilter = materialName -> true;

    /*
     * 创造模式标签页目标枚举。
     * <p>
     * 定义物品应该出现在哪个创造模式标签页中。
     * </p>
     */
    public enum CreativeTabTarget {
        /* 材料标签页（锭、粉、宝石等） */
        MATERIAL,
        /* 方块标签页（存储方块等） */
        BLOCK,
        /* 工具标签页（工具、装备等） */
        TOOL,
        /* 不添加到任何标签页 */
        NONE
    }

    /*
     * 创建一个新的物品标签前缀。
     * 
     * @param id 前缀的唯一标识符
     * @param idPattern 物品ID生成模式，如"%s_ingot"
     * @param langValue 本地化文本生成模式，如"%s Ingot"
     */
    public ItemTagPrefix(ResourceLocation id, String idPattern, String langValue) {
        this.id = id;
        this.name = id.getPath();
        this.idPattern = idPattern;
        this.langValue = langValue;
    }

    /*
     * 设置该前缀物品的目标创造模式标签页。
     * 
     * @param target 目标标签页
     * @return this（支持链式调用）
     */
    public ItemTagPrefix creativeTab(CreativeTabTarget target) {
        this.creativeTabTarget = target;
        return this;
    }

    /*
     * 设置材料过滤器，控制哪些材料应该生成此类型的物品。
     * <p>
     * 例如：INGOT只适用于单质金属，ALLOY_INGOT只适用于合金。
     * </p>
     * 
     * @param filter 材料名称的谓词过滤器
     * @return this（支持链式调用）
     */
    public ItemTagPrefix materialFilter(Predicate<String> filter) {
        this.materialFilter = filter;
        return this;
    }

    /*
     * 获取该前缀的目标创造模式标签页。
     * 
     * @return 目标标签页
     */
    public CreativeTabTarget getCreativeTabTarget() {
        return creativeTabTarget;
    }

    /*
     * 获取前缀的唯一标识符。
     * 
     * @return 前缀的ResourceLocation ID
     */
    public ResourceLocation getId() {
        return id;
    }

    /*
     * 获取前缀名称。
     * 
     * @return 前缀名称（id的路径部分）
     */
    public String getName() {
        return name;
    }

    /*
     * 获取物品ID生成模式。
     * <p>
     * 该模式包含%s占位符，在生成物品ID时会被材料名替换。
     * 例如："%s_ingot".formatted("steel") -> "steel_ingot"
     * </p>
     * 
     * @return ID生成模式字符串
     */
    public String getIdPattern() {
        return idPattern;
    }

    /*
     * 获取本地化文本生成模式。
     * <p>
     * 该模式包含%s占位符，在生成本地化文本时会被材料显示名替换。
     * 例如："%s Ingot".formatted("Steel") -> "Steel Ingot"
     * </p>
     * 
     * @return 本地化文本生成模式字符串
     */
    public String getLangValue() {
        return langValue;
    }

    /*
     * 判断是否应该为指定材料生成此类型的物品。
     * <p>
     * 根据材料过滤器决定是否生成。
     * </p>
     * 
     * @param materialName 材料名称
     * @return 如果应该生成则返回true
     */
    public boolean shouldGenerate(String materialName) {
        return materialFilter.test(materialName);
    }

    /*
     * 创建此类型物品的实例。
     * <p>
     * 默认创建普通的Item实例，子类可以重写此方法以创建特殊物品类型。
     * </p>
     * 
     * @param properties 物品属性
     * @return 新创建的物品实例
     */
    public Item createItem(Item.Properties properties) {
        return new Item(properties);
    }
}
