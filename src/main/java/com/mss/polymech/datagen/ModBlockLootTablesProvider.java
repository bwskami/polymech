package com.mss.polymech.datagen;

import com.mss.polymech.api.item.ModItemTypes;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.powergrid.ConnectorBlock;
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

import java.util.Set;

public class ModBlockLootTablesProvider extends BlockLootSubProvider {
    public ModBlockLootTablesProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        for (Block block : getKnownBlocks()) {
            // 连接器按格内数量(count)掉落对应个数，单独生成战利品表
            if (block == ModBlocks.CONNECTOR.get())
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
