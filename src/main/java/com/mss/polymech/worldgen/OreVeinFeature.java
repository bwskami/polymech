package com.mss.polymech.worldgen;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
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

    /** 全域被sporadic零星矿替换的概率 */
    private static final float SPORADIC_CHANCE = 0.08F;

    /** 椭球垂直压扁系数：透镜状矿体 */
    private static final float VERTICAL_SQUASH = 0.6F;

    /*
     * 矿苗深度约束（群峦Indicator.depth语义）：
     * 矿体顶部与地表岩石的垂直距离小于该值才生成矿苗。
     * 深埋矿脉不露苗，勘探需要高度与岩层双重判断。
     */
    private static final int INDICATOR_DEPTH = 16;

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
        int radius = chunkRadius(config.size());
        for (int cx = chunkPos.x - radius; cx <= chunkPos.x + radius; cx++) {
            for (int cz = chunkPos.z - radius; cz <= chunkPos.z + radius; cz++) {
                placed |= placeVeinFromCenterChunk(level, chunkPos, cx, cz, config);
            }
        }
        return placed;
    }

    /** 能影响当前区块的最远中心区块距离（方块半径size折算成区块数，向上取整） */
    private static int chunkRadius(int size) {
        return (size + 15) / 16;
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

        int rxz = config.size();
        int ry = Math.max(3, Math.round(config.size() * VERTICAL_SQUASH));

        // 矿脉中心：区块内随机位置；高度向内收缩ry（群峦defaultYPos），
        // 让椭球上下缘不超出minY~maxY，避免被高度范围裁切
        int originX = (centerChunkX << 4) + rand.nextInt(16);
        int originZ = (centerChunkZ << 4) + rand.nextInt(16);
        int yRange = config.maxY() - config.minY() - 2 * ry;
        int originY = yRange > 0
                ? config.minY() + ry + rand.nextInt(yRange)
                : (config.minY() + config.maxY()) / 2;
        BlockPos origin = new BlockPos(originX, originY, originZ);

        // 放置期宿主门控（第一级过滤）：中心落在错误岩区则整条作废。
        // 纯噪声计算、无世界读取，各区块的生成流程判定一致
        if (!centerAllowed(level.getSeed(), origin, config)) {
            return false;
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

                // 椭球方程：该柱位的垂直半高
                int columnHalf = (int) (Math.sqrt(1.0 - horizontal) * ry);
                int topIndex = (dx + rxz) * footprint + (dz + rxz);
                for (int dy = -columnHalf; dy <= columnHalf; dy++) {
                    // 密度采样：未成矿的位置直接跳过（序列消耗跨区块一致）
                    if (shapeRand.nextFloat() >= config.density()) continue;

                    int x = originX + dx;
                    int y = originY + dy;
                    int z = originZ + dz;
                    // 跨区块一致性的关键：只写当前区块，其余部分交给对应区块的流程
                    if (x < chunkMinX || x > chunkMaxX || z < chunkMinZ || z > chunkMaxZ) continue;

                    cursor.set(x, y, z);
                    BlockState host = level.getBlockState(cursor);
                    // 宿主过滤（第二级）：只替换允许的岩石；岩区边界穿过矿体时沿边界裁切
                    if (!config.isHost(host.getBlock())) continue;

                    BlockState ore;
                    if (config.between().isPresent() && dy >= -bandHalf && dy <= bandHalf
                            && shapeRand.nextFloat() < BETWEEN_CHANCE) {
                        ore = config.between().get().forState(host);
                    } else if (config.sporadic().isPresent() && shapeRand.nextFloat() < SPORADIC_CHANCE) {
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

        // 地表矿苗：苗位由矿脉随机确定性派生（各区块消耗同一序列），
        // 只由苗位所在区块的流程放置，天然无跨区块写入与重复
        int attempts = 1 + shapeRand.nextInt(3);
        for (int i = 0; i < attempts; i++) {
            int x = originX + shapeRand.nextInt(footprint) - rxz;
            int z = originZ + shapeRand.nextInt(footprint) - rxz;
            if (x < chunkMinX || x > chunkMaxX || z < chunkMinZ || z > chunkMaxZ) continue;

            int topIndex = (x - originX + rxz) * footprint + (z - originZ + rxz);
            int maxVeinY = columnTop[topIndex];
            if (maxVeinY == Integer.MIN_VALUE) continue;
            placeSurfaceIndicator(level, cursor, x, z, maxVeinY, config);
        }
        return placed;
    }

    /*
     * 放置期宿主门控：中心所在岩区的岩种必须在宿主列表内。
     * 深层石带（岩层底部以下）只可能是深层石；-8~0过渡带石块与深层石混杂，
     * 两者任一被允许即放行。
     */
    private static boolean centerAllowed(long levelSeed, BlockPos origin, OreVeinConfiguration config) {
        if (origin.getY() < ModRocks.ROCK_LAYER_MIN_Y) {
            return config.isHost(Blocks.DEEPSLATE);
        }
        Block centerRock = ModRocks.rockTypeAtBlock(origin.getX(), origin.getZ(), levelSeed)
                .block().get();
        return config.isHost(centerRock)
                || (origin.getY() < 0 && config.isHost(Blocks.DEEPSLATE));
    }

    /*
     * 地表矿苗（群峦式约束）：
     * 1) 深度约束：该柱矿体顶部距地表岩石 < INDICATOR_DEPTH 才出苗；
     * 2) 载体约束：只替换<b>裸露</b>的岩石表面（上方为空气或无碰撞的小植物/雪），
     *    草方块/泥土/树叶等覆盖层直接终止探测——矿苗绝不浮在草上或树叶上；
     * 3) 形态约束：替换岩面方块本体形成嵌入露头，不产生悬浮方块。
     * 高度图用OCEAN_FLOOR_WG：与群峦一致，水面不计入地表。
     */
    private void placeSurfaceIndicator(WorldGenLevel level, BlockPos.MutableBlockPos cursor,
                                       int x, int z, int maxVeinY, OreVeinConfiguration config) {
        int surfaceY = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;

        // 自地表向下最多探3格，寻找第一块裸露岩石
        for (int y = surfaceY; y > surfaceY - 3; y--) {
            cursor.set(x, y, z);
            BlockState state = level.getBlockState(cursor);

            if (isRockySurface(state.getBlock())) {
                // 裸露判定：上方必须是空气或无碰撞方块（草、蕨、雪等），
                // 否则说明岩面被覆盖（如草方块下压着的石头），不出苗
                BlockState above = level.getBlockState(cursor.above());
                if (!above.isAir() && !above.getCollisionShape(level, cursor.above()).isEmpty()) {
                    break;
                }
                // 深度约束：矿体顶部离此岩面太远则不出苗
                if (Math.abs(y - maxVeinY) >= INDICATOR_DEPTH) {
                    break;
                }
                // 嵌入露头：替换岩面方块本体，按宿主选对应岩种矿石变体
                BlockState indicatorOre = config.primary().forState(state);
                if (indicatorOre == null) break;
                level.setBlock(cursor, indicatorOre, 2);
                break;
            }
            // 非岩石、非空气（泥土/草/树叶/沙等覆盖层）：终止本柱探测
            if (!state.isAir()) {
                break;
            }
        }
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
}
