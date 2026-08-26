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
 * 在RAW_GENERATION阶段运行（早于一切矿石特征），不改变地形形状。
 * 参考 TFC RegionChunkDataGenerator.generateRock()：
 * 先检查方块类型，跳过空气/水/洞穴空腔，再计算岩种，
 * 避免对非石头方块做无用的 sampleAtLayer 调用。
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
        final int minBlockX = chunkPos.getMinBlockX();
        final int minBlockZ = chunkPos.getMinBlockZ();

        int replaced = 0;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minBlockX + lx;
                int z = minBlockZ + lz;
                int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                var biome = level.getBiome(pos.set(x, surface, z));

                int loopMin = Math.max(level.getMinBuildHeight(),
                        ModRocks.ROCK_LAYER_MIN_Y - ModRocks.TRANSITION_WIDTH);
                for (int y = loopMin; y <= surface; y++) {
                    // 先检查方块类型，跳过空气/水/洞穴空腔
                    pos.set(x, y, z);
                    BlockState host = level.getBlockState(pos);
                    if (!host.is(Blocks.STONE) && !host.is(Blocks.DEEPSLATE)) continue;

                    // 深层岩→深板岩过渡
                    if (!ModRocks.shouldUseModRock(y, levelSeed, x, z)) continue;

                    BlockState rock = ModRocks.rockTypeAt(x, z, y, levelSeed, biome, surface).blockState();
                    level.setBlock(pos, rock, 2);
                    replaced++;
                }
            }
        }
        return replaced > 0;
    }
}
