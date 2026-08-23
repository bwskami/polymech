package com.mss.polymech.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/*
 * 区域岩层替换特征：把当前区块内的原版石头替换为岩区岩种。
 * <p>
 * 在RAW_GENERATION阶段运行（早于一切矿石特征），不改变地形形状：
 * 洞穴在区块生成阶段已雕好，这里替换石头时洞壁自然露出岩层。
 * 岩种按列查询（{@link ModRocks#rockTypeAtBlock}，噪声驱动的大尺度岩区），
 * 因此岩区边界可以穿过区块内部；每列从{@link ModRocks#ROCK_LAYER_MIN_Y}
 * 替换到地表高度，深层石带（Y&lt;-8以下）保持原版深层石不受影响。
 * </p>
 *
 * @see ModRocks
 */
public class RockRegionFeature extends Feature<NoneFeatureConfiguration> {

    public RockRegionFeature() {
        super(NoneFeatureConfiguration.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos chunkPos = new ChunkPos(context.origin());
        long levelSeed = level.getSeed();

        int replaced = 0;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = chunkPos.getMinBlockX() + lx;
                int z = chunkPos.getMinBlockZ() + lz;
                // 岩种按列由噪声决定：同一岩区内的相邻列岩种一致，边界处自然过渡
                BlockState rock = ModRocks.rockTypeAtBlock(x, z, levelSeed).blockState();
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);

                for (int y = ModRocks.ROCK_LAYER_MIN_Y; y <= surface; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (level.getBlockState(pos).is(Blocks.STONE)) {
                        level.setBlock(pos, rock, 2);
                        replaced++;
                    }
                }
            }
        }
        return replaced > 0;
    }
}
