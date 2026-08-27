package com.mss.polymech.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.List;
import java.util.Optional;

public record OreVeinConfiguration(
        int rarity,
        int minY,
        int maxY,
        long seed,
        int sizeMin,
        int sizeMax,
        float density,
        List<Block> blocks,
        OreEntry primary,
        OreEntry secondary,
        Optional<OreEntry> between,
        Optional<OreEntry> sporadic,
        String shape,
        Optional<IndicatorConfig> indicator,
        boolean projectToSurface,
        boolean projectOffset
) implements FeatureConfiguration {

    /** \u5730\u8868/\u5730\u4e0b\u6307\u793a\u7269\u914d\u7f6e\uff08\u7fa4\u5ce6\u5f0f\uff09 */
    public record IndicatorConfig(
            int surfaceRarity,
            int depth,
            int undergroundRarity,
            int undergroundCount,
            OreEntry block,
            int indicatorRadius,
            float indicatorDensity
    ) {
        public static final Codec<IndicatorConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("surface_rarity").forGetter(IndicatorConfig::surfaceRarity),
                Codec.INT.fieldOf("depth").forGetter(IndicatorConfig::depth),
                Codec.INT.fieldOf("underground_rarity").forGetter(IndicatorConfig::undergroundRarity),
                Codec.INT.fieldOf("underground_count").forGetter(IndicatorConfig::undergroundCount),
                OreEntry.CODEC.fieldOf("block").forGetter(IndicatorConfig::block),
                Codec.INT.optionalFieldOf("radius", 3).forGetter(IndicatorConfig::indicatorRadius),
                Codec.FLOAT.optionalFieldOf("density", 0.15F).forGetter(IndicatorConfig::indicatorDensity)
        ).apply(i, IndicatorConfig::new));
    }

    public static final Codec<OreVeinConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, 1000).fieldOf("rarity").forGetter(OreVeinConfiguration::rarity),
            Codec.intRange(-128, 512).fieldOf("min_y").forGetter(OreVeinConfiguration::minY),
            Codec.intRange(-128, 512).fieldOf("max_y").forGetter(OreVeinConfiguration::maxY),
            Codec.LONG.fieldOf("seed").forGetter(OreVeinConfiguration::seed),
            Codec.intRange(1, 64).fieldOf("size_min").forGetter(OreVeinConfiguration::sizeMin),
            Codec.intRange(1, 64).fieldOf("size_max").forGetter(OreVeinConfiguration::sizeMax),
            Codec.floatRange(0.0F, 1.0F).fieldOf("density").forGetter(OreVeinConfiguration::density),
            BuiltInRegistries.BLOCK.byNameCodec().listOf().fieldOf("blocks").forGetter(OreVeinConfiguration::blocks),
            OreEntry.CODEC.fieldOf("primary").forGetter(OreVeinConfiguration::primary),
            OreEntry.CODEC.fieldOf("secondary").forGetter(OreVeinConfiguration::secondary),
            OreEntry.CODEC.optionalFieldOf("between").forGetter(OreVeinConfiguration::between),
            OreEntry.CODEC.optionalFieldOf("sporadic").forGetter(OreVeinConfiguration::sporadic),
            Codec.STRING.optionalFieldOf("shape", "ELLIPSOID").forGetter(OreVeinConfiguration::shape),
            IndicatorConfig.CODEC.optionalFieldOf("indicator").forGetter(OreVeinConfiguration::indicator),
            Codec.BOOL.optionalFieldOf("project", false).forGetter(OreVeinConfiguration::projectToSurface),
            Codec.BOOL.optionalFieldOf("project_offset", false).forGetter(OreVeinConfiguration::projectOffset)
    ).apply(instance, OreVeinConfiguration::new));

    public boolean isHost(Block block) { return blocks.contains(block); }
    public int size(RandomSource random) { return sizeMax <= sizeMin ? sizeMin : sizeMin + random.nextInt(sizeMax - sizeMin + 1); }
    public int size() { return (sizeMin + sizeMax) / 2; }
}
