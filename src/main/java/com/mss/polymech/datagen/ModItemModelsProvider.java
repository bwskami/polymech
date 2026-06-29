package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.ModItems;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModItemModelsProvider extends ItemModelProvider {
    public ModItemModelsProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, Polymech.MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        basicItem(ModItems.STEEL_INGOT.get());
        basicItem(ModItems.TEST_ITEM1.get());
        basicItem(ModItems.TEST_ITEM2.get());
        basicItem(ModItems.TEST_ITEM3.get());
        basicItem(ModItems.TEST_RAW.get());

    }
}
