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