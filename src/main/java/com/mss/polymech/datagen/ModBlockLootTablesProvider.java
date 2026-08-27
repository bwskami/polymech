package com.mss.polymech.datagen;

import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.powergrid.ConnectorBlock;
import com.mss.polymech.worldgen.ModMinerals;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
import net.minecraft.world.level.storage.loot.functions.ApplyBonusCount;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;

import java.util.HashSet;
import java.util.Set;

public class ModBlockLootTablesProvider extends BlockLootSubProvider {
    public ModBlockLootTablesProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        // 收集矿石方块集合（全部岩种变体）：按产物类型掉落，而非掉落自身
        Set<Block> oreBlocks = new HashSet<>();
        for (var oreSet : ModBlocks.MINERAL_ORES.values()) {
            for (var oreBlock : oreSet.all()) {
                oreBlocks.add(oreBlock.get());
            }
        }

        for (Block block : getKnownBlocks()) {
            // 连接器按格内数量(count)掉落对应个数，单独生成战利品表
            if (block == ModBlocks.CONNECTOR.get())
                continue;
            // 矿石掉落粗矿（时运加成），单独生成战利品表
            if (oreBlocks.contains(block))
                continue;
            // 地表碎石无掉落（.noLootTable()）
            if (block instanceof com.mss.polymech.block.SurfaceRockBlock)
                continue;
            dropSelf(block);
        }

        // 连接器：同原版海泡菜语义，破坏时按 count 掉落对应数量的连接器物品
        // 顺序同原版海泡菜：先 set_count 按 state 固定数量，再 explosion_decay 做爆炸衰减
        Block connector = ModBlocks.CONNECTOR.get();
        var entry = LootItem.lootTableItem(connector);
        for (int c = 1; c <= ConnectorBlock.MAX_COUNT; c++) {
            final int count = c;
            entry = entry.apply(SetItemCountFunction.setCount(ConstantValue.exactly(count))
                    .when(LootItemBlockStatePropertyCondition.hasBlockStateProperties(connector)
                            .setProperties(StatePropertiesPredicate.Builder.properties()
                                    .hasProperty(ConnectorBlock.COUNT, count))));
        }
        entry = this.applyExplosionDecay(connector, entry);
        add(connector, LootTable.lootTable()
                .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1)).add(entry)));

        // 矿石：全部岩种变体按产物类型掉落（基础1个，时运加成，精准采集掉自身，爆炸衰减）
        //   METAL→粗矿物raw_{mineral}、GEM→宝石{metal}_gem、
        //   DUST→粉末{metal}_dust、COAL→原版煤炭
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            var oreSet = ModBlocks.MINERAL_ORES.get(def.mineral());
            if (oreSet == null) continue;
            Item dropItem = switch (def.kind()) {
                case METAL -> {
                    var rawItem = ModItems.getRawMineral(def.mineral());
                    yield rawItem != null ? rawItem.get() : null;
                }
                case GEM -> {
                    var gem = ModItems.getMaterialItem(ModItemTypes.GEM, def.metal());
                    yield gem != null ? gem.get() : null;
                }
                case DUST -> {
                    // 红石矿直接掉原版红石粉（材料系统无redstone粉）
                    if ("redstone".equals(def.metal())) {
                        yield net.minecraft.world.item.Items.REDSTONE;
                    }
                    var dust = ModItems.getMaterialItem(ModItemTypes.DUST, def.metal());
                    yield dust != null ? dust.get() : null;
                }
                case COAL -> net.minecraft.world.item.Items.COAL;
            };
            if (dropItem == null) continue;
            for (var oreBlock : oreSet.all()) {
                add(oreBlock.get(), createCopperOreLikeDrops(oreBlock.get(), dropItem, 1, 1));
            }
        }
    }

    protected LootTable.Builder createCopperOreLikeDrops(Block block, Item item, float min, float max) {
        HolderLookup.RegistryLookup<Enchantment> registryLookup = this.registries.lookupOrThrow(Registries.ENCHANTMENT);
        return this.createSilkTouchDispatchTable(
                block,
                (LootPoolEntryContainer.Builder<?>) this.applyExplosionDecay(
                        block,
                        LootItem.lootTableItem(item)
                                .apply(SetItemCountFunction.setCount(UniformGenerator.between(min, max)))
                                .apply(ApplyBonusCount.addOreBonusCount(registryLookup.getOrThrow(Enchantments.FORTUNE)))
                )
        );
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ModBlocks.BLOCKS.getEntries().stream()
                .map(Holder::value)
                .toList();
    }
}
