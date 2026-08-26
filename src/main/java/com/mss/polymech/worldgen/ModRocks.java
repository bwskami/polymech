package com.mss.polymech.worldgen;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/*
 * 区域岩种系统：数据驱动的岩石地层（群峦式大尺度岩区）。
 * <p>
 * 岩区不是方形网格，而是<b>生物群系尺度的有机形状区域</b>：
 * 每种岩种拥有一个独立的低频二维值噪声场（主波长约768方块、
 * 次波长约256方块），某坐标的岩种取噪声值最大的岩种（argmax）。
 * 多个噪声场互相竞争，形成数百方块宽、边界自然弯曲的岩区，
 * 观感与生物群系一致。
 * </p>
 * <p>
 * 垂直方向采用<b>群峦式地层序列</b>：先由噪声决定该列的“母岩”，
 * 再按深度套用沉积岩→变质岩、喷出岩→侵入岩的演变，
 * 而不是浅/中/深三层各自随机。
 * </p>
 *
 * <h2>与矿脉的联动（群峦式宿主过滤，两级）：</h2>
 * <p>
 * {@link ModVeins}的矿脉定义带allowedRocks字段：
 * 放置期矿脉中心岩种必须在允许列表内（否则整条骰子作废，
 * 避免产出被削掉大半的残月形矿体）；替换期再<b>逐方块</b>检查宿主——
 * 矿体只会生长在自己的宿主岩内，岩区边界穿过时沿边界"裁切"，
 * 实现"看岩认矿"的地质勘探体验（如红土镍脉只生长在玄武岩区）。
 * </p>
 *
 * <h2>添加新岩种：</h2>
 * <pre>{@code
 * // 1. ROCK_TYPES 中追加一行
 * // 2. 放入同名贴图 assets/poly_mech/textures/block/rock/raw/{name}.png
 * //    （当前素材取自TerraFirmaCraft，EUPL授权，见TEXTURE_CREDITS.md）
 * // 3. 语言文件中追加名称；方块/物品/战利品/标签/创造页自动生成
 * }</pre>
 *
 * @see RockRegionFeature
 * @see ModVeins.VeinDefinition#allowedRocks()
 */
public final class ModRocks {

    /*
     * 单个岩种定义。
     *
     * @param name 注册名（方块ID、贴图名、矿脉allowedRocks引用名三处一致）
     */
    public record RockType(String name) {
        /** 该岩种对应的方块（由ModBlocks批量注册） */
        public DeferredBlock<Block> block() {
            return com.mss.polymech.block.ModBlocks.ROCKS.get(name);
        }

        /** 默认方块状态（Feature替换用） */
        public BlockState blockState() {
            return block().get().defaultBlockState();
        }
    }

    /*
     * 全部岩种（贴图取自TerraFirmaCraft的21种完整岩石套件）。
     * <p>
     * 按三大岩类覆盖：
     * <ul>
     *   <li>沉积岩：石灰岩limestone、页岩shale、白垩chalk、硅质岩chert、
     *       粘土岩claystone、砾岩conglomerate、白云岩dolomite、凝灰岩tuff</li>
     *   <li>岩浆岩：花岗岩granite、玄武岩basalt、流纹岩rhyolite、英安岩dacite、
     *       闪长岩diorite、辉长岩gabbro、安山岩andesite</li>
     *   <li>变质岩：大理岩marble、片麻岩gneiss、片岩schist、板岩slate、
     *       千枚岩phyllite、石英岩quartzite</li>
     * </ul>
     * 岩区由argmax噪声场竞争生成（见类注释），21个噪声场同场竞争。
     * </p>
     */
    public static final List<RockType> ROCK_TYPES = List.of(
            // 沉积岩
            new RockType("limestone"),
            new RockType("shale"),
            new RockType("chalk"),
            new RockType("chert"),
            new RockType("claystone"),
            new RockType("conglomerate"),
            new RockType("dolomite"),
            new RockType("tuff"),
            // 岩浆岩
            new RockType("granite"),
            new RockType("basalt"),
            new RockType("rhyolite"),
            new RockType("dacite"),
            new RockType("diorite"),
            new RockType("gabbro"),
            new RockType("andesite"),
            // 变质岩
            new RockType("marble"),
            new RockType("gneiss"),
            new RockType("schist"),
            new RockType("slate"),
            new RockType("phyllite"),
            new RockType("quartzite")
    );

    // ========== 群峦式垂直岩层图（照 TFC 1.21.x rock_layer_settings） ==========

    /** 最终基底岩层（TFC bottom） */
    private static final List<String> BOTTOM_ROCKS = List.of("gneiss", "schist", "diorite", "granite", "gabbro");

    /**
     * 每个岩种向下一个岩层可随机进入的岩石列表。
     * 数据照抄 TFC 1.21.x {@code worldgen/world_preset/overworld.json} 的 rock_layer_settings。
     */
    private static final Map<String, List<String>> ROCK_NEXT = Map.ofEntries(
        // 沉积岩 → 低级变质岩
        Map.entry("chalk", List.of("marble")),
        Map.entry("chert", List.of("quartzite")),
        Map.entry("claystone", List.of("phyllite", "slate")),
        Map.entry("conglomerate", List.of("phyllite", "slate")),
        Map.entry("dolomite", List.of("marble")),
        Map.entry("limestone", List.of("marble")),
        Map.entry("shale", List.of("phyllite", "slate")),
        // 低级变质岩 → 高级变质岩
        Map.entry("phyllite", List.of("gneiss", "schist")),
        Map.entry("slate", List.of("gneiss", "schist")),
        // 高级变质岩/基底 → 基底
        Map.entry("gneiss", BOTTOM_ROCKS),
        Map.entry("schist", BOTTOM_ROCKS),
        Map.entry("marble", BOTTOM_ROCKS),
        Map.entry("quartzite", BOTTOM_ROCKS),
        // 喷出火成岩 → 同成分侵入岩
        Map.entry("andesite", List.of("diorite")),
        Map.entry("basalt", List.of("gabbro")),
        Map.entry("dacite", List.of("diorite")),
        Map.entry("rhyolite", List.of("granite")),
        Map.entry("tuff", List.of("granite")),
        // 侵入岩 → 基底
        Map.entry("granite", BOTTOM_ROCKS),
        Map.entry("diorite", BOTTOM_ROCKS),
        Map.entry("gabbro", BOTTOM_ROCKS)
    );

    /** 岩层替换的最低高度（此深度以下保持原版深层石不再替换） */
    public static final int ROCK_LAYER_MIN_Y = -48;

    /** 垂直岩层分界：浅层起始深度（>=该Y为表层岩） */
    public static final int SURFACE_ROCK_MIN_Y = 16;

    /** 垂直岩层分界：中层起始深度（该Y~SURFACE_ROCK_MIN_Y为中层岩） */
    public static final int MID_ROCK_MIN_Y = -24;

    /** 垂直岩层分界：深层起始深度（ROCK_LAYER_MIN_Y~MID_ROCK_MIN_Y为深层岩） */
    public static final int DEEP_ROCK_MIN_Y = ROCK_LAYER_MIN_Y;

    /** 垂直岩层过渡带宽度（方块）：边界两侧按概率混合相邻层岩石，避免一刀切（改为较短过渡） */
    public static final int TRANSITION_WIDTH = 6;

    /** 岩区种子盐值（"ROCK"） */
    private static final long ROCK_SALT = 0x524F434BL;

    /** 主噪声波长（方块）：决定岩区主体尺度，数百方块宽，与生物群系同量级 */
    private static final double PRIMARY_WAVELENGTH = 768.0;

    /** 次噪声波长（方块）：为岩区边界添加小尺度起伏，避免平滑圆弧感 */
    private static final double SECONDARY_WAVELENGTH = 256.0;

    /** 主/次噪声的混合权重 */
    private static final double PRIMARY_WEIGHT = 0.7;
    private static final double SECONDARY_WEIGHT = 0.3;

    private ModRocks() {
    }

    /*
     * 群峦式垂直岩层：先由水平噪声决定本区域的“母岩”，再按深度套用地质序列。
     * <p>
     * 分层规则（纯确定性，任何线程重算一致）：
     * <ul>
     *   <li>Y >= {@link #SURFACE_ROCK_MIN_Y}：表层（母岩本身）</li>
     *   <li>{@link #MID_ROCK_MIN_Y} <= Y < {@link #SURFACE_ROCK_MIN_Y}：中层
     *       （沉积岩→低级变质岩；喷出岩→同成分侵入岩）</li>
     *   <li>{@link #DEEP_ROCK_MIN_Y} <= Y < {@link #MID_ROCK_MIN_Y}：深层
     *       （低级→高级变质岩，或侵入岩）</li>
     * </ul>
     * 这样同一片岩区在垂直方向会呈现群峦手册描述的沉积岩→变质岩、
     * 喷出岩→侵入岩的过渡，而不是三层各自随机。
     * </p>
     */
    public static RockType rockTypeAt(int blockX, int blockZ, int blockY, long levelSeed) {
        return rockTypeAt(blockX, blockZ, blockY, levelSeed, null);
    }

    /**
     * 岩层查询：母岩由全局噪声决定，不依赖生物群系（避免跨区块判定不一致）。
     * biome 参数保留仅为了兼容旧接口，实际不再参与选岩。
     */
    public static RockType rockTypeAt(int blockX, int blockZ, int blockY, long levelSeed,
                                      @Nullable Holder<Biome> biome) {
        long seed = levelSeed + ROCK_SALT;
        int layer = blockY >= SURFACE_ROCK_MIN_Y ? 0
                : (blockY >= MID_ROCK_MIN_Y ? 1 : 2);
        // 群峦式岩区是全局噪声驱动的，不依赖当前区块的生物群系；
        // 如果在这里使用 biome，会把同一矿脉中心在不同区块判定成不同岩种，
        // 导致跨区块矿脉被“切成两半”（老 bug）。
        List<RockType> surfaceAllowed = null;
        // 母岩只由表层噪声决定，中/深层由地质序列从母岩推导
        RockType surface = rockTypeFromNoise(seed, 0, surfaceAllowed, blockX, blockZ);
        RockType primary = rockForLayer(surface, layer, seed, blockX, blockZ);

        // 边界垂直过渡：在分界线附近按距离比例混合相邻层岩石（同一母岩的相邻深度变体）
        int otherLayer = -1;
        int boundary = -1;
        if (Math.abs(blockY - SURFACE_ROCK_MIN_Y) <= TRANSITION_WIDTH) {
            boundary = SURFACE_ROCK_MIN_Y;
            otherLayer = blockY >= SURFACE_ROCK_MIN_Y ? 1 : 0;
        } else if (Math.abs(blockY - MID_ROCK_MIN_Y) <= TRANSITION_WIDTH) {
            boundary = MID_ROCK_MIN_Y;
            otherLayer = blockY >= MID_ROCK_MIN_Y ? 2 : 1;
        }
        if (otherLayer < 0) return primary;

        double distance = Math.abs(blockY - boundary);
        double chance = 0.5 * (1.0 - Math.min(1.0, distance / TRANSITION_WIDTH));
        if (transitionNoise(seed, layer, boundary, blockX, blockY, blockZ) < chance) {
            return rockForLayer(surface, otherLayer, seed, blockX, blockZ);
        }
        return primary;
    }

    /** 按名称查岩种；名称非法时快速失败（数据表配置错误） */
    private static RockType findRock(String name) {
        for (RockType rock : ROCK_TYPES) {
            if (rock.name().equals(name)) return rock;
        }
        throw new IllegalStateException("未知岩种: " + name);
    }

    /*
     * 群峦式地层序列：给定表层母岩和深度层，按 TFC 的 rock_layer_settings 图向下采样。
     * <ul>
     *   <li>沉积岩：→ 低级变质岩 → 高级变质岩 → 基底</li>
     *   <li>喷出岩：→ 同成分侵入岩 → 基底</li>
     *   <li>侵入岩/高级变质岩：→ 基底</li>
     * </ul>
     * 每一步都在 next 列表里用确定性随机选一个，保证跨区块/跨线程一致。
     */
    private static RockType rockForLayer(RockType surface, int layer, long seed, int x, int z) {
        String current = surface.name();
        for (int step = 1; step <= layer; step++) {
            List<String> next = ROCK_NEXT.getOrDefault(current, List.of());
            if (next.isEmpty()) break;
            current = pickNext(seed, x, z, step, next);
        }
        return findRock(current);
    }

    /** 从 next 列表中确定性随机选一个（纯种子的 TFC 式采样） */
    private static String pickNext(long seed, int x, int z, int step, List<String> options) {
        double random = transitionNoise(seed, step, 0, x, 0, z);
        int index = Math.min(options.size() - 1, (int) (random * options.size()));
        return options.get(index);
    }

    /** 兼容旧接口：默认按表层岩查询（surface layer） */
    public static RockType rockTypeAtBlock(int blockX, int blockZ, long levelSeed) {
        return rockTypeAt(blockX, blockZ, SURFACE_ROCK_MIN_Y + 1, levelSeed);
    }

    /**
     * 深层模组岩 ↔ 深板岩过渡：true=放置模组岩，false=保留深板岩。
     * 在ROCK_LAYER_MIN_Y上方一定深度内按距离概率混合，避免深板岩分界一刀切。
     */
    public static boolean shouldUseModRock(int blockY, long levelSeed, int x, int z) {
        if (blockY >= ROCK_LAYER_MIN_Y) return true;
        int distance = ROCK_LAYER_MIN_Y - blockY;
        if (distance > TRANSITION_WIDTH) return false;
        long seed = levelSeed + ROCK_SALT;
        double chance = 0.5 * (1.0 - distance / (double) TRANSITION_WIDTH);
        return transitionNoise(seed, 2, ROCK_LAYER_MIN_Y, x, blockY, z) < chance;
    }

    /** 对某一岩层索引执行 argmax 噪声竞争；allowed非null时只在允许岩族内竞争 */
    private static RockType rockTypeFromNoise(long seed, int layer, @Nullable List<RockType> allowed, int x, int z) {
        int best = 0;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ROCK_TYPES.size(); i++) {
            RockType rock = ROCK_TYPES.get(i);
            if (allowed != null && !allowed.contains(rock)) continue;
            double value = rockNoise(seed, layer, i, x, z);
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return ROCK_TYPES.get(best);
    }

    /** 过渡带确定性随机：只依赖坐标与种子，任何线程任何时间结果一致 */
    private static double transitionNoise(long seed, int layer, int boundary, int x, int y, int z) {
        long h = seed ^ (long) x * 0x27D4EB2F165667C5L ^ (long) y * 0x9E3779B97F4A7C15L
                ^ (long) z * 0xC2B2AE3D27D4EB4FL ^ (long) layer * 0x165667B19E3779F9L
                ^ (long) boundary * 0x85EBCA77C2B2AE63L;
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** 单岩种的双八度噪声场：主波长定大形，次波长扰动边界；层盐使浅/中/深噪声互不相关 */
    private static double rockNoise(long seed, int layer, int rockIndex, int x, int z) {
        long rockSeed = seed + layer * 0x632BE59BD9B4E019L + rockIndex * 0x9E3779B97F4A7C15L;
        double primary = valueNoise(rockSeed, x / PRIMARY_WAVELENGTH, z / PRIMARY_WAVELENGTH);
        double secondary = valueNoise(rockSeed + 1, x / SECONDARY_WAVELENGTH, z / SECONDARY_WAVELENGTH);
        return primary * PRIMARY_WEIGHT + secondary * SECONDARY_WEIGHT;
    }

    /*
     * 二维值噪声：晶格哈希取角点值，smoothstep双线性插值。
     * 输出范围[0,1)，关于输入连续。
     */
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

    /** 整数晶格坐标 → [0,1)伪随机值（MurmurHash3收尾混合，分布均匀） */
    private static double hashToUnit(long seed, int x, int z) {
        long h = seed ^ ((long) x * 374761393L) ^ ((long) z * 668265263L);
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        h = h ^ (h >>> 33);
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }
}
