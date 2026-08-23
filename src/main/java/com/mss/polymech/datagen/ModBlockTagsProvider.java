package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.worldgen.ModMinerals;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

public class ModBlockTagsProvider extends BlockTagsProvider {
    public ModBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider, @Nullable ExistingFileHelper existingFileHelper) {
        super(output, lookupProvider, Polymech.MOD_ID, existingFileHelper);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .add(ModBlocks.COKE_OVEN_BRICK.get())
                .add(ModBlocks.FLUID_TANK.get());

        // 矿石挖掘标签：全部矿石需镐开采；按开采等级划分石镐级/铁镐级
        var mineable = tag(BlockTags.MINEABLE_WITH_PICKAXE);
        var stoneTier = tag(BlockTags.NEEDS_STONE_TOOL);
        var ironTier = tag(BlockTags.NEEDS_IRON_TOOL);
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            var oreSet = ModBlocks.MINERAL_ORES.get(def.mineral());
            if (oreSet == null) continue;
            // 全部岩种变体（石头/深板岩/21种群峦岩种）
            var tierTag = def.tier() == ModMinerals.ToolTier.IRON ? ironTier : stoneTier;
            for (var oreBlock : oreSet.all()) {
                mineable.add(oreBlock.get());
                tierTag.add(oreBlock.get());
            }
        }

        // 区域岩石：镐可开采；并加入stone_ore_replaceables——
        // 既使原版/模组散矿能生成其中，也使GT矿脉Feature（按该标签判定宿主）能替换它们
        var oreReplaceables = tag(BlockTags.STONE_ORE_REPLACEABLES);
        for (var rock : ModBlocks.ROCKS.values()) {
            mineable.add(rock.get());
            oreReplaceables.add(rock.get());
        }
    }
}
