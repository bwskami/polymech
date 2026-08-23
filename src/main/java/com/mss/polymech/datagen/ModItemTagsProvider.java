package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.worldgen.ModMinerals;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public class ModItemTagsProvider extends ItemTagsProvider {
    public ModItemTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, CompletableFuture<TagLookup<Block>> blockTags, @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, blockTags, Polymech.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // 粗矿物 → c:raw_materials + c:raw_materials/{mineral}
        // 矿石方块物品 → c:ores + c:ores/{mineral} + c:ores/{metal}（金属别名，跨模组配方兼容）
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            String mineral = def.mineral();

            var rawItem = ModItems.getRawMineral(mineral);
            if (rawItem != null) {
                tag(Tags.Items.RAW_MATERIALS).add(rawItem.get());
                tag(mineralItemTag("raw_materials/" + mineral)).add(rawItem.get());
            }

            var oreSet = ModBlocks.MINERAL_ORES.get(mineral);
            if (oreSet != null) {
                var oreTag = tag(Tags.Items.ORES);
                var mineralOreTag = tag(mineralItemTag("ores/" + mineral));
                var metalOreTag = tag(mineralItemTag("ores/" + def.metal()));
                // 全部岩种变体（石头/深板岩/21种群峦岩种）
                for (var oreBlockHolder : oreSet.all()) {
                    Item item = oreBlockHolder.get().asItem();
                    oreTag.add(item);
                    mineralOreTag.add(item);
                    metalOreTag.add(item);
                }
            }
        }

        // 矿物加工中间产物 → c:crushed_ores + c:purified_ores（跨模组选矿配方兼容）
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            if (def.kind() == ModMinerals.ProductKind.COAL) continue;
            var crushed = ModItems.getMineralItem(com.mss.polymech.api.item.ModItemTypes.CRUSHED, def.mineral());
            var purified = ModItems.getMineralItem(com.mss.polymech.api.item.ModItemTypes.PURIFIED, def.mineral());
            if (crushed != null) {
                tag(mineralItemTag("crushed_ores")).add(crushed.get());
                tag(mineralItemTag("crushed_ores/" + def.mineral())).add(crushed.get());
            }
            if (purified != null) {
                tag(mineralItemTag("purified_ores")).add(purified.get());
                tag(mineralItemTag("purified_ores/" + def.mineral())).add(purified.get());
            }
        }

        // 宝石 → c:gems + c:gems/{gem}
        for (String gem : com.mss.polymech.api.material.GemMaterials.getGems()) {
            var gemItem = ModItems.getMaterialItem(com.mss.polymech.api.item.ModItemTypes.GEM, gem);
            if (gemItem != null) {
                tag(Tags.Items.GEMS).add(gemItem.get());
                tag(mineralItemTag("gems/" + gem)).add(gemItem.get());
            }
        }
    }

    /** c 命名空间下的矿物约定标签（如 c:ores/cassiterite、c:raw_materials/cassiterite） */
    private static TagKey<Item> mineralItemTag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", path));
    }
}
