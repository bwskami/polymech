package com.mss.polymech.block;

import com.mss.polymech.Polymech;
import com.mss.polymech.item.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Polymech.MOD_ID);
    public static final DeferredBlock<Block> COKE_OVEN_BRICK =
            registerBlocks("coke_oven_brick", () -> new Block(Block.Properties.ofFullCopy(Blocks.STONE)
                    .requiresCorrectToolForDrops()));
    public static final DeferredBlock<Block> TEST_ORE =
            registerBlocks("test_ore", () -> new Block(Block.Properties.ofFullCopy(Blocks.STONE)
                    .requiresCorrectToolForDrops()));

    private static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private static  <T extends Block> DeferredBlock<T> registerBlocks(String name, Supplier<T> block) {
        DeferredBlock<T> blocks = BLOCKS.register(name, block);
        registerBlockItems(name, blocks);
        return blocks;
    }

    public static void register (IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
