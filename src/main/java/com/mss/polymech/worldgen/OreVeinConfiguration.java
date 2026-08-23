package com.mss.polymech.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.List;
import java.util.Optional;

/*
 * 矿脉特征配置：描述一条矿脉的分布、形状、密度、宿主与矿物组成。
 * <p>
 * 由datagen写入worldgen/configured_feature/vein_*.json，
 * 运行时由{@link OreVeinFeature}消费。
 * </p>
 * <p>
 * 分布参数（rarity/minY/maxY/seed）全部在配置内（群峦式）：
 * 矿脉中心由(世界种子, 中心区块, seed)确定性掷骰产生，不依赖
 * 放置修饰器——放置特征不带任何修饰器，每区块执行一次，
 * 特征自行扫描候选中心区块实现跨区块生成。
 * </p>
 *
 * @param rarity 稀有度：平均每rarity个中心区块出现一条该矿脉
 * @param minY 矿脉中心高度下限（椭球垂直半径会把中心向内收缩，避免裁切）
 * @param maxY 矿脉中心高度上限
 * @param seed 矿脉种子：由矿脉ID哈希而来，区分不同矿脉的掷骰序列
 * @param size 矿脉水平半径（椭球半轴）
 * @param density 成矿密度：椭球内每个方块被替换的概率
 * @param blocks 允许的宿主方块列表：放置期矿脉中心岩种必须在列表内，
 *               替换期也只有这些方块会被替换成矿石，
 *               矿体因此被岩区边界自然裁切（群峦式两级宿主过滤）
 * @param primary 下层主矿
 * @param secondary 上层次矿
 * @param between 中间夹层伴生矿（可空）
 * @param sporadic 全域零星散布矿（可空）
 */
public record OreVeinConfiguration(
        int rarity,
        int minY,
        int maxY,
        long seed,
        int size,
        float density,
        List<Block> blocks,
        OreEntry primary,
        OreEntry secondary,
        Optional<OreEntry> between,
        Optional<OreEntry> sporadic
) implements FeatureConfiguration {

    public static final Codec<OreVeinConfiguration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.intRange(1, 1000).fieldOf("rarity").forGetter(OreVeinConfiguration::rarity),
            // 高度范围放宽：群峦高山矿脉可到y300，深层矿脉中心可到y-80
            Codec.intRange(-128, 512).fieldOf("min_y").forGetter(OreVeinConfiguration::minY),
            Codec.intRange(-128, 512).fieldOf("max_y").forGetter(OreVeinConfiguration::maxY),
            Codec.LONG.fieldOf("seed").forGetter(OreVeinConfiguration::seed),
            Codec.intRange(1, 32).fieldOf("size").forGetter(OreVeinConfiguration::size),
            Codec.floatRange(0.0F, 1.0F).fieldOf("density").forGetter(OreVeinConfiguration::density),
            BuiltInRegistries.BLOCK.byNameCodec().listOf().fieldOf("blocks").forGetter(OreVeinConfiguration::blocks),
            OreEntry.CODEC.fieldOf("primary").forGetter(OreVeinConfiguration::primary),
            OreEntry.CODEC.fieldOf("secondary").forGetter(OreVeinConfiguration::secondary),
            OreEntry.CODEC.optionalFieldOf("between").forGetter(OreVeinConfiguration::between),
            OreEntry.CODEC.optionalFieldOf("sporadic").forGetter(OreVeinConfiguration::sporadic)
    ).apply(instance, OreVeinConfiguration::new));

    /** 宿主方块是否允许被本矿脉替换 */
    public boolean isHost(Block block) {
        return blocks.contains(block);
    }
}
