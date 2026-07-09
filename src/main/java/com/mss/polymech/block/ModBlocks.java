package com.mss.polymech.block;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.entity.FluidTankBlock;
import com.mss.polymech.item.ModItems;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
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
    public static final DeferredBlock<FluidTankBlock> FLUID_TANK =
            registerBlocks("fluid_tank", () -> new FluidTankBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion()));
    
    // ========== 普通管道（铁/钢）==========
    public static final DeferredBlock<PipeBlock> PIPE =
            registerBlocks("pipe", () -> new PipeBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion(),
                    PipeBlock.PipeSize.NORMAL));

    public static final DeferredBlock<PipeBlock> SMALL_PIPE =
            registerBlocks("small_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(2.0F, 4.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.SMALL));

    public static final DeferredBlock<PipeBlock> BIG_PIPE =
            registerBlocks("big_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(4.0F, 8.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.BIG));

    public static final DeferredBlock<PipeBlock> HUGE_PIPE =
            registerBlocks("huge_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(5.0F, 10.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.HUGE));

    // ========== 青铜管道 ==========
    public static final DeferredBlock<PipeBlock> BRONZE_PIPE =
            registerBlocks("bronze_pipe", () -> new PipeBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion(),
                    PipeBlock.PipeSize.NORMAL));

    public static final DeferredBlock<PipeBlock> BRONZE_SMALL_PIPE =
            registerBlocks("bronze_small_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(2.0F, 4.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.SMALL));

    public static final DeferredBlock<PipeBlock> BRONZE_BIG_PIPE =
            registerBlocks("bronze_big_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(4.0F, 8.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.BIG));

    public static final DeferredBlock<PipeBlock> BRONZE_HUGE_PIPE =
            registerBlocks("bronze_huge_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(5.0F, 10.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.HUGE));

    // ========== 不锈钢管道 ==========
    public static final DeferredBlock<PipeBlock> STAINLESS_STEEL_PIPE =
            registerBlocks("stainless_steel_pipe", () -> new PipeBlock(Block.Properties.of()
                    .strength(4.0F, 8.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion(),
                    PipeBlock.PipeSize.NORMAL));

    public static final DeferredBlock<PipeBlock> STAINLESS_STEEL_SMALL_PIPE =
            registerBlocks("stainless_steel_small_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(3.0F, 6.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.SMALL));

    public static final DeferredBlock<PipeBlock> STAINLESS_STEEL_BIG_PIPE =
            registerBlocks("stainless_steel_big_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(5.0F, 10.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.BIG));

    public static final DeferredBlock<PipeBlock> STAINLESS_STEEL_HUGE_PIPE =
            registerBlocks("stainless_steel_huge_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(6.0F, 12.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.HUGE));

    // ========== 黄铜管道 ==========
    public static final DeferredBlock<PipeBlock> BRASS_PIPE =
            registerBlocks("brass_pipe", () -> new PipeBlock(Block.Properties.of()
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()
                    .noOcclusion(),
                    PipeBlock.PipeSize.NORMAL));

    public static final DeferredBlock<PipeBlock> BRASS_SMALL_PIPE =
            registerBlocks("brass_small_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(2.0F, 4.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.SMALL));

    public static final DeferredBlock<PipeBlock> BRASS_BIG_PIPE =
            registerBlocks("brass_big_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(4.0F, 8.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.BIG));

    public static final DeferredBlock<PipeBlock> BRASS_HUGE_PIPE =
            registerBlocks("brass_huge_pipe", () -> new PipeBlock(
                    Block.Properties.of()
                            .strength(5.0F, 10.0F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion(),
                    PipeBlock.PipeSize.HUGE));

    private static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private static <T extends Block> DeferredBlock<T> registerBlocks(String name, Supplier<T> block) {
        DeferredBlock<T> blocks = BLOCKS.register(name, block);
        registerBlockItems(name, blocks);
        return blocks;
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);

    }
}
