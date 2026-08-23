package com.mss.polymech.datagen;

import com.mss.polymech.Polymech;
import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.worldgen.ModFeatures;
import com.mss.polymech.worldgen.ModMinerals;
import com.mss.polymech.worldgen.ModRocks;
import com.mss.polymech.worldgen.ModVeins;
import com.mss.polymech.worldgen.OreEntry;
import com.mss.polymech.worldgen.OreVeinConfiguration;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistrySetBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.neoforged.neoforge.common.data.DatapackBuiltinEntriesProvider;
import net.neoforged.neoforge.common.world.BiomeModifiers;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/*
 * 世界生成数据提供器（服务端datagen）：矿脉系统 + 区域岩层 + 散矿保底。
 *
 * <h2>设计取向（群峦式分布 + 格雷式组成）：</h2>
 * <ul>
 *   <li>岩区：低频值噪声驱动的生物群系尺度区域（{@link ModRocks}），非方形网格</li>
 *   <li>矿脉分布：群峦式跨区块生成——放置特征不带修饰器、每区块执行一次，
 *       特征扫描候选中心区块按(世界种子, 区块坐标, 矿脉种子)确定性掷骰，
 *       矿体按区块切片放置、跨区块无缝衔接；宿主岩约束两级过滤
 *       （中心岩区不对整条作废 + 替换期逐方块过滤），矿体被岩区边界自然裁切</li>
 *   <li>矿脉组成：主矿/次矿/夹层/零星的格雷式共生结构，密度采样椭球形态</li>
 * </ul>
 *
 * <h2>生成阶段顺序（关键）：</h2>
 * <ol>
 *   <li>RAW_GENERATION：岩层替换（RockRegionFeature把石头换成区域岩石）</li>
 *   <li>UNDERGROUND_ORES：矿脉（OreVeinFeature，只替换宿主岩）
 *       + 锡石/闪锌矿散矿保底</li>
 * </ol>
 * 岩层先行、矿脉随后，矿脉才能在区域岩石中生成。
 *
 * <h2>产出文件：</h2>
 * <ul>
 *   <li>worldgen/configured_feature/vein_{id}.json、placed_feature/vein_{id}.json</li>
 *   <li>worldgen/configured_feature/rock_regions.json、placed_feature/rock_regions.json</li>
 *   <li>worldgen/configured_feature/{mineral}_ore.json（散矿，仅锡石/闪锌矿）</li>
 *   <li>neoforge/biome_modifier/add_*.json</li>
 * </ul>
 * 新增矿脉/矿物只需在{@link ModVeins}/{@link ModMinerals}中追加定义，重跑runData即可。
 */
public class ModWorldGenProvider extends DatapackBuiltinEntriesProvider {

    /** 动态注册表内容构建器：三个世界生成注册表各挂一个bootstrap */
    private static final RegistrySetBuilder BUILDER = new RegistrySetBuilder()
            .add(Registries.CONFIGURED_FEATURE, ModWorldGenProvider::bootstrapConfiguredFeatures)
            .add(Registries.PLACED_FEATURE, ModWorldGenProvider::bootstrapPlacedFeatures)
            .add(NeoForgeRegistries.Keys.BIOME_MODIFIERS, ModWorldGenProvider::bootstrapBiomeModifiers);

    public ModWorldGenProvider(PackOutput output, CompletableFuture<net.minecraft.core.HolderLookup.Provider> registries) {
        super(output, registries, BUILDER, Set.of(Polymech.MOD_ID));
    }

    // ==================== 配置特征 ====================

    private static void bootstrapConfiguredFeatures(BootstrapContext<ConfiguredFeature<?, ?>> context) {
        // 1. 矿脉：每条矿脉定义一个配置特征。
        //    分布参数（rarity/min_y/max_y/seed）全部进配置（群峦式）：
        //    矿脉中心由(世界种子, 中心区块, seed)确定性掷骰，特征跨区块生成
        for (ModVeins.VeinDefinition vein : ModVeins.getDefinitions()) {
            List<Block> hosts = veinHostBlocks(vein);
            OreVeinConfiguration configuration = new OreVeinConfiguration(
                    vein.rarity(),
                    vein.minY(),
                    vein.maxY(),
                    hashVeinId(vein.id()),
                    vein.size(),
                    vein.density(),
                    hosts,
                    oreEntry(vein.primary(), hosts),
                    oreEntry(vein.secondary(), hosts),
                    Optional.ofNullable(vein.between()).map(m -> oreEntry(m, hosts)),
                    Optional.ofNullable(vein.sporadic()).map(m -> oreEntry(m, hosts)));

            context.register(
                    ResourceKey.create(Registries.CONFIGURED_FEATURE, veinConfiguredFeatureId(vein.id())),
                    new ConfiguredFeature<>(ModFeatures.ORE_VEIN.get(), configuration));
        }

        // 2. 区域岩层替换（无配置）
        context.register(
                ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "rock_regions")),
                new ConfiguredFeature<>(ModFeatures.ROCK_REGION.get(), NoneFeatureConfiguration.INSTANCE));

        // 3. 散矿保底（仅scatterCount>0的矿物：早期锡石/闪锌矿砂矿）
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            if (!def.hasScatter()) continue;
            var oreSet = ModBlocks.MINERAL_ORES.get(def.mineral());
            if (oreSet == null) continue;

            OreConfiguration configuration = new OreConfiguration(List.of(
                    OreConfiguration.target(
                            new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
                            oreSet.stone().get().defaultBlockState()),
                    OreConfiguration.target(
                            new TagMatchTest(BlockTags.DEEPSLATE_ORE_REPLACEABLES),
                            oreSet.deepslate().get().defaultBlockState())
            ), def.scatterSize());

            context.register(
                    ResourceKey.create(Registries.CONFIGURED_FEATURE, ModMinerals.configuredFeatureId(def.mineral())),
                    new ConfiguredFeature<>(Feature.ORE, configuration));
        }
    }

    // ==================== 放置特征 ====================

    private static void bootstrapPlacedFeatures(BootstrapContext<PlacedFeature> context) {
        var configuredFeatures = context.lookup(Registries.CONFIGURED_FEATURE);

        // 1. 矿脉（群峦式跨区块分布）：不带任何放置修饰器，每区块执行一次。
        //    稀有度骰子、位置与高度全部由特征内部按(世界种子, 区块坐标, 矿脉种子)
        //    确定性推导——这是矿脉能跨区块无缝衔接的前提（同群峦VeinFeature）
        for (ModVeins.VeinDefinition vein : ModVeins.getDefinitions()) {
            var configured = configuredFeatures.getOrThrow(
                    ResourceKey.create(Registries.CONFIGURED_FEATURE, veinConfiguredFeatureId(vein.id())));

            context.register(
                    ResourceKey.create(Registries.PLACED_FEATURE, veinPlacedFeatureId(vein.id())),
                    new PlacedFeature(configured, List.of()));
        }

        // 2. 区域岩层：每区块执行一次，无额外修饰器
        var rockConfigured = configuredFeatures.getOrThrow(
                ResourceKey.create(Registries.CONFIGURED_FEATURE, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "rock_regions")));
        context.register(
                ResourceKey.create(Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "rock_regions")),
                new PlacedFeature(rockConfigured, List.of()));

        // 3. 散矿保底：次数+扩散+三角形高度+生物群系过滤（同原版矿石范式）
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            if (!def.hasScatter()) continue;

            var configured = configuredFeatures.getOrThrow(
                    ResourceKey.create(Registries.CONFIGURED_FEATURE, ModMinerals.configuredFeatureId(def.mineral())));
            List<PlacementModifier> modifiers = List.of(
                    CountPlacement.of(def.scatterCount()),
                    InSquarePlacement.spread(),
                    HeightRangePlacement.triangle(
                            VerticalAnchor.absolute(def.scatterMinY()),
                            VerticalAnchor.absolute(def.scatterMaxY())),
                    BiomeFilter.biome());
            context.register(
                    ResourceKey.create(Registries.PLACED_FEATURE, ModMinerals.placedFeatureId(def.mineral())),
                    new PlacedFeature(configured, modifiers));
        }
    }

    // ==================== 生物群系修饰器 ====================

    private static void bootstrapBiomeModifiers(BootstrapContext<net.neoforged.neoforge.common.world.BiomeModifier> context) {
        var biomes = context.lookup(Registries.BIOME);
        var placedFeatures = context.lookup(Registries.PLACED_FEATURE);
        HolderSet.Named<net.minecraft.world.level.biome.Biome> overworld = biomes.getOrThrow(BiomeTags.IS_OVERWORLD);

        // 1. GT式矿脉：UNDERGROUND_ORES阶段注入全部主世界生物群系
        for (ModVeins.VeinDefinition vein : ModVeins.getDefinitions()) {
            var placed = placedFeatures.getOrThrow(
                    ResourceKey.create(Registries.PLACED_FEATURE, veinPlacedFeatureId(vein.id())));
            context.register(
                    ResourceKey.create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, veinBiomeModifierId(vein.id())),
                    new BiomeModifiers.AddFeaturesBiomeModifier(
                            overworld,
                            HolderSet.direct(placed),
                            GenerationStep.Decoration.UNDERGROUND_ORES));
        }

        // 2. 区域岩层：RAW_GENERATION阶段（必须先于矿脉）
        var rockPlaced = placedFeatures.getOrThrow(
                ResourceKey.create(Registries.PLACED_FEATURE, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "rock_regions")));
        context.register(
                ResourceKey.create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "add_rock_regions")),
                new BiomeModifiers.AddFeaturesBiomeModifier(
                        overworld,
                        HolderSet.direct(rockPlaced),
                        GenerationStep.Decoration.RAW_GENERATION));

        // 3. 散矿保底：UNDERGROUND_ORES阶段
        for (ModMinerals.MineralDefinition def : ModMinerals.getDefinitions()) {
            if (!def.hasScatter()) continue;
            var placed = placedFeatures.getOrThrow(
                    ResourceKey.create(Registries.PLACED_FEATURE, ModMinerals.placedFeatureId(def.mineral())));
            context.register(
                    ResourceKey.create(NeoForgeRegistries.Keys.BIOME_MODIFIERS, ModMinerals.biomeModifierId(def.mineral())),
                    new BiomeModifiers.AddFeaturesBiomeModifier(
                            overworld,
                            HolderSet.direct(placed),
                            GenerationStep.Decoration.UNDERGROUND_ORES));
        }
    }

    // ==================== 辅助方法 ====================

    /*
     * 矿脉种子：由矿脉ID稳定哈希而来（FNV-1a + Murmur收尾混合）。
     * 不同矿脉必须拿到不同的种子，否则中心掷骰序列会互相重合，
     * 各类矿脉的中心会永远挤在同一批区块里。
     */
    private static long hashVeinId(String veinId) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < veinId.length(); i++) {
            h ^= veinId.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /*
     * 矿脉宿主方块列表：矿脉定义允许的岩种方块；
     * 若矿脉高度下探到岩层底部以下（深层石带），额外允许深层石。
     * 空allowedRocks（不限岩种）时宿主为全部岩种。
     */
    private static List<Block> veinHostBlocks(ModVeins.VeinDefinition vein) {
        List<Block> hosts = new ArrayList<>();
        for (ModRocks.RockType rock : ModRocks.ROCK_TYPES) {
            if (vein.allowedRocks().isEmpty() || vein.allowedRocks().contains(rock.name())) {
                hosts.add(rock.block().get());
            }
        }
        if (vein.minY() < ModRocks.ROCK_LAYER_MIN_Y) {
            hosts.add(Blocks.DEEPSLATE);
        }
        return hosts;
    }

    /*
     * 矿物名 + 矿脉宿主方块列表 → 矿脉组成条目。
     * <p>
     * 为每个宿主方块挑选对应的岩种矿石变体（群峦式"看岩认矿"）：
     * 花岗岩宿主→{mineral}_granite_ore，深层石宿主→deepslate_{mineral}_ore，
     * 石头宿主→{mineral}_ore。矿脉替换宿主时按宿主查表落地变体。
     * </p>
     */
    private static OreEntry oreEntry(String mineral, List<Block> hosts) {
        var oreSet = ModBlocks.MINERAL_ORES.get(mineral);
        if (oreSet == null) {
            throw new IllegalStateException("矿脉引用的矿物没有矿石方块: " + mineral);
        }
        List<OreEntry.HostMapping> mappings = new ArrayList<>();
        for (Block host : hosts) {
            mappings.add(new OreEntry.HostMapping(host,
                    oreBlockForHost(oreSet, host).defaultBlockState()));
        }
        return new OreEntry(mappings);
    }

    /** 宿主方块 → 对应岩种矿石方块（石头/深板岩/21种群峦岩种） */
    private static Block oreBlockForHost(ModBlocks.OreBlockSet oreSet, Block host) {
        if (host == Blocks.STONE) return oreSet.stone().get();
        if (host == Blocks.DEEPSLATE) return oreSet.deepslate().get();
        for (ModRocks.RockType rock : ModRocks.ROCK_TYPES) {
            if (host == rock.block().get()) {
                return oreSet.forRock(rock.name()).get();
            }
        }
        // 非岩石宿主（保险回退）
        return oreSet.stone().get();
    }

    private static ResourceLocation veinConfiguredFeatureId(String veinId) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "vein_" + veinId);
    }

    private static ResourceLocation veinPlacedFeatureId(String veinId) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "vein_" + veinId);
    }

    private static ResourceLocation veinBiomeModifierId(String veinId) {
        return ResourceLocation.fromNamespaceAndPath(Polymech.MOD_ID, "add_vein_" + veinId);
    }
}
