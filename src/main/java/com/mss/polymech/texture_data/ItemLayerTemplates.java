package com.mss.polymech.texture_data;

import java.util.List;

public enum ItemLayerTemplates {
    INGOT(
            "item/material_sets/ingot/ingot",
            "item/material_sets/ingot/ingot_secondary",
            "item/material_sets/ingot/ingot_overlay"
    ),
    ALLOY(
            "item/material_sets/alloy_ingot/alloy_ingot",
                    "item/material_sets/alloy_ingot/alloy_ingot_secondary",
                    "item/material_sets/alloy_ingot/alloy_ingot_overlay"
    ),
    PIPE_ITEM(
            "item/material_sets/pipe_item/pipe_item",
            "item/material_sets/pipe_item/pipe_item_secondary",
            "item/material_sets/pipe_item/pipe_item_overlay"
    ),
    SMALL_PIPE_ITEM(
            "item/material_sets/pipe_item/small_pipe_item",
            "item/material_sets/pipe_item/small_pipe_item_secondary",
            "item/material_sets/pipe_item/small_pipe_item_overlay"
    ),
    BIG_PIPE_ITEM(
            "item/material_sets/pipe_item/big_pipe_item",
            "item/material_sets/pipe_item/big_pipe_item_secondary",
            "item/material_sets/pipe_item/big_pipe_item_overlay"
    ),
    HUGE_PIPE_ITEM(
            "item/material_sets/pipe_item/huge_pipe_item",
            "item/material_sets/pipe_item/huge_pipe_item_secondary",
            "item/material_sets/pipe_item/huge_pipe_item_overlay"
    ),
    CONVEYOR_BELT_ITEM(
            "item/material_sets/conveyor_belt_item/conveyor_item",
            "item/material_sets/conveyor_belt_item/conveyor_item_secondary",
            "item/material_sets/conveyor_belt_item/conveyor_item_overlay",
            "item/material_sets/conveyor_belt_item/conveyor_item_thirdly"
    ),
    NUGGET(
            "item/material_sets/nugget/nugget",
            "item/material_sets/nugget/nugget_secondary",
            "item/material_sets/nugget/nugget_overlay"
    ),
    DUST(
            "item/material_sets/dust/dust",
            "item/material_sets/dust/dust_secondary",
            "item/material_sets/dust/dust_overlay"
    ),
    /**
     * 粗矿形态1（普通）：三层染色模板（主体/暗部/高光），按矿物配色染色。
     * 贴图素材为用户提供的 raw1.png / raw1_secondary / raw1_overlay。
     */
    RAW_ORE_1(
            "item/material_sets/raw_ore/raw1",
            "item/material_sets/raw_ore/raw1_secondary",
            "item/material_sets/raw_ore/raw1_overlay"
    ),
    /**
     * 粗矿形态2（层状）
     */
    RAW_ORE_2(
            "item/material_sets/raw_ore/raw2",
            "item/material_sets/raw_ore/raw2_secondary",
            "item/material_sets/raw_ore/raw2_overlay"
    ),
    /**
     * 粗矿形态3（斜向小块分段）
     */
    RAW_ORE_3(
            "item/material_sets/raw_ore/raw3",
            "item/material_sets/raw_ore/raw3_secondary",
            "item/material_sets/raw_ore/raw3_overlay"
    ),
    /**
     * 粗矿形态4（大块状）
     */
    RAW_ORE_4(
            "item/material_sets/raw_ore/raw4",
            "item/material_sets/raw_ore/raw4_secondary",
            "item/material_sets/raw_ore/raw4_overlay"
    ),
    /**
     * 粉碎矿：三层染色模板（主体/暗部/高光），按矿物配色染色。
     * 破碎机把粗矿破碎成粉碎矿（素材取自GregTech dull/crushed）。
     */
    CRUSHED(
            "item/material_sets/crushed/crushed",
            "item/material_sets/crushed/crushed_secondary",
            "item/material_sets/crushed/crushed_overlay"
    ),
    /**
     * 洗净矿：两层染色模板（主体/暗部），按矿物配色染色。
     * 跳汰机/洗矿机把粉碎矿洗选成洗净矿（素材取自GregTech dull/crushed_purified）。
     */
    PURIFIED(
            "item/material_sets/purified/purified",
            "item/material_sets/purified/purified_secondary"
    ),
    PLATE(
            "item/material_sets/ingot_plate/ingot_plate",
            "item/material_sets/ingot_plate/ingot_plate_secondary",
            "item/material_sets/ingot_plate/ingot_plate_overlay"
    ),
    FOIL(
            "item/material_sets/foil/foil",
            "item/material_sets/foil/foil_secondary",
            "item/material_sets/foil/foil_overlay"
    ),
    STICK(
            "item/material_sets/stick/stick",
            "item/material_sets/stick/stick_secondary",
            "item/material_sets/stick/stick_overlay"
    ),
    GEAR(
            "item/material_sets/gear/gear",
            "item/material_sets/gear/gear_secondary",
            "item/material_sets/gear/gear_overlay"
    ),
    SMALL_GEAR(
            "item/material_sets/small_gear/small_gear",
            "item/material_sets/small_gear/small_gear_secondary",
            "item/material_sets/small_gear/small_gear_overlay"
    ),
    SPRING(
            "item/material_sets/spring/spring",
            "item/material_sets/spring/spring_secondary",
            "item/material_sets/spring/spring_overlay"
    ),
    SCREW(
            "item/material_sets/screw/screw",
            "item/material_sets/screw/screw_secondary",
            "item/material_sets/screw/screw_overlay"
    ),
    BOLT(
            "item/material_sets/blot/bolt",
            "item/material_sets/blot/bolt_secondary",
            "item/material_sets/blot/bolt_overlay"
    ),
    RING(
            "item/material_sets/ring/ring",
            "item/material_sets/ring/ring_secondary",
            "item/material_sets/ring/ring_overlay"
    ),
    /**
     * 线材（电线/线缆）：三层染色模板，按金属材质染色。
     * 用于电线物品的贴图层渲染。
     */
    WIRE(
            "item/material_sets/wire/wire",
            "item/material_sets/wire/wire_secondary",
            "item/material_sets/wire/wire_overlay"
    ),
    /**
     * 线轴（满卷）：底层为空线轴（colors.json 中对应染色为 null 不染色），
     * 其上三层为线圈图层，按金属材质染色。
     */
    SPOOL(
            "item/material_sets/spool/empty_spool",
            "item/material_sets/spool/spool",
            "item/material_sets/spool/spool_secondary",
            "item/material_sets/spool/spool_overlay"
    ),
    /**
     * 绝缘线轴：在满卷线轴基础上追加一层绝缘标识（insulated_logo，不染色原样显示），
     * 用于在物品图标上区分绝缘线缆变体。
     * 注意：资源路径仅允许小写，贴图文件必须命名为 insulated_logo.png。
     */
    INSULATED_SPOOL(
            "item/material_sets/spool/empty_spool",
            "item/material_sets/spool/spool",
            "item/material_sets/spool/spool_secondary",
            "item/material_sets/spool/spool_overlay",
            "item/material_sets/spool/insulated_logo"
    ),
    /** 空线轴：仅底层空线轴图层，不染色 */
    EMPTY_SPOOL(
            "item/material_sets/spool/empty_spool"
    ),
    /**
     * 宝石/晶体：三层染色模板（主体/高光/暗部）。
     * 中间高光层 colors.json 中配置为 null（不染色保持白色光泽），
     * 与格雷宝石渲染一致。用于 {material}_gem 的物品图标。
     */
    GEM(
            "item/material_sets/gem/gem",
            "item/material_sets/gem/gem_overlay",
            "item/material_sets/gem/gem_secondary"
    );


    private final List<String> layerTextures; // 顺序存储每个图层的纹理路径（不含 .png）

    ItemLayerTemplates(String... layerTextures) {
        this.layerTextures = List.of(layerTextures);
    }

    public List<String> getLayerTextures() {
        return layerTextures;
    }

    public int getLayerCount() {
        return layerTextures.size();
    }
}