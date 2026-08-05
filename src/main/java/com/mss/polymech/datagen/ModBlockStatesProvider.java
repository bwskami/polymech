package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.api.material.MaterialRegistry;
import com.mss.polymech.block.ConveyorBlock;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.block.PipeBlock;
import com.mss.polymech.fluid.ModFluids;
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

        // 蒸汽流体方块
        simpleBlock(ModFluids.STEAM_BLOCK.get(), models().getBuilder("steam")
                .texture("particle", modLoc("block/steam_still")));

        generateConveyorBlockStates();
        generateMaterialBlocks();

        for (var materialEntry : ModBlocks.PIPE_TABLE.entrySet()) {
            for (var sizeEntry : materialEntry.getValue().entrySet()) {
                String templateName = getTemplateName(sizeEntry.getKey());
                generatePipeBlockState(sizeEntry.getValue().get(), templateName);
            }
        }
    }

    private void generateConveyorBlockStates() {
        // 所有材质共用同一个染色模型，颜色由 colors.json + 方块颜色处理器决定
        ModelFile conveyorModel = models().getExistingFile(
                modLoc("block/conveyor_belt/conveyor_belt"));

        for (var entry : ModBlocks.CONVEYOR_TABLE.entrySet()) {
            getVariantBuilder(entry.getValue().get())
                    .forAllStates(state -> {
                        Direction facing = state.getValue(ConveyorBlock.FACING);
                        return ConfiguredModel.builder()
                                .modelFile(conveyorModel)
                                .rotationY((int) (facing.toYRot() + 180) % 360)
                                .build();
                    });
        }
    }

    /*
     * 金属存储块：所有材料共用两套染色模板模型，颜色由 colors.json + 方块颜色处理器决定。
     * 贴图选择标准：材料平均原子质量 >= MASS_THRESHOLD → block_normal，否则 block_heavy。
     */
    private void generateMaterialBlocks() {
        ModelFile normalModel = models().getExistingFile(modLoc("block/material_block_normal"));
        ModelFile heavyModel = models().getExistingFile(modLoc("block/material_block_heavy"));
        for (var entry : ModBlocks.MATERIAL_BLOCKS.entrySet()) {
            double mass = MaterialRegistry.getAtomicMass(entry.getKey());
            ModelFile model = mass >= ModBlocks.MASS_THRESHOLD ? normalModel : heavyModel;
            simpleBlockWithItem(entry.getValue().get(), model);
        }
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
