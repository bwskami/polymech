package com.mss.polymech.texture_data;

public enum MaterialSet {
    TEST("test", ItemLayerTemplates.INGOT, BlockLayerTemplates.PIPE),
    ALUMINIUM("aluminium", ItemLayerTemplates.INGOT, BlockLayerTemplates.PIPE),
    NICKEL("nickel", ItemLayerTemplates.INGOT, BlockLayerTemplates.PIPE),
    TIN("tin", ItemLayerTemplates.INGOT, BlockLayerTemplates.PIPE),
    ZINC("zinc", ItemLayerTemplates.INGOT, BlockLayerTemplates.PIPE),
    BRASS("brass", ItemLayerTemplates.ALLOY, BlockLayerTemplates.PIPE),
    BRONZE("bronze", ItemLayerTemplates.ALLOY, BlockLayerTemplates.PIPE),
    IVAR("ivar", ItemLayerTemplates.ALLOY, BlockLayerTemplates.PIPE),
    CUPRONICKEL("cupronickel", ItemLayerTemplates.ALLOY, BlockLayerTemplates.PIPE),
    STAINLESS_STEEL("stainless_steel", ItemLayerTemplates.ALLOY, BlockLayerTemplates.PIPE);

    private final String materialName;
    private final ItemLayerTemplates itemTemplate;
    private final BlockLayerTemplates blockTemplate;

    MaterialSet(String materialName, ItemLayerTemplates itemTemplate, BlockLayerTemplates blockTemplate) {
        this.materialName = materialName;
        this.itemTemplate = itemTemplate;
        this.blockTemplate = blockTemplate;
    }

    public String getMaterialName() { return materialName; }
    public ItemLayerTemplates getItemTemplate() { return itemTemplate; }
    public BlockLayerTemplates getBlockTemplate() { return blockTemplate; }

    public static MaterialSet byName(String name) {
        for (MaterialSet ms : values()) {
            if (ms.materialName.equals(name)) return ms;
        }
        return null;
    }
}
