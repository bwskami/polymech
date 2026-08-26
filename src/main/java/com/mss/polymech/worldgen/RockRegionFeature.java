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
 * 区域岩层替换特征：把当前区块内的原版石头/深层石替换为岩区岩种。
 * <p>
 * 在RAW_GENERATION阶段运行（早于一切矿石特征），不改变地形形状：
 * 洞穴在区块生成阶段已雕好，这里替换石头时洞壁自然露出岩层。
 * 岩种按 (x, z, y) 查询（{@link ModRocks#rockTypeAt}，噪声驱动的大尺度岩区
 * + 浅/中/深三层垂直岩层），因此岩区边界可以穿过区块内部，
 * 同一列在不同深度也能看到不同岩层。从{@link ModRocks#ROCK_LAYER_MIN_Y}
 * 替换到地表高度；最低深层石带（Y&lt;ROCK_LAYER_MIN_Y）保持原版深层石。
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
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos surfacePos = new BlockPos(x, surface, z);
                var biome = level.getBiome(surfacePos);

                int loopMin = Math.max(level.getMinBuildHeight(),
                        ModRocks.ROCK_LAYER_MIN_Y - ModRocks.TRANSITION_WIDTH);
                for (int y = loopMin; y <= surface; y++) {
                    // 深层岩→深板岩过渡：只有 shouldUseModRock 为 true 才放模组岩，否则保留深板岩
                    if (!ModRocks.shouldUseModRock(y, levelSeed, x, z)) continue;

                    // 岩种按 (x,z,y) + 群系 由噪声 + 垂直层决定：不同生物群系呈现不同岩族
                    BlockState rock = ModRocks.rockTypeAt(x, z, y, levelSeed, biome).blockState();
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState host = level.getBlockState(pos);
                    // 浅层替换石头；中层/深层按当前深岩范围替换对应石头或深层石
                    if (host.is(Blocks.STONE) || host.is(Blocks.DEEPSLATE)) {
                        level.setBlock(pos, rock, 2);
                        replaced++;
                    }
                }
            }
        }
        return replaced > 0;
    }
}
