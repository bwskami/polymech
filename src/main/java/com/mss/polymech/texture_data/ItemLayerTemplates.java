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