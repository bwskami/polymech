package com.mss.polymech.api.material;

import java.util.*;

/*
 * 材料注册表，集中管理模组中所有可用的材料定义。
 * <p>
 * 该类采用数据驱动设计，维护一个材料名称列表。
 * 这些材料名称会与{@link com.mss.polymech.api.item.ItemTagPrefix}组合，
 * 自动生成对应的物品（如steel_ingot、brass_ingot等）。
 * </p>
 * 
 * <h2>添加新材料：</h2>
 * <pre>{@code
 * // 在static块中添加新材料名称
 * MATERIAL_NAMES.add("copper");
 * 
 * // 系统会自动生成：
 * // - copper_ingot（如果INGOT前缀存在）
 * // - raw_copper（如果RAW_ORE前缀存在且shouldGenerate返回true）
 * }</pre>
 * 
 * <h2>材料命名规范：</h2>
 * <ul>
 *   <li>使用小写字母和下划线</li>
 *   <li>合金材料使用完整名称，如stainless_steel</li>
 *   <li>避免使用特殊字符和空格</li>
 * </ul>
 * 
 * @see com.mss.polymech.api.item.ModItemTypes
 * @see com.mss.polymech.item.ModItems
 */
public class MaterialRegistry {
    /** 存储所有已注册材料名称的列表 */
    private static final List<String> MATERIAL_NAMES = new ArrayList<>();

    static {
        // ========== 单质金属 ==========
        MATERIAL_NAMES.add("steel");           // 钢
        MATERIAL_NAMES.add("aluminium");       // 铝
        MATERIAL_NAMES.add("nickel");          // 镍
        MATERIAL_NAMES.add("tin");             // 锡
        MATERIAL_NAMES.add("zinc");            // 锌
        
        // ========== 合金 ==========
        MATERIAL_NAMES.add("brass");           // 黄铜（铜锌合金）
        MATERIAL_NAMES.add("bronze");          // 青铜（铜锡合金）
        MATERIAL_NAMES.add("ivar");            // 因瓦合金（铁镍合金）
        MATERIAL_NAMES.add("cupronickel");     // 白铜（铜镍合金）
        MATERIAL_NAMES.add("stainless_steel"); // 不锈钢
        
    }

    /*
     * 获取所有已注册的材料名称列表。
     * <p>
     * 返回不可修改的列表视图，防止外部代码意外修改注册表。
     * 该列表用于数据驱动的物品生成系统。
     * </p>
     * 
     * @return 所有材料名称的只读列表
     */
    public static List<String> getMaterialNames() {
        return Collections.unmodifiableList(MATERIAL_NAMES);
    }
}
