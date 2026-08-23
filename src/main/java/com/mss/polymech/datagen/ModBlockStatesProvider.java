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
        generateOreBlocks();
        generateRockBlocks();

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

    /*
     * 矿石方块（格雷/群峦式岩种变体）：每种矿物×每种宿主岩一个方块状态/模型。
     * <p>
     * 模型为四层元素结构（OOP准则：岩石底图不染色）：
     * <ol>
     *   <li>底层完整立方体：岩石底图（原版石头/原版深板岩/群峦岩种贴图），
     *       tintindex 0 —— 岩种与石头不染色，深板岩染色（见colors.json的deepslate）</li>
     *   <li>矿石底图层：格雷ore贴图，按矿物主色染色（tintindex 1）</li>
     *   <li>矿石阴影层：格雷ore_layer2（ore_shadow）贴图，按矿物辅色染色（tintindex 2）</li>
     *   <li>矿石高光灯：格雷底图最亮像素提取（ore_highlight），不染色白色光泽（tintindex 3）</li>
     * </ol>
     * 矿石三层向外做微小偏移（0.01/0.02/0.03），避免与底层共面产生z-fighting。
     * </p>
     */
    private void generateOreBlocks() {
        // 矿石方块状态/物品模型/共享复合模型全部由客户端 OreDynamicResourcePack 运行时生成
        // （neoforge:composite：岩石底 solid + 矿石层 translucent，见该类注释）。
        // datagen 不再输出任何矿石模型文件。
    }

    /*
     * 区域岩石：每种岩种使用独立的彩色贴图（贴图取自TerraFirmaCraft，
     * 见TEXTURE_CREDITS.md），不再走"原版石头+染色"方案，因此无tintindex。
     */
    private void generateRockBlocks() {
        for (var entry : ModBlocks.ROCKS.entrySet()) {
            simpleBlockWithItem(entry.getValue().get(), buildRockModel(entry.getKey()));
        }
    }

    private ModelFile buildRockModel(String rockName) {
        return models().cubeAll(rockName, modLoc("block/rock/raw/" + rockName));
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
