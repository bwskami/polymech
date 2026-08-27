package com.mss.polymech.worldgen;

import com.mss.polymech.worldgen.noise.OpenSimplex2D;
import net.minecraft.core.Holder;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 区域岩种系统：完全照抄 TFC 1.21.x 的 RockLayerSettings + TFCLayers.createOverworldRockLayer
 * + RegionChunkDataGenerator.generateRock。
 * <p>
 * 已搬入：
 * <ul>
 *   <li>RockLayerSettings 的 linked-list Layer 图（overworld.json 逐字数据）；</li>
 *   <li>sampleAtLayer 的 XoroshiroRandomSource(pointRock >> 2) 采样；</li>
 *   <li>createOverworldRockLayer 的 Zoom × 7 + Smooth × 2（方块尺度有机边界）；</li>
 *   <li>RegionGenerator.rockArea 的 Uniform + Zoom × 3 + Smooth × 3（Grid 尺度相关随机）；</li>
 *   <li>generateRock 的层高 43~63、层偏移、水平扭曲 skew 采样。</li>
 * </ul>
 */
public final class ModRocks {

    // ========== 岩种定义 ==========

    public record RockType(String name) {
        public DeferredBlock<Block> block() {
            return com.mss.polymech.block.ModBlocks.ROCKS.get(name);
        }

        public BlockState blockState() {
            return block().get().defaultBlockState();
        }
    }

    /** 21 种岩石（TerraFirmaCraft 岩石套件） */
    public static final List<RockType> ROCK_TYPES = List.of(
            new RockType("limestone"), new RockType("shale"), new RockType("chalk"),
            new RockType("chert"), new RockType("claystone"), new RockType("conglomerate"),
            new RockType("dolomite"), new RockType("tuff"),
            new RockType("granite"), new RockType("basalt"), new RockType("rhyolite"),
            new RockType("dacite"), new RockType("diorite"), new RockType("gabbro"),
            new RockType("andesite"),
            new RockType("marble"), new RockType("gneiss"), new RockType("schist"),
            new RockType("slate"), new RockType("phyllite"), new RockType("quartzite")
    );

    // ========== TFC ChooseRocks 类型常量 ==========

    private static final int OCEAN = 0;
    private static final int VOLCANIC = 1;
    private static final int LAND = 2;
    private static final int UPLIFT = 3;

    private static final int TYPE_BITS = 2;
    private static final int TYPE_MASK = (1 << TYPE_BITS) - 1;

    // ========== TFC Layer 图 ==========

    private record Layer(String rockName, List<Layer> next) {}

    private static final List<String> BOTTOM_IDS = List.of(
            "gneiss", "schist", "diorite", "granite", "gabbro");

    private static final List<String> OCEAN_FLOOR_ROOT = List.of("igneous_extrusive");
    private static final List<String> LAND_ROOT = List.of(
            "sedimentary", "sedimentary", "sedimentary", "igneous_extrusive");
    private static final List<String> VOLCANIC_ROOT = List.of(
            "igneous_extrusive", "igneous_extrusive_x2", "igneous_intrusive");
    private static final List<String> UPLIFT_ROOT = List.of(
            "uplift", "uplift", "uplift", "sedimentary");

    private static final List<Layer> OCEAN_FLOOR_LAYERS;
    private static final List<Layer> LAND_LAYERS;
    private static final List<Layer> VOLCANIC_LAYERS;
    private static final List<Layer> UPLIFT_LAYERS;

    static {
        List<Layer> bottom = new ArrayList<>();
        for (String id : BOTTOM_IDS) {
            bottom.add(new Layer(id, bottom));
        }

        Map<String, List<Layer>> layers = new HashMap<>();
        layers.put("bottom", bottom);

        buildLayer(layers, "felsic", Map.of("granite", "bottom"));
        buildLayer(layers, "intermediate", Map.of("diorite", "bottom"));
        buildLayer(layers, "mafic", Map.of("gabbro", "bottom"));
        buildLayer(layers, "igneous_extrusive", Map.of(
                "andesite", "intermediate",
                "basalt", "mafic",
                "dacite", "intermediate",
                "rhyolite", "felsic"));
        buildLayer(layers, "igneous_extrusive_x2", Map.of(
                "andesite", "igneous_extrusive",
                "basalt", "igneous_extrusive",
                "dacite", "igneous_extrusive",
                "rhyolite", "igneous_extrusive"));
        buildLayer(layers, "igneous_intrusive", Map.of(
                "diorite", "intermediate",
                "gabbro", "mafic",
                "granite", "felsic"));
        buildLayer(layers, "high_grade", Map.of(
                "gneiss", "bottom",
                "schist", "bottom"));
        buildLayer(layers, "low_grade", Map.of(
                "phyllite", "high_grade",
                "slate", "high_grade"));
        buildLayer(layers, "marble", Map.of("marble", "bottom"));
        buildLayer(layers, "quartzite", Map.of("quartzite", "bottom"));
        buildLayer(layers, "sedimentary", Map.of(
                "chalk", "marble",
                "chert", "quartzite",
                "claystone", "low_grade",
                "conglomerate", "low_grade",
                "dolomite", "marble",
                "limestone", "marble",
                "shale", "low_grade"));
        buildLayer(layers, "uplift", Map.of(
                "diorite", "low_grade",
                "gabbro", "low_grade",
                "gneiss", "high_grade",
                "granite", "low_grade",
                "marble", "bottom",
                "phyllite", "high_grade",
                "quartzite", "bottom",
                "schist", "high_grade",
                "slate", "high_grade"));

        OCEAN_FLOOR_LAYERS = flattenRoot(layers, OCEAN_FLOOR_ROOT);
        LAND_LAYERS = flattenRoot(layers, LAND_ROOT);
        VOLCANIC_LAYERS = flattenRoot(layers, VOLCANIC_ROOT);
        UPLIFT_LAYERS = flattenRoot(layers, UPLIFT_ROOT);
    }

    private static void buildLayer(Map<String, List<Layer>> layers, String layerId,
                                   Map<String, String> rockToNext) {
        List<Layer> baked = new ArrayList<>();
        for (var rockEntry : rockToNext.entrySet()) {
            List<Layer> next = layers.get(rockEntry.getValue());
            baked.add(new Layer(rockEntry.getKey(), next));
        }
        layers.put(layerId, baked);
    }

    private static List<Layer> flattenRoot(Map<String, List<Layer>> layers, List<String> rootIds) {
        List<Layer> result = new ArrayList<>();
        for (String id : rootIds) {
            List<Layer> layerList = layers.get(id);
            if (layerList != null) result.addAll(layerList);
        }
        return result;
    }

    /** TFC RockLayerSettings.sampleAtLayer —— 1:1 */
    private static String sampleAtLayer(int pointRock, int layerN) {
        RandomSource source = new XoroshiroRandomSource((long) (pointRock >> TYPE_BITS));
        List<Layer> rootLayers = switch (pointRock & TYPE_MASK) {
            case OCEAN -> OCEAN_FLOOR_LAYERS;
            case VOLCANIC -> VOLCANIC_LAYERS;
            case UPLIFT -> UPLIFT_LAYERS;
            default -> LAND_LAYERS;
        };
        Layer layer = rootLayers.get(source.nextInt(rootLayers.size()));
        for (int i = 0; i < layerN; i++) {
            layer = layer.next.get(source.nextInt(layer.next.size()));
        }
        return layer.rockName;
    }

    // ========== TFC createOverworldRockLayer：Zoom + Smooth 到方块尺度 ==========

    /** 1 Grid = 128 方块（TFC Units.GRID_BITS = 7） */
    private static final int GRID_BITS = 7;
    private static final int GRID_SIZE = 1 << GRID_BITS; // 128

    @FunctionalInterface
    private interface AreaSource {
        int sample(int x, int z);
    }

    /** TFC Area：带简单哈希缓存的层容器 */
    private static final class AreaCache {
        private final AreaSource source;
        private final long[] keys;
        private final int[] values;
        private final int mask;

        AreaCache(AreaSource source, int cacheSize) {
            this.source = source;
            int size = 1 << (32 - Integer.numberOfLeadingZeros(cacheSize - 1));
            this.keys = new long[size];
            this.values = new int[size];
            this.mask = size - 1;
            Arrays.fill(keys, Long.MIN_VALUE);
        }

        int get(int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            int idx = (int) (mix(key) & mask);
            if (keys[idx] == key) return values[idx];
            int value = source.sample(x, z);
            keys[idx] = key;
            values[idx] = value;
            return value;
        }

        private static long mix(long h) {
            h ^= h >>> 33;
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
            h *= 0xc4ceb9fe1a85ec53L;
            h ^= h >>> 33;
            return h;
        }
    }

    /** TFC AreaContext：决定 Zoom/Smooth 的随机选择 */
    private static final class AreaContext {
        private final long seed;
        private final XoroshiroRandomSource random;

        AreaContext(long seed) {
            // TFC AreaContext: HashCommon.murmurHash3(seed) 后才参与 setSeed
            this.seed = it.unimi.dsi.fastutil.HashCommon.murmurHash3(seed);
            this.random = new XoroshiroRandomSource(seed);
        }

        void setSeed(long x, long z) {
            random.setSeed(((x * 501125321L) ^ (z * 1136930381L) ^ seed) * 0x27d4eb2d);
        }

        int choose(int first, int second) {
            return random.nextBoolean() ? first : second;
        }

        int choose(int first, int second, int third, int fourth) {
            return switch (random.nextInt(4)) {
                case 0 -> first;
                case 1 -> second;
                case 2 -> third;
                default -> fourth;
            };
        }
    }

    /** TFC ZoomLayer.NORMAL（完整等值保留逻辑） */
    private static int zoom(AreaContext context, AreaCache prev, int x, int z) {
        final int parentX = x >> 1, parentZ = z >> 1;
        final int offsetX = x & 1, offsetZ = z & 1;
        final int northWest = prev.get(parentX, parentZ);

        context.setSeed(parentX, parentZ);
        if (offsetX == 0 && offsetZ == 0) {
            return northWest;
        } else if (offsetX == 0) {
            return context.choose(northWest, prev.get(parentX, parentZ + 1));
        } else if (offsetZ == 0) {
            return context.choose(northWest, prev.get(parentX + 1, parentZ));
        }
        final int northEast = prev.get(parentX, parentZ + 1);
        final int southWest = prev.get(parentX + 1, parentZ);
        final int southEast = prev.get(parentX + 1, parentZ + 1);

        // TFC ZoomLayer.NORMAL.choose
        if (northWest == northEast) {
            return northWest == southWest || southWest != southEast ? northWest : context.choose(northWest, southWest);
        } else if (northWest == southWest) {
            return northEast != southEast ? northWest : context.choose(northWest, northEast);
        } else if (northWest == southEast) {
            return northEast != southWest ? northWest : context.choose(northWest, northEast);
        } else if (northEast == southWest || northEast == southEast) {
            return northEast;
        } else if (southWest == southEast) {
            return southWest;
        }
        return context.choose(northWest, northEast, southWest, southEast);
    }

    /** TFC SmoothLayer */
    private static int smooth(AreaContext context, AreaCache prev, int x, int z) {
        final int north = prev.get(x, z - 1);
        final int east = prev.get(x + 1, z);
        final int south = prev.get(x, z + 1);
        final int west = prev.get(x - 1, z);
        final int center = prev.get(x, z);

        final boolean equalX = west == east, equalZ = north == south;
        if (equalX == equalZ) {
            if (equalX) {
                return context.choose(east, north);
            }
            return center;
        }
        return equalX ? east : north;
    }

    /** 每个世界种子建一条 ThreadLocal 的 Region RockLayerArea（TFC createUniformLayer(seed, 3)） */
    private static final ThreadLocal<Map<Long, AreaCache>> REGION_ROCK_AREAS =
            ThreadLocal.withInitial(HashMap::new);

    private static int regionRockArea(long levelSeed, int gridX, int gridZ) {
        final AreaCache area = REGION_ROCK_AREAS.get()
                .computeIfAbsent(levelSeed, ModRocks::createRegionRockArea);
        return area.get(gridX, gridZ);
    }

    /** TFC RegionGenerator.rockArea：Uniform + Zoom×3 + Smooth×3，在 Grid 尺度上产生关联的随机值 */
    private static AreaCache createRegionRockArea(long levelSeed) {
        AreaCache layer = new AreaCache((x, z) -> {
            XoroshiroRandomSource rng = new XoroshiroRandomSource(
                    levelSeed ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0x632BE59BD9B4E019L));
            return rng.nextInt();
        }, 4096);

        for (int i = 0; i < 3; i++) {
            final AreaCache prevZoom = layer;
            final AreaContext zoomCtx = new AreaContext(levelSeed ^ (0x6C8E9CF570932BD5L * (i + 1)));
            layer = new AreaCache((x, z) -> zoom(zoomCtx, prevZoom, x, z), 4096);

            final AreaCache prevSmooth = layer;
            final AreaContext smoothCtx = new AreaContext(levelSeed ^ (0x165667B19E3779F9L * (i + 1)));
            layer = new AreaCache((x, z) -> smooth(smoothCtx, prevSmooth, x, z), 4096);
        }
        return layer;
    }

    /** 每个世界种子建一条 ThreadLocal 的 RockLayerArea */
    private static final ThreadLocal<Map<Long, AreaCache>> ROCK_AREAS =
            ThreadLocal.withInitial(HashMap::new);

    private static AreaCache rockArea(long levelSeed) {
        return ROCK_AREAS.get().computeIfAbsent(levelSeed, ModRocks::createRockArea);
    }

    /** 与 TFC createOverworldRockLayer 等价：grid → zoom×6 → smooth → zoom → smooth */
    private static AreaCache createRockArea(long levelSeed) {
        // Grid 尺度：先用 regionRockArea（TFC RegionGenerator.rockArea）再套 Overworld Rock Layer
        AreaCache layer = new AreaCache((x, z) -> regionRockArea(levelSeed, x, z), 4096);

        // 6 次 ZoomLayer，把 128 方块缩到 2 方块
        for (int i = 0; i < 6; i++) {
            final AreaCache prev = layer;
            final AreaContext ctx = new AreaContext(levelSeed ^ (0x9E3779B97F4A7C15L * (i + 1)));
            layer = new AreaCache((x, z) -> zoom(ctx, prev, x, z), 4096);
        }

        // Smooth + Zoom + Smooth 到 1 方块
        final AreaCache prevSmooth1 = layer;
        final AreaContext smoothCtx1 = new AreaContext(levelSeed ^ 0x632BE59BD9B4E019L);
        layer = new AreaCache((x, z) -> smooth(smoothCtx1, prevSmooth1, x, z), 4096);

        final AreaCache prevZoom2 = layer;
        final AreaContext zoomCtx2 = new AreaContext(levelSeed ^ 0x165667B19E3779F9L);
        layer = new AreaCache((x, z) -> zoom(zoomCtx2, prevZoom2, x, z), 4096);

        final AreaCache prevSmooth2 = layer;
        final AreaContext smoothCtx2 = new AreaContext(levelSeed ^ 0x85EBCA77C2B2AE63L);
        layer = new AreaCache((x, z) -> smooth(smoothCtx2, prevSmooth2, x, z), 4096);

        return layer;
    }

    /** 每个 Grid 格子的确定性随机值 */
    private static int deterministicRockArea(long levelSeed, int gridX, int gridZ) {
        XoroshiroRandomSource rng = new XoroshiroRandomSource(
                levelSeed ^ ((long) gridX * 0x9E3779B97F4A7C15L) ^ ((long) gridZ * 0x632BE59BD9B4E019L));
        return rng.nextInt();
    }

    // ========== TFC RegionChunkDataGenerator.generateRock：垂直层高 + 层偏移 + 水平扭曲 ==========

    private static final int LAYER_OFFSET_BITS = 3;
    private static final int LAYER_OFFSET_MASK = (1 << LAYER_OFFSET_BITS) - 1;
    private static final int[] LAYER_OFFSETS = new int[1 << (LAYER_OFFSET_BITS + 1)];

    private static final float DELTA_Y_OFFSET = 12;

    static {
        final RandomSource random = new XoroshiroRandomSource(1923874192341L);
        for (int i = 0; i < LAYER_OFFSETS.length; i++) {
            LAYER_OFFSETS[i] = random.nextInt(0, 100_000);
        }
    }

    private static int getOffsetX(int layer) {
        return LAYER_OFFSETS[(layer & LAYER_OFFSET_MASK) << 1];
    }

    private static int getOffsetZ(int layer) {
        return LAYER_OFFSETS[((layer & LAYER_OFFSET_MASK) << 1) | 0b1];
    }

    /** 每列缓存：从地表向下各层的累计厚度（TFC 的 ChunkRockDataCache 简化为列级缓存） */
    private static final class ColumnData {
        final float[] cumulativeLayerHeights;

        ColumnData(float[] cumulativeLayerHeights) {
            this.cumulativeLayerHeights = cumulativeLayerHeights;
        }
    }

    private record ColumnKey(long seed, int x, int z) {}

    private static final ThreadLocal<Map<ColumnKey, ColumnData>> COLUMN_CACHE =
            ThreadLocal.withInitial(HashMap::new);

    private static ColumnData columnData(long seed, int x, int z) {
        return COLUMN_CACHE.get().computeIfAbsent(new ColumnKey(seed, x, z), key -> {
            // 最多算 8 层，足以覆盖从地表到 ROCK_LAYER_MIN_Y
            float[] cumulative = new float[8];
            float total = 0;
            for (int layer = 0; layer < cumulative.length; layer++) {
                int offsetX = x + getOffsetX(layer);
                int offsetZ = z + getOffsetZ(layer);
                total += layerHeightNoise(seed, offsetX, offsetZ);
                cumulative[layer] = total;
            }
            return new ColumnData(cumulative);
        });
    }

    private static int solveLayer(ColumnData data, float deltaY) {
        float[] cumulative = data.cumulativeLayerHeights;
        for (int layer = 0; layer < cumulative.length; layer++) {
            if (deltaY <= cumulative[layer]) return layer;
        }
        return cumulative.length - 1;
    }

    private static float remainingInLayer(ColumnData data, int layer, float deltaY) {
        float[] cumulative = data.cumulativeLayerHeights;
        return deltaY - (layer == 0 ? 0 : cumulative[layer - 1]);
    }

    private static float layerHeight(ColumnData data, int layer) {
        float[] cumulative = data.cumulativeLayerHeights;
        return cumulative[layer] - (layer == 0 ? 0 : cumulative[layer - 1]);
    }

    /** TFC layerHeightNoise：OpenSimplex2D 3八度，43~63 方块一层的噪声高度 */
    private static float layerHeightNoise(long seed, int x, int z) {
        return (float) noiseSet(seed).layerHeight.noise(x, z);
    }

    /** TFC layerSkewXNoise：OpenSimplex2D 2八度，-1.8 ~ 1.8 */
    private static float layerSkewXNoise(long seed, int x, int z) {
        return (float) noiseSet(seed).layerSkewX.noise(x, z);
    }

    /** TFC layerSkewZNoise：OpenSimplex2D 2八度，-1.8 ~ 1.8 */
    private static float layerSkewZNoise(long seed, int x, int z) {
        return (float) noiseSet(seed).layerSkewZ.noise(x, z);
    }

    /** 二维值噪声：晶格哈希 + smoothstep 双线性插值 */
    private static double valueNoise(long seed, double x, double z) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double sx = fx * fx * (3.0 - 2.0 * fx);
        double sz = fz * fz * (3.0 - 2.0 * fz);

        double v00 = hashToUnit(seed, x0, z0);
        double v10 = hashToUnit(seed, x0 + 1, z0);
        double v01 = hashToUnit(seed, x0, z0 + 1);
        double v11 = hashToUnit(seed, x0 + 1, z0 + 1);

        double a = v00 + (v10 - v00) * sx;
        double b = v01 + (v11 - v01) * sx;
        return a + (b - a) * sz;
    }

    private static double hashToUnit(long seed, int x, int z) {
        long h = seed ^ ((long) x * 374761393L) ^ ((long) z * 668265263L);
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        h = h ^ (h >>> 33);
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }


    // ========== 深度层划分 ==========

    public static final int ROCK_LAYER_MIN_Y = -48;
    public static final int SURFACE_ROCK_MIN_Y = 16;
    public static final int MID_ROCK_MIN_Y = -24;
    public static final int DEEP_ROCK_MIN_Y = ROCK_LAYER_MIN_Y;
    public static final int TRANSITION_WIDTH = 6;

    private static final long ROCK_SALT = 0x524F434BL;

    private record NoiseSet(OpenSimplex2D layerHeight, OpenSimplex2D layerSkewX, OpenSimplex2D layerSkewZ) {}
    private static final ThreadLocal<Map<Long, NoiseSet>> NOISE_SETS = ThreadLocal.withInitial(HashMap::new);

    private static NoiseSet noiseSet(long seed) {
        return NOISE_SETS.get().computeIfAbsent(seed, s -> new NoiseSet(
                new OpenSimplex2D(s + 0x6C8E9CF570932BD5L).octaves(3).scaled(43, 63).spread(0.014F),
                new OpenSimplex2D(s + 0x9E3779B97F4A7C15L).octaves(2).scaled(-1.8F, 1.8F).spread(0.01F),
                new OpenSimplex2D(s + 0x632BE59BD9B4E019L).octaves(2).scaled(-1.8F, 1.8F).spread(0.01F)
        ));
    }

    private ModRocks() {}

    // ========== 对外接口 ==========

    public static RockType rockTypeAtBlock(int blockX, int blockZ, long levelSeed) {
        return rockTypeAt(blockX, blockZ, SURFACE_ROCK_MIN_Y + 1, levelSeed);
    }

    public static RockType rockTypeAt(int blockX, int blockZ, int blockY, long levelSeed) {
        return rockTypeAt(blockX, blockZ, blockY, levelSeed, null);
    }

    /**
     * 核心入口：完全照抄 TFC generateRock。
     * <p>
     * 1. 以实际地表为起点向下迭代层高（43~63 方块/层），确定当前方块属于第几层；
     * 2. 用层高残差 + 层偏移 + 水平扭曲噪声计算采样点；
     * 3. 在 smooth 后的 RockLayerArea 上取 pointRock；
     * 4. 用 TFC sampleAtLayer(pointRock, layer) 得到岩种。
     */
    public static RockType rockTypeAt(int blockX, int blockZ, int blockY, long levelSeed,
                                       @Nullable Holder<Biome> biome) {
        return rockTypeAt(blockX, blockZ, blockY, levelSeed, biome,
                Math.max(blockY, SURFACE_ROCK_MIN_Y + 1));
    }

    public static RockType rockTypeAt(int blockX, int blockZ, int blockY, long levelSeed,
                                       @Nullable Holder<Biome> biome, int surfaceY) {
        final long seed = levelSeed + ROCK_SALT;

        // TFC: adjustedSurfaceY
        float adjustedSurfaceY = surfaceY > 125 ? 125 + 0.3f * (surfaceY - 125) : surfaceY;

        // TFC: 从地表向下迭代找层
        float deltaY = adjustedSurfaceY - blockY;
        if (deltaY < 0) deltaY = 0;

        ColumnData column = columnData(seed, blockX, blockZ);
        int layer = solveLayer(column, deltaY);

        // TFC: 层偏移 + 水平扭曲
        int offsetX = blockX + getOffsetX(layer);
        int offsetZ = blockZ + getOffsetZ(layer);
        float remaining = remainingInLayer(column, layer, deltaY);
        float skewX = layerSkewXNoise(seed, offsetX, offsetZ);
        float skewZ = layerSkewZNoise(seed, offsetX, offsetZ);
        int skewBlockX = blockX + (int) (skewX * (remaining + DELTA_Y_OFFSET));
        int skewBlockZ = blockZ + (int) (skewZ * (remaining + DELTA_Y_OFFSET));

        int smoothSeed = rockArea(seed).get(skewBlockX, skewBlockZ);
        int type = determineType(biome);

        int pointRock = (smoothSeed << TYPE_BITS) | type;
        String rockName = sampleAtLayer(pointRock, layer);

        // TFC 原版是硬边界；这里按用户要求加一层“岩层过渡混合”：
        // 在层边界上下 TRANSITION_WIDTH 方块内，用噪声在相邻两层岩石间随机混合。
        float height = layerHeight(column, layer);
        if (layer > 0 && remaining < TRANSITION_WIDTH) {
            String upperRock = sampleAtLayer(pointRock, layer - 1);
            double chance = 0.5 * (1.0 - remaining / TRANSITION_WIDTH);
            if (transitionNoise(seed, blockX, blockY, blockZ, 0x1_0000 + layer) < chance) {
                rockName = upperRock;
            }
        } else if (layer < column.cumulativeLayerHeights.length - 1
                && height - remaining < TRANSITION_WIDTH) {
            String lowerRock = sampleAtLayer(pointRock, layer + 1);
            double chance = 0.5 * (1.0 - (height - remaining) / TRANSITION_WIDTH);
            if (transitionNoise(seed, blockX, blockY, blockZ, 0x2_0000 + layer) < chance) {
                rockName = lowerRock;
            }
        }

        return findRock(rockName);
    }


    /** biome → TFC type */
    private static int determineType(@Nullable Holder<Biome> biome) {
        if (biome == null) return LAND;
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN)) return OCEAN;
        if (biome.is(BiomeTags.IS_MOUNTAIN) || biome.is(BiomeTags.IS_HILL)) return UPLIFT;
        return LAND;
    }

    private static RockType findRock(String name) {
        for (RockType rock : ROCK_TYPES) {
            if (rock.name().equals(name)) return rock;
        }
        throw new IllegalStateException("未知岩种: " + name);
    }

    /** 过渡带哈希噪声 */
    private static double transitionNoise(long seed, int x, int y, int z, int boundary) {
        long h = seed ^ (long) x * 0x27D4EB2F165667C5L ^ (long) y * 0x9E3779B97F4A7C15L
                ^ (long) z * 0xC2B2AE3D27D4EB4FL ^ (long) boundary * 0x85EBCA77C2B2AE63L;
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** 深层模组岩 ↔ 深板岩过渡 */
    public static boolean shouldUseModRock(int blockY, long levelSeed, int x, int z) {
        if (blockY >= ROCK_LAYER_MIN_Y) return true;
        int distance = ROCK_LAYER_MIN_Y - blockY;
        if (distance > TRANSITION_WIDTH) return false;
        double chance = 0.5 * (1.0 - distance / (double) TRANSITION_WIDTH);
        return transitionNoise(levelSeed, x, blockY, z, ROCK_LAYER_MIN_Y) < chance;
    }
}
