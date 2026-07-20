package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class ModBlockStatesProvider extends BlockStateProvider {
    public ModBlockStatesProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, Polymech.MOD_ID, exFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        simpleBlockWithItem(ModBlocks.COKE_OVEN_BRICK.get(), cubeAll(ModBlocks.COKE_OVEN_BRICK.get()));
        simpleBlockWithItem(ModBlocks.FLUID_TANK.get(), cubeAll(ModBlocks.FLUID_TANK.get()));

        generateConveyorBlockState();

        for (var materialEntry : ModBlocks.PIPE_TABLE.entrySet()) {
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                String templateName = getTemplateName(sizeEntry.getKey());
                generatePipeBlockState(sizeEntry.getValue().get(), templateName);
            }
        }
    }

    private void generateConveyorBlockState() {
        ModelFile conveyorModel = models().getExistingFile(
                modLoc("block/conveyor_belt/conveyor_belt"));

        getVariantBuilder(ModBlocks.CONVEYOR.get())
                .forAllStates(state -> {
                    Direction facing = state.getValue(ConveyorBlock.FACING);
                    return ConfiguredModel.builder()
                            .modelFile(conveyorModel)
                            .rotationY((int) (facing.toYRot() + 180) % 360)
                            .build();
                });
    }

    private String getTemplateName(PipeBlock.PipeSize size) {
        return switch (size) {
            case SMALL -> "template_small_pipe";
            case BIG   -> "template_big_pipe";
            case HUGE  -> "template_huge_pipe";
            default    -> "template_pipe";
        };
    }

    private void generatePipeBlockState(Block block, String modelName) {
        ModelFile pipeModel = models().getExistingFile(modLoc("block/pipes/" + modelName));
        simpleBlock(block, pipeModel);
    }
}
