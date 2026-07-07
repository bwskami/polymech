package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
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

        ModelFile pipeCore = models().getExistingFile(modLoc("block/pipes/template_pipe_core"));
        ModelFile pipeArm = models().getExistingFile(modLoc("block/pipes/template_pipe_arm"));
        
        generatePipeMultipart(ModBlocks.PIPE.get(), pipeCore, pipeArm);
        
        simpleBlockItem(ModBlocks.PIPE.get(), pipeCore);

        ModelFile smallPipeCore = models().getExistingFile(modLoc("block/pipes/template_small_pipe_core"));
        ModelFile smallPipeArm = models().getExistingFile(modLoc("block/pipes/template_small_pipe_arm"));
        
        generatePipeMultipart(ModBlocks.SMALL_PIPE.get(), smallPipeCore, smallPipeArm);
        
        simpleBlockItem(ModBlocks.SMALL_PIPE.get(), smallPipeCore);

        ModelFile bigPipeCore = models().getExistingFile(modLoc("block/pipes/template_big_pipe_core"));
        ModelFile bigPipeArm = models().getExistingFile(modLoc("block/pipes/template_big_pipe_arm"));
        
        generatePipeMultipart(ModBlocks.BIG_PIPE.get(), bigPipeCore, bigPipeArm);
        
        simpleBlockItem(ModBlocks.BIG_PIPE.get(), bigPipeCore);

        ModelFile hugePipeCore = models().getExistingFile(modLoc("block/pipes/template_huge_pipe_core"));
        ModelFile hugePipeArm = models().getExistingFile(modLoc("block/pipes/template_huge_pipe_arm"));
        
        generatePipeMultipart(ModBlocks.HUGE_PIPE.get(), hugePipeCore, hugePipeArm);
        
        simpleBlockItem(ModBlocks.HUGE_PIPE.get(), hugePipeCore);
    }
    
    private void generatePipeMultipart(Block block, ModelFile core, ModelFile arm) {
        var builder = getMultipartBuilder(block);
        
        builder.part().modelFile(core).addModel().end();
        
        builder.part().modelFile(arm).rotationY(0).addModel()
                .condition(PipeBlock.NORTH, true).end();
        builder.part().modelFile(arm).rotationY(180).addModel()
                .condition(PipeBlock.SOUTH, true).end();
        builder.part().modelFile(arm).rotationY(90).addModel()
                .condition(PipeBlock.EAST, true).end();
        builder.part().modelFile(arm).rotationY(270).addModel()
                .condition(PipeBlock.WEST, true).end();
        builder.part().modelFile(arm).rotationX(270).addModel()
                .condition(PipeBlock.UP, true).end();
        builder.part().modelFile(arm).rotationX(90).addModel()
                .condition(PipeBlock.DOWN, true).end();
    }
}
