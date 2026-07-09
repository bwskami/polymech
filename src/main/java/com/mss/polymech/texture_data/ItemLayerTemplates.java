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