package com.mss.polymech.prospecting;

import com.mss.polymech.block.ModBlocks;
import com.mss.polymech.worldgen.ModMinerals;
import com.mss.polymech.worldgen.ModRocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.IdentityHashMap;
import java.util.Map;

/*
 * 探矿扫描：确定性地计算岩石类型 + 扫描已加载区块内的矿物矿石。
 * <p>
 * 结果按"列"组织（每列 = 一个方块XZ坐标），网格尺寸 = (2×半径+1)×16，
 * 即格雷探矿仪的同款精度：每个方块列一个单元格。
 * 岩石类型由{@link ModRocks#rockTypeAtBlock}确定性计算（噪声，无需扫描），
 * 矿物则扫描 Y∈[-64,128] 范围内已加载区块的矿石方块。
 * </p>
 *
 * <h2>编码：</h2>
 * <p>
 * 每列打包为一个int：低8位=岩石索引，高24位=矿物索引+1（0表示无矿）。
 * 结果序列化为 "gridSize,v0,v1,..." 字符串，经LDLib2的stringS2C一次性同步到客户端。
 * </p>
 */
public final class ProspectorScan {

    /** 扫描半径（区块数）：5×5=25个区块，与格雷LV探矿仪一致 */
    public static final int RADIUS_CHUNKS = 2;

    /** 矿物扫描高度带（覆盖全部矿脉与散矿高度） */
    public static final int SCAN_MIN_Y = -64;
    public static final int SCAN_MAX_Y = 128;

    private ProspectorScan() {
    }

    /** 深度带常量：用于探矿图区分浅/中/深矿（0=该列无矿） */
    public static final int DEPTH_SHALLOW = 1;
    public static final int DEPTH_MIDDLE = 2;
    public static final int DEPTH_DEEP = 3;

    /** 扫描结果：按列组织的岩石索引、矿物索引与矿物深度带 */
    public static final class Result {
        public final int gridSize;
        public final int[] rockTypes;
        public final int[] oreMinerals;
        public final int[] oreDepths;

        Result(int gridSize, int[] rockTypes, int[] oreMinerals, int[] oreDepths) {
            this.gridSize = gridSize;
            this.rockTypes = rockTypes;
            this.oreMinerals = oreMinerals;
            this.oreDepths = oreDepths;
        }

        /*
         * 每列打包为一个int：
         * 位 0~3  = 深度带（0无矿，1浅层，2中层，3深层）
         * 位 4~11 = 岩石索引
         * 位 12~31= 矿物索引+1（0表示无矿）
         */
        public String encode() {
            StringBuilder sb = new StringBuilder(rockTypes.length * 6);
            sb.append(gridSize);
            for (int i = 0; i < rockTypes.length; i++) {
                int oreField = oreMinerals[i] + 1; // -1 → 0（无矿）
                int packed = (oreDepths[i] & 0xF) | ((rockTypes[i] & 0xFF) << 4) | ((oreField & 0xFFFFF) << 12);
                sb.append(',').append(packed);
            }
            return sb.toString();
        }

        public static Result decode(String encoded) {
            String[] parts = encoded.split(",");
            int gridSize = Integer.parseInt(parts[0]);
            int count = gridSize * gridSize;
            int[] rockTypes = new int[count];
            int[] oreMinerals = new int[count];
            int[] oreDepths = new int[count];
            for (int i = 0; i < count; i++) {
                int packed = Integer.parseInt(parts[i + 1]);
                oreDepths[i] = packed & 0xF;
                rockTypes[i] = (packed >> 4) & 0xFF;
                oreMinerals[i] = (packed >> 12) - 1;
            }
            return new Result(gridSize, rockTypes, oreMinerals, oreDepths);
        }
    }

    /** 矿石方块 → 矿物索引（惰性构建，注册表冻结后首次使用时生成） */
    private static volatile Map<Block, Integer> oreBlockToMineralIndex;

    /*
     * 扫描玩家所在区域：以玩家区块为中心的 (2×半径+1)×(2×半径+1) 区块网格。
     *
     * @param level 世界（服务端）
     * @param centerChunkX 中心区块X
     * @param centerChunkZ 中心区块Z
     */
    public static Result scan(ServerLevel level, int centerChunkX, int centerChunkZ) {
        Map<Block, Integer> oreMap = oreBlockToMineralIndex();
        int gridSize = (RADIUS_CHUNKS * 2 + 1) * 16;
        int[] rockTypes = new int[gridSize * gridSize];
        int[] oreMinerals = new int[gridSize * gridSize];
        int[] oreDepths = new int[gridSize * gridSize];

        int minChunkX = centerChunkX - RADIUS_CHUNKS;
        int minChunkZ = centerChunkZ - RADIUS_CHUNKS;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int cx = 0; cx < gridSize; cx++) {
            for (int cz = 0; cz < gridSize; cz++) {
                int index = cz * gridSize + cx;
                int chunkX = minChunkX + cx / 16;
                int chunkZ = minChunkZ + cz / 16;
                int x = (chunkX << 4) + (cx & 15);
                int z = (chunkZ << 4) + (cz & 15);

                // 岩石类型：确定性噪声 + 群系偏好，无需扫描
                BlockPos rockPos = new BlockPos(x, 0, z);
                rockTypes[index] = ModRocks.ROCK_TYPES.indexOf(
                        ModRocks.rockTypeAt(x, z, ModRocks.SURFACE_ROCK_MIN_Y + 1,
                                level.getSeed(), level.getBiome(rockPos)));

                // 矿物：扫描已加载区块内的矿石方块
                oreMinerals[index] = -1;
                oreDepths[index] = 0;
                if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                    continue;
                }
                for (int y = SCAN_MIN_Y; y <= SCAN_MAX_Y; y++) {
                    cursor.set(x, y, z);
                    Integer mineral = oreMap.get(level.getBlockState(cursor).getBlock());
                    if (mineral != null) {
                        oreMinerals[index] = mineral;
                        oreDepths[index] = depthBand(y);
                        break;
                    }
                }
            }
        }
        return new Result(gridSize, rockTypes, oreMinerals, oreDepths);
    }

    /** Y坐标 → 深度带：浅层(>=0)、中层(-32~0)、深层(<-32) */
    private static int depthBand(int y) {
        if (y >= 0) return DEPTH_SHALLOW;
        if (y >= -32) return DEPTH_MIDDLE;
        return DEPTH_DEEP;
    }

    /*
     * 矿石方块 → 矿物索引映射（惰性构建）。
     * 与ModCommands.oreToMineral同源，但映射到ModMinerals定义表的下标。
     */
    private static Map<Block, Integer> oreBlockToMineralIndex() {
        Map<Block, Integer> map = oreBlockToMineralIndex;
        if (map == null) {
            map = new IdentityHashMap<>();
            var defs = ModMinerals.getDefinitions();
            for (int i = 0; i < defs.size(); i++) {
                var oreSet = ModBlocks.MINERAL_ORES.get(defs.get(i).mineral());
                if (oreSet == null) continue;
                // 全部岩种变体（石头/深板岩/21种群峦岩种）都映射回矿物下标
                for (var oreBlock : oreSet.all()) {
                    map.put(oreBlock.get(), i);
                }
            }
            oreBlockToMineralIndex = map;
        }
        return map;
    }
}
