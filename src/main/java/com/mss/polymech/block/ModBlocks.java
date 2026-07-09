package com.mss.polymech.block;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.PipeMaterial;
import com.mss.polymech.block.entity.FluidTankBlock;
import com.mss.polymech.item.ModItems;
import com.mss.polymech.item.PipeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.*;
import java.util.function.Supplier;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Polymech.MOD_ID);

    // ========== 非管道方块 ==========
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

    // ========== 管道方块：数据驱动批量注册 ==========
    private static final Map<PipeMaterial, Map<PipeBlock.PipeSize, DeferredBlock<PipeBlock>>> PIPE_TABLE_INTERNAL = new LinkedHashMap<>();
    public static final Map<PipeMaterial, Map<PipeBlock.PipeSize, DeferredBlock<PipeBlock>>> PIPE_TABLE;
    public static final List<DeferredBlock<PipeBlock>> PIPE_BLOCKS;

    static {
        List<DeferredBlock<PipeBlock>> allPipes = new ArrayList<>();
        for (PipeMaterial material : PipeMaterial.values()) {
            Map<PipeBlock.PipeSize, DeferredBlock<PipeBlock>> sizeMap = new LinkedHashMap<>();
            for (PipeBlock.PipeSize size : PipeBlock.PipeSize.values()) {
                String name = size.getRegistryName(material);
                DeferredBlock<PipeBlock> pipe = registerPipe(name,
                        () -> new PipeBlock(Block.Properties.of()
                                .strength(material.getStrength(), material.getResistance())
                                .sound(material.getSoundType())
                                .requiresCorrectToolForDrops()
                                .noOcclusion(),
                                size));
                sizeMap.put(size, pipe);
                allPipes.add(pipe);
            }
            PIPE_TABLE_INTERNAL.put(material, Collections.unmodifiableMap(sizeMap));
        }
        PIPE_TABLE = Collections.unmodifiableMap(PIPE_TABLE_INTERNAL);
        PIPE_BLOCKS = Collections.unmodifiableList(allPipes);
    }

    public static DeferredBlock<PipeBlock> getPipe(PipeMaterial material, PipeBlock.PipeSize size) {
        return PIPE_TABLE.get(material).get(size);
    }

    // ========== 注册工具方法 ==========
    private static <T extends Block> void registerBlockItems(String name, DeferredBlock<T> block) {
        ModItems.ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    private static <T extends Block> DeferredBlock<T> registerBlocks(String name, Supplier<T> block) {
        DeferredBlock<T> blocks = BLOCKS.register(name, block);
        registerBlockItems(name, blocks);
        return blocks;
    }

    private static DeferredBlock<PipeBlock> registerPipe(String name, Supplier<PipeBlock> block) {
        DeferredBlock<PipeBlock> pipe = BLOCKS.register(name, block);
        ModItems.ITEMS.register(name, () -> new PipeItem(pipe.get(), new Item.Properties()));
        return pipe;
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
    }
}
