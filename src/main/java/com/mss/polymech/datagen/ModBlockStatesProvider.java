package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModBlockStatesProvider extends BlockStateProvider {
    public ModBlockStatesProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, Polymech.MOD_ID,exFileHelper);
    }

    private void customBlockWithItem(Block block, String modelName) {
        simpleBlockWithItem(block, models().getExistingFile(modLoc("block/" + modelName)));
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlockWithItem(ModBlocks.COKE_OVEN_BRICK.get(), cubeAll(ModBlocks.COKE_OVEN_BRICK.get()));
        simpleBlockWithItem(ModBlocks.TEST_ORE.get(), cubeAll(ModBlocks.TEST_ORE.get()));
        simpleBlockWithItem(ModBlocks.FLUID_TANK.get(), cubeAll(ModBlocks.FLUID_TANK.get()));

        // 普通管道（铁/钢）
        generatePipeBlockState(ModBlocks.PIPE.get(), "template_pipe");
        generatePipeBlockState(ModBlocks.SMALL_PIPE.get(), "template_small_pipe");
        generatePipeBlockState(ModBlocks.BIG_PIPE.get(), "template_big_pipe");
        generatePipeBlockState(ModBlocks.HUGE_PIPE.get(), "template_huge_pipe");
        
        // 青铜管道 - 复用 template 模型
        generatePipeBlockState(ModBlocks.BRONZE_PIPE.get(), "template_pipe");
        generatePipeBlockState(ModBlocks.BRONZE_SMALL_PIPE.get(), "template_small_pipe");
        generatePipeBlockState(ModBlocks.BRONZE_BIG_PIPE.get(), "template_big_pipe");
        generatePipeBlockState(ModBlocks.BRONZE_HUGE_PIPE.get(), "template_huge_pipe");
        
        // 不锈钢管道 - 复用 template 模型
        generatePipeBlockState(ModBlocks.STAINLESS_STEEL_PIPE.get(), "template_pipe");
        generatePipeBlockState(ModBlocks.STAINLESS_STEEL_SMALL_PIPE.get(), "template_small_pipe");
        generatePipeBlockState(ModBlocks.STAINLESS_STEEL_BIG_PIPE.get(), "template_big_pipe");
        generatePipeBlockState(ModBlocks.STAINLESS_STEEL_HUGE_PIPE.get(), "template_huge_pipe");
        
        // 黄铜管道 - 复用 template 模型
        generatePipeBlockState(ModBlocks.BRASS_PIPE.get(), "template_pipe");
        generatePipeBlockState(ModBlocks.BRASS_SMALL_PIPE.get(), "template_small_pipe");
        generatePipeBlockState(ModBlocks.BRASS_BIG_PIPE.get(), "template_big_pipe");
        generatePipeBlockState(ModBlocks.BRASS_HUGE_PIPE.get(), "template_huge_pipe");
    }
    
    private void generatePipeBlockState(Block block, String modelName) {
        ModelFile pipeModel = models().getExistingFile(modLoc("block/pipes/" + modelName));
        
        simpleBlock(block, pipeModel);
        
    }
}
