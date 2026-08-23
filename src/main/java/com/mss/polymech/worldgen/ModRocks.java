package com.mss.polymech.worldgen;

import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.world.level.block.Block;

import java.util.List;

/*
 * 区域岩种系统：数据驱动的岩石地层（群峦式大尺度岩区）。
 * <p>
 * 岩区不是方形网格，而是<b>生物群系尺度的有机形状区域</b>：
 * 每种岩种拥有一个独立的低频二维值噪声场（主波长约768方块、
 * 次波长约256方块），某坐标的岩种取噪声值最大的岩种（argmax）。
 * 五个噪声场互相竞争，形成数百方块宽、边界自然弯曲的岩区，
 * 观感与生物群系一致。
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

    /** 岩层替换的最低高度（深层石带以下不再替换） */
    public static final int ROCK_LAYER_MIN_Y = -8;

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
     * 确定性地计算某水平坐标的岩种。
     * <p>
     * 对每个岩种独立采样双八度值噪声，取噪声值最大者。
     * 纯位置+种子的函数：任何时刻、任何线程重算结果一致，
     * 岩层替换Feature与任何查询方共用此方法保证一致。
     * </p>
     *
     * @param blockX 方块X坐标
     * @param blockZ 方块Z坐标
     * @param levelSeed 世界种子
     * @return 该坐标的岩种
     */
    public static RockType rockTypeAtBlock(int blockX, int blockZ, long levelSeed) {
        long seed = levelSeed + ROCK_SALT;
        int best = 0;
        double bestValue = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < ROCK_TYPES.size(); i++) {
            double value = rockNoise(seed, i, blockX, blockZ);
            if (value > bestValue) {
                bestValue = value;
                best = i;
            }
        }
        return ROCK_TYPES.get(best);
    }

    /** 单岩种的双八度噪声场：主波长定大形，次波长扰动边界 */
    private static double rockNoise(long seed, int rockIndex, int x, int z) {
        long rockSeed = seed + rockIndex * 0x9E3779B97F4A7C15L;
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
