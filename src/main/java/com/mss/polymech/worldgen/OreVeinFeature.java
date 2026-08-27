package com.mss.polymech.worldgen;

import com.mojang.serialization.Codec;
import com.mss.polymech.block.ModBlocks;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/*
 * 矿脉生成特征：跨区块密度采样椭球矿体 + 地表矿苗（群峦式机制）。
 *
 * <h2>跨区块生成（照群峦VeinFeature的写法）：</h2>
 * <p>
 * 原版Feature一个区块掷一次骰、矿体被限制在单次放置的上下文里；
 * 群峦的做法是——放置特征不带任何修饰器、每区块执行一次，特征自己
 * 扫描以当前区块为中心、chunkRadius范围内的全部<b>候选中心区块</b>：
 * </p>
 * <pre>
 * 对每个候选中心区块(cx, cz)：
 *   random = Xoroshiro(世界种子 ⊕ cx×A, 矿脉种子 ⊕ cz×B)   // 全球确定
 *   random.nextInt(rarity) == 0 → 该区块内有一条矿脉中心
 *   中心位置 = 区块内随机(x,z) + 收缩了ry的随机高度(避免高度边界裁切)
 * </pre>
 * <p>
 * 掷骰只依赖(世界种子, 区块坐标, 矿脉种子)，全球任何时刻重算一致——
 * 相邻区块各自扫描时得到同一批矿脉中心。每个区块只放置
 * <b>椭球与自己16×16柱域的交集</b>，整条矿脉在区块边界无缝衔接，
 * 形状随机又按矿脉中心派生、各区块消耗同一随机序列，跨区块完全一致。
 * </p>
 *
 * <h2>形态结构（以矿脉中心为原点）：</h2>
 * <ul>
 *   <li>水平截面：半径size的圆；垂直方向压扁为 ry = size×0.6 的椭球，
 *       呈透镜状矿体（群峦矿脉的典型形态）</li>
 *   <li>密度采样：椭球内每个方块以density概率成矿，
 *       形成稀疏-致密过渡的自然矿体，而非实心团块</li>
 *   <li>下部（dy &lt; 0）：主矿 primary；上部：次矿 secondary</li>
 *   <li>中间夹层（|dy| ≤ ry/3）：35%概率替换为 between 伴生矿</li>
 *   <li>全域：8%概率替换为 sporadic 零星矿</li>
 * </ul>
 *
 * <h2>宿主过滤（群峦式，两级）：</h2>
 * <p>
 * 1) <b>放置期门控</b>：矿脉中心所在岩种不在{@link OreVeinConfiguration#blocks()}
 * 允许列表内时，整条骰子直接作废（该判定由纯噪声计算、各区块结果一致）。
 * 否则"中心落在错误岩区、只有边缘蹭进正确岩区"的骰子会产出被削掉大半的
 * 残月形矿体——即此前"个别区块凭空缺矿"的根源。<br>
 * 2) <b>替换期逐方块过滤</b>：只替换允许列表内的宿主方块
 * （深部矿脉额外含深层石）。岩区边界恰好穿过矿体时，
 * 矿体沿边界自然裁切——边界两侧岩种不同，属地质常态。
 * </p>
 *
 * <h2>地表矿苗（勘探指示，群峦式约束）：</h2>
 * <p>
 * 每条矿脉1~3个苗位，位置由矿脉随机确定性派生、<b>只由苗位所在区块的
 * 生成流程放置</b>（跨区块写入会产生重复与顺序依赖）。仅当该柱矿体顶部
 * 距地表足够近（INDICATOR_DEPTH）时才生成；矿苗以露头形式替换裸露的
 * 岩石表面方块（嵌入岩面，不悬浮），绝不出现在草方块、泥土、树叶等
 * 非岩石表面上。
 * </p>
 *
 * @see ModVeins
 */
public class OreVeinFeature extends Feature<OreVeinConfiguration> {

    /** 中间夹层被between伴生矿替换的概率 */
    private static final float BETWEEN_CHANCE = 0.35F;

    /** 外围伴生矿基础概率（在OUTER_HALO_START之外出现，越靠外越高） */
    private static final float SPORADIC_CHANCE = 0.08F;

    /** 椭球垂直压扁系数：透镜状矿体 */
    private static final float VERTICAL_SQUASH = 0.6F;

    /** 矿脉有效半径倍率：使矿床比配置里的size更大，更接近群峦矿脉观感 */
    private static final float SIZE_SCALE = 1.5F;

    /** 椭球边缘的最低成矿密度因子：即使边缘也保留少量零星矿，避免突然截断 */
    private static final float MIN_DENSITY_FACTOR = 0.15F;

    /** 外围伴生晕的起始径向距离（0=中心，1=椭球边界）；此范围之外主要出现伴随矿 */
    private static final float OUTER_HALO_START = 0.5F;

    /** 主矿脉体外围“游离矿物”壳层的水平半径倍率（相对 rxz） */
    private static final float OUTER_HALO_RADIUS_FACTOR = 1.25F;



    /*
     * 矿苗深度约束（群峦Indicator.depth语义）：
     * 矿体顶部与地表岩石的垂直距离小于该值才生成矿苗。
     * 深埋矿脉不露苗，勘探需要高度与岩层双重判断。
     */
    private static final int INDICATOR_DEPTH = 32;

    /** 中心区块掷骰的混入常数（与群峦同款， decorrelate X/Z两个种子轴） */
    private static final long CHUNK_X_SEED_MULTIPLIER = 61728364132L;
    private static final long CHUNK_Z_SEED_MULTIPLIER = 16298364123L;

    /** 裸露岩面判定：允许作为矿苗载体的岩石方块（惰性构建） */
    private static volatile Set<Block> ROCKY_SURFACE_BLOCKS;

    public OreVeinFeature(Codec<OreVeinConfiguration> codec) {
        super(codec);
    }

    /*
     * 每区块执行一次：扫描候选中心区块，放置所有"够得着"当前区块的矿脉
     * 在本区块内的部分。
     */
    @Override
    public boolean place(FeaturePlaceContext<OreVeinConfiguration> context) {
        WorldGenLevel level = context.level();
        ChunkPos chunkPos = new ChunkPos(context.origin());
        OreVeinConfiguration config = context.config();

        boolean placed = false;
        int radius = chunkRadius(config.sizeMax());
        for (int cx = chunkPos.x - radius; cx <= chunkPos.x + radius; cx++) {
            for (int cz = chunkPos.z - radius; cz <= chunkPos.z + radius; cz++) {
                placed |= placeVeinFromCenterChunk(level, chunkPos, cx, cz, config);
            }
        }
        return placed;
    }

    /** 缩放后的实际矿脉半径（保证至少4格，让小型矿脉也不至于过碎） */
    private static int effectiveRadius(int size) {
        return Math.max(4, Math.round(size * SIZE_SCALE));
    }

    /**
     * 按矿脉形态计算水平半径(rxz)与垂直半高(ry)。
     * <p>
     * LAYER 横向放大、垂直压薄；PIPE 横向缩小、纵向拉长；
     * DIKE 保持中等厚度、由后续方向过滤拉长；DISSEMINATED 整体放大但密度低。
     * </p>
     */
    private static int[] veinRadii(int size, ModVeins.VeinShape shape) {
        int base = effectiveRadius(size);
        return switch (shape) {
            case LAYER -> new int[]{Math.max(6, Math.round(base * 1.3F)), Math.max(2, Math.round(size * 0.25F))};
            case PIPE -> new int[]{Math.max(3, Math.round(size * 0.5F)), Math.max(6, Math.round(base * 1.6F))};
            case DIKE -> new int[]{Math.max(6, Math.round(base * 1.2F)), Math.max(4, Math.round(base * 0.8F))};
            case DISSEMINATED -> new int[]{Math.max(6, Math.round(base * 1.25F)), Math.max(4, Math.round(base * 0.8F))};
            default -> new int[]{base, Math.max(4, Math.round(base * VERTICAL_SQUASH))};
        };
    }

    /** 能影响当前区块的最远中心区块距离（按缩放后实际半径（含外围游离矿壳）折算成区块数，向上取整） */
    private static int chunkRadius(int size) {
        int radius = (int) Math.ceil(effectiveRadius(size) * OUTER_HALO_RADIUS_FACTOR);
        return (radius + 15) / 16;
    }

    /*
     * 尝试一个候选中心区块：确定性掷骰 → 推导矿脉中心 → 宿主门控 →
     * 放置椭球与当前区块的交集。
     */
    private boolean placeVeinFromCenterChunk(WorldGenLevel level, ChunkPos current,
                                             int centerChunkX, int centerChunkZ,
                                             OreVeinConfiguration config) {
        // 确定性掷骰（群峦getVeinsAtChunk）：只依赖世界种子、区块坐标与矿脉种子，
        // 相邻区块扫描到同一中心区块时结果一致——跨区块矿脉的根基
        RandomSource rand = new XoroshiroRandomSource(
                level.getSeed() ^ centerChunkX * CHUNK_X_SEED_MULTIPLIER,
                config.seed() ^ centerChunkZ * CHUNK_Z_SEED_MULTIPLIER);
        if (rand.nextInt(config.rarity()) != 0) {
            return false;
        }

        // 每条矿脉在 [sizeMin, sizeMax] 内有一个独立的半径（同一条矿脉跨区块一致）
        int size = config.size(rand);

        ModVeins.VeinShape shape;
        try {
            shape = ModVeins.VeinShape.valueOf(config.shape());
        } catch (IllegalArgumentException e) {
            shape = ModVeins.VeinShape.ELLIPSOID;
        }
        int[] radii = veinRadii(size, shape);
        int rxz = radii[0];
        int ry = radii[1];

        // 矿脉中心：区块内随机位置；高度向内收缩ry（群峦defaultYPos），
        // 让椭球上下缘不超出minY~maxY，避免被高度范围裁切
        int originX = (centerChunkX << 4) + rand.nextInt(16);
        int originZ = (centerChunkZ << 4) + rand.nextInt(16);
        int yRange = config.maxY() - config.minY() - 2 * ry;
        int originY = yRange > 0
                ? config.minY() + ry + rand.nextInt(yRange)
                : (config.minY() + config.maxY()) / 2;
        BlockPos origin = new BlockPos(originX, originY, originZ);

        // TFC project_offset：投影矿脉的确定性水平偏移（±15格）
        int projectOffsetX = 0, projectOffsetZ = 0;
        if (config.projectOffset()) {
            RandomSource offsetRandom = RandomSource.create(level.getSeed() ^ origin.asLong() ^ 0x5EEDC0DEL);
            projectOffsetX = offsetRandom.nextInt(16) - offsetRandom.nextInt(16);
            projectOffsetZ = offsetRandom.nextInt(16) - offsetRandom.nextInt(16);
        }

        // 形状随机由世界种子+矿脉中心派生：所有区块的流程消耗同一序列，
        // 密度分布在跨区块处完全一致
        RandomSource shapeRand = RandomSource.create(level.getSeed() ^ origin.asLong());

        int bandHalf = Math.max(1, ry / 3);
        int chunkMinX = current.getMinBlockX();
        int chunkMaxX = current.getMaxBlockX();
        int chunkMinZ = current.getMinBlockZ();
        int chunkMaxZ = current.getMaxBlockZ();
        int footprint = 2 * rxz + 1;
        // 每柱（dx,dz）实际成矿的最高Y：供矿苗深度约束使用
        int[] columnTop = new int[footprint * footprint];
        Arrays.fill(columnTop, Integer.MIN_VALUE);

        boolean placed = false;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -rxz; dx <= rxz; dx++) {
            for (int dz = -rxz; dz <= rxz; dz++) {
                double horizontal = (double) (dx * dx + dz * dz) / (double) (rxz * rxz);
                if (horizontal > 1.0) continue;
                // DIKE：沿 X 方向拉长的岩墙状矿脉，窄的 Z 向剔除，形成走向矿墙
                if (shape == ModVeins.VeinShape.DIKE && Math.abs(dz) > Math.abs(dx) * 0.35 + 1) continue;
                double radialDist = Math.sqrt(horizontal);

                // 椭球方程：该柱位的垂直半高
                int columnHalf = (int) (Math.sqrt(1.0 - horizontal) * ry);
                int topIndex = (dx + rxz) * footprint + (dz + rxz);
                int x = originX + dx;
                int z = originZ + dz;
                // TFC project_to_surface：该列矿体基准Y = 地表高度，矿体随地形起伏
                int baseY = config.projectToSurface()
                        ? level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x + projectOffsetX, z + projectOffsetZ)
                        : originY;

                for (int dy = -columnHalf; dy <= columnHalf; dy++) {
                    // 连续密度衰减：中心接近config.density，越靠边越低，但边缘不会突然归零
                    double vertical = (double) dy / Math.max(1, columnHalf);
                    double falloff = Math.pow(1.0 - radialDist, 0.7)
                            * Math.max(0.0, 1.0 - vertical * vertical);
                    double chance = config.density() * (MIN_DENSITY_FACTOR + (1.0 - MIN_DENSITY_FACTOR) * falloff);
                    if (shapeRand.nextFloat() >= chance) continue;

                    int y = baseY + dy;
                    // 跨区块一致性的关键：只写当前区块，其余部分交给对应区块的流程
                    if (x < chunkMinX || x > chunkMaxX || z < chunkMinZ || z > chunkMaxZ) continue;

                    cursor.set(x, y, z);
                    BlockState host = level.getBlockState(cursor);
                    // 宿主过滤（第二级）：只替换允许的岩石；岩区边界穿过矿体时沿边界裁切
                    if (!config.isHost(host.getBlock())) continue;

                    BlockState ore;
                    boolean betweenInBand = config.between().isPresent()
                            && dy >= -bandHalf && dy <= bandHalf;
                    if (betweenInBand && shapeRand.nextFloat() < BETWEEN_CHANCE) {
                        ore = config.between().get().forState(host);
                    } else if (config.sporadic().isPresent() && radialDist > OUTER_HALO_START
                            && shapeRand.nextFloat() < SPORADIC_CHANCE * (1.0F + 2.0F
                            * (float) ((radialDist - OUTER_HALO_START) / (1.0F - OUTER_HALO_START)))) {
                        // 伴生晕：只在矿脉外围，越靠边缘概率越高
                        ore = config.sporadic().get().forState(host);
                    } else {
                        ore = (dy < 0 ? config.primary() : config.secondary()).forState(host);
                    }
                    // 宿主无对应岩种变体时跳过（保险：宿主过滤已保证映射存在）
                    if (ore == null) continue;
                    level.setBlock(cursor, ore, 2);
                    placed = true;
                    if (y > columnTop[topIndex]) columnTop[topIndex] = y;
                }
            }
        }

        // 矿床外围游离矿物：主椭球体之外的低密度零星矿石，让矿脉周围有少量“找矿线索”，
        // 但不会像之前的原版散矿那样铺满整个地下。

        // 地表矿苗 + 地下指示物：对齐 TFC 逐列扫描，只有当前区块内的列才放置
        // （每列只调用一次，由 indicator.surfaceRarity / undergroundRarity 控制频率）
        for (int dx = -rxz; dx <= rxz; dx++) {
            for (int dz = -rxz; dz <= rxz; dz++) {
                int x = originX + dx;
                int z = originZ + dz;
                if (x < chunkMinX || x > chunkMaxX || z < chunkMinZ || z > chunkMaxZ) continue;

                int topIndex = (dx + rxz) * footprint + (dz + rxz);
                int maxVeinY = columnTop[topIndex];
                if (maxVeinY == Integer.MIN_VALUE) continue;
                placeSurfaceIndicator(level, cursor, x, z, maxVeinY, config, shapeRand);
                placeUndergroundIndicator(level, cursor, x, z, config.minY(), config.maxY(), config, shapeRand);
            }
        }
        return placed;
    }

    /*
     * 矿床外围竖向游离矿壳（竖直椭球状散矿）。
     * <p>
     * 在主椭球体之外包一圈稀疏的游离矿，且上下延伸范围大于水平方向，
     * 整体呈“竖直椭球”：
     * <pre>
     *        ●
     *      ● ● ●
     *    ●  ■■■  ●
     *      ● ● ●
     *        ●
     * </pre>
     * 玩家在矿体上下方或四周都能看到零星矿石，顺着就能找到主矿体。
     * </p>
     */

    /*
     * 地表指示矿（GTM式）：
     * 矿脉中心投影到地表，在地面放一小片散矿，让玩家从地表发现矿脉方向。
     * 不管矿脉埋多深，地表都会有指示（深度约束仅控制rarity）。
     *
     * 流程：
     * 1) rarity掷骰决定是否在此区块生成指示矿
     * 2) 以矿脉中心XZ为圆心，radius为半径画圆
     * 3) 圆内每格以density概率放指示矿块（中心必放）
     * 4) 指示矿放在地表方块上方（草地/泥土上）
     */
    /**
     * 放置地表指示矿（对齐TFC VeinFeature above-ground indicator）。
     * 随机偏移15格，在地表高度检查深度差，放置矿物碎块。
     */
    private void placeSurfaceIndicator(WorldGenLevel level, BlockPos.MutableBlockPos cursor,
                                       int x, int z, int maxVeinY, OreVeinConfiguration config,
                                       RandomSource random) {
        var indOpt = config.indicator();
        if (indOpt.isEmpty()) return;
        var ind = indOpt.get();
        if (ind.surfaceRarity() <= 0) return;
        if (random.nextInt(ind.surfaceRarity()) != 0) return;

        String mineral = ind.mineral();
        if (mineral == null || mineral.isEmpty()) return;

        // TFC: 在3x3区块范围内随机偏移
        int ix = x + random.nextInt(15) - random.nextInt(15);
        int iz = z + random.nextInt(15) - random.nextInt(15);
        int iy = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, ix, iz);

        // TFC: 深度检查——地表与矿脉中心距离必须小于depth
        if (Math.abs(iy - maxVeinY) >= ind.depth()) return;

        // 水下不放置碎块
        if (!level.getFluidState(new BlockPos(ix, iy, iz)).isEmpty()) return;

        cursor.set(ix, iy, iz);
        BlockState stateAt = level.getBlockState(cursor);

        // TFC: 必须在可替换方块中放置
        if (!(stateAt.isAir() || stateAt.canBeReplaced())) return;

        placeLooseOre(level, mineral, cursor);
    }

    /** 裸露岩面判定表：原版石头/深层石 + 全部区域岩种 */
    private static Set<Block> rockySurfaceBlocks() {
        Set<Block> blocks = ROCKY_SURFACE_BLOCKS;
        if (blocks == null) {
            blocks = new HashSet<>();
            blocks.add(Blocks.STONE);
            blocks.add(Blocks.DEEPSLATE);
            blocks.add(Blocks.GRAVEL);
            for (ModRocks.RockType rock : ModRocks.ROCK_TYPES) {
                blocks.add(rock.block().get());
            }
            ROCKY_SURFACE_BLOCKS = blocks;
        }
        return blocks;
    }

    private static boolean isRockySurface(Block block) {
        return rockySurfaceBlocks().contains(block);
    }

    /*
     * 地下矿苗（对齐TFC VeinFeature below-ground indicator）：
     * 在矿脉附近随机位置放置矿物碎块，引导玩家发现深部矿脉。
     * 必须在地表以下至少5格。
     */
    private void placeUndergroundIndicator(WorldGenLevel level, BlockPos.MutableBlockPos cursor,
                                           int x, int z, int veinMinY, int veinMaxY,
                                           OreVeinConfiguration config, RandomSource rand) {
        var indOpt = config.indicator();
        if (indOpt.isEmpty()) return;
        var ind = indOpt.get();
        if (ind.undergroundCount() <= 0) return;

        String mineral = ind.mineral();
        if (mineral == null || mineral.isEmpty()) return;

        // TFC: undergroundRarity=1 表示每次必定放置
        for (int i = 0; i < ind.undergroundCount(); i++) {
            if (ind.undergroundRarity() > 1 && rand.nextInt(ind.undergroundRarity()) != 0) continue;

            // TFC: 在3x3区块范围内随机偏移
            int ix = x + rand.nextInt(15) - rand.nextInt(15);
            int iy = veinMinY + (veinMaxY > veinMinY ? rand.nextInt(veinMaxY - veinMinY) : 0)
                    + rand.nextInt(32) - rand.nextInt(8); // 偏上方
            int iz = z + rand.nextInt(15) - rand.nextInt(15);

            int maxGroundY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, ix, iz);
            if (iy > maxGroundY - 5) continue; // TFC: 必须在地表以下至少5格

            cursor.set(ix, iy, iz);
            BlockState stateAt = level.getBlockState(cursor);
            if (!(stateAt.isAir() || stateAt.canBeReplaced())) continue;

            placeLooseOre(level, mineral, cursor);
        }
    }

    /**
     * 在指定位置放置矿物碎块（TFC GroundcoverBlock风格）。
     */
    private void placeLooseOre(WorldGenLevel level, String mineral, BlockPos pos) {
        var rockBlock = ModBlocks.SURFACE_ROCKS.get(mineral);
        if (rockBlock == null) return;
        BlockState state = rockBlock.get().defaultBlockState();
        if (state.canSurvive(level, pos)) {
            level.setBlock(pos, state, 2);
        }
    }
}
