package com.mss.polymech.mps.physical.helper;

import com.mss.polymech.mps.rapier.helper.ColliderBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 地形区块 —— <b>与 {@code org.polaris2023.mps.physical.helper.PhysicalChunk} 同形的公开 API</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <p>把一个 MC 区块变成两种碰撞体（与 MPS 相同的分工）：</p>
 * <ol>
 *   <li><b>满格体素</b>：整格方块收成 {@link ColliderBody.Type#VOXEL}，原点在区块最小角
 *       {@code (chunkX*16, minY, chunkZ*16)}，格坐标是区块内局部坐标；</li>
 *   <li><b>非满格复合盒</b>：台阶/楼梯/栅栏/墙等收成 {@link ColliderBody.Type#COMPLEX_VOXEL}，
 *       盒数据是<b>世界坐标</b>、碰撞体挂在原点（MPS 就是这么放的，两者在世界空间等价）。</li>
 * </ol>
 *
 * <p><b>尚未实现（S5 再补原生）</b>：{@code getHeightMap} / {@code buildHeightmapColliderBody}
 * 需要原生 HEIGHTMAP 形状（用噪声直接算地面高度，未加载区块也有地）。调用即抛，不静默失真。</p>
 *
 * @see PalettedChunk
 */
public class PhysicalChunk {

    private final ChunkPos chunkPos;

    private ColliderBody voxelColliderBody;
    private ColliderBody heightmapColliderBody;
    private ColliderBody cuboidColliderBody;

    public PhysicalChunk(ChunkPos chunkPos) {
        this.chunkPos = chunkPos;
    }

    // ==================== 方块数组 ====================

    /**
     * 扫描整区块，产出 {@code [boolean[] fullBlocks, double[] boxes]}。
     *
     * @return 长度 2 的列表：第 0 项是 {@code boolean[16 * 段数 * 16 * 16]}，
     *         第 1 项是复合盒（世界坐标，每盒 6 个 double）
     */
    public List<Object> getBlockArray(Level level) {
        LevelChunkSection[] sections = level.getChunk(this.chunkPos.x, this.chunkPos.z).getSections();
        int h = sections.length;
        int minY = level.getMinBuildHeight();
        boolean[] fullBlocks = new boolean[16 * h * 16 * 16];
        List<Double> boxData = new ArrayList<>();

        for (int si = 0; si < h; si++) {
            List<Object> sectionData = getFullBlockArrayInChunkQuickly(level, sections[si], this.chunkPos, si, minY);
            boolean[] sectionFull = (boolean[]) sectionData.get(0);
            double[] sectionBoxes = (double[]) sectionData.get(1);
            int yOffset = si * 4096;
            for (int idx = 0; idx < 4096; idx++) {
                if (sectionFull[idx]) {
                    fullBlocks[yOffset + idx] = true;
                }
            }
            for (double v : sectionBoxes) {
                boxData.add(v);
            }
        }
        return List.of(fullBlocks, toPackedDoubleArray(boxData));
    }

    /**
     * 单个 16³ 段：满格方块打真，非满格方块收成盒。
     *
     * <p>索引约定与 MPS 一致：{@code idx = (y << 8) | (z << 4) | x}（段内局部坐标）。</p>
     */
    public static List<Object> getFullBlockArrayInChunkQuickly(Level level, LevelChunkSection section,
                                                               ChunkPos chunkPos, int sectionIndex, int minY) {
        boolean[] fullBlocks = new boolean[4096];
        if (section == null || section.hasOnlyAir()) {
            return List.of(fullBlocks, new double[0]);
        }

        // ★ 单一方块段落的快路径 —— 对应 space 的**调色板快路径**
        //（space 0.1.3 `PhysicalChunk.getFullBlockArrayInChunkQuickly`：
        //  `bits == 0` 时直接 `Arrays.fill(fullBlocks, true)`，否则先读一次调色板
        //  算出 `paletteIsFull[]`，逐格只剩一次 `storage.get`）。
        //
        // 为什么这条不能省：整段只有一种方块是**最常见**的情况（平坦/行星地表的纯石、
        // 虚空、大片海洋），而这里原本是逐格 4096 次 `getBlockState` +
        // `isCollisionShapeFullBlock`；一个区块几十段 ⇒ **单区块近 10 万次判定**，
        // 并且它发生在 `replaceWithPacketData` 的**同步回调**里（区块到手就做）。
        // 现象就是"每加载一个区块卡一下" —— 2026-09 实测到的正是这个。
        //
        // 判定用公开 API 而不是手搓 `FriendlyByteBuf` 里的调色板二进制格式：
        // `maybeHas` 只遍历调色板项（单一项时 O(1)），且 `GlobalPalette` 会**保守**
        // 返回 true ⇒ 自动回落逐格路径，不会误判成"单一"。等价于 space 的快路径，
        // 但不依赖 MC 内部序列化布局（那是会随版本变的东西）。
        BlockState uniformState = section.getBlockState(0, 0, 0);
        if (!section.getStates().maybeHas(s -> s != uniformState)) {
            if (uniformState.isAir()) {
                return List.of(fullBlocks, new double[0]);
            }
            if (uniformState.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                java.util.Arrays.fill(fullBlocks, true);
                return List.of(fullBlocks, new double[0]);
            }
            // 整段单一但非满格（例如整段栅栏/玻璃）：盒仍然逐格收，但省掉了状态查询
            List<Double> uniformBoxes = new ArrayList<>();
            for (int idx = 0; idx < 4096; idx++) {
                collectBoxData(uniformState, level, chunkPos, sectionIndex, minY, idx, uniformBoxes);
            }
            return List.of(fullBlocks, toPackedDoubleArray(uniformBoxes));
        }

        List<Double> boxData = new ArrayList<>();
        for (int idx = 0; idx < 4096; idx++) {
            int x = idx & 15;
            int y = (idx >> 8) & 15;
            int z = (idx >> 4) & 15;
            BlockState state = section.getBlockState(x, y, z);
            if (state.isAir()) {
                continue;
            }
            if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                fullBlocks[idx] = true;
            } else {
                collectBoxData(state, level, chunkPos, sectionIndex, minY, idx, boxData);
            }
        }
        return List.of(fullBlocks, toPackedDoubleArray(boxData));
    }

    private static void collectBoxData(BlockState state, Level level, ChunkPos chunkPos,
                                       int sectionIndex, int minY, int idx, List<Double> out) {
        int x = chunkPos.x * 16 + (idx & 15);
        int y = minY + sectionIndex * 16 + ((idx >> 8) & 15);
        int z = chunkPos.z * 16 + ((idx >> 4) & 15);
        // 形状必须在真实位置求：栅栏/墙/楼梯的碰撞形状取决于邻居
        VoxelShape shape = state.getCollisionShape(level, new BlockPos(x, y, z), CollisionContext.empty());
        if (!shape.isEmpty()) {
            shape.forAllBoxes((minX, minY2, minZ, maxX, maxY2, maxZ) -> {
                out.add(minX + x);
                out.add(minY2 + y);
                out.add(minZ + z);
                out.add(maxX + x);
                out.add(maxY2 + y);
                out.add(maxZ + z);
            });
        }
    }

    private static double[] toPackedDoubleArray(List<Double> list) {
        double[] packed = new double[list.size()];
        for (int i = 0; i < packed.length; i++) {
            packed[i] = list.get(i);
        }
        return packed;
    }

    // ==================== 碰撞体 ====================

    /**
     * 建体素碰撞体（满格方块）。
     *
     * @param data {@code getBlockArray} 的第 0 项，索引 {@code y*256 + z*16 + x}（全局段序）
     * @param minY 世界最低建筑高度
     * @param h    段数（每段 16 格）
     */
    /**
     * 建体素碰撞体（满格方块）。
     *
     * @param data {@code getBlockArray} 的第 0 项，索引 {@code y*256 + z*16 + x}（全局段序）
     * @param minY 世界最低建筑高度
     * @param h    <b>y 方向的格数</b>（= 段数 × 16，即 {@code sections.length * 16}），
     *             不是段数 —— 调用方 {@code ClientPhysicalWorld/PhysicalChunkManager} 就是这么传的
     */
    public void buildVoxelCollider(boolean[] data, int minY, int h) {
        // 索引序天然一致，**不需要转换**：段的 (y<<8)|(z<<4)|x 就是 ColliderBody.VOXEL 约定的
        // x + z*sx + y*sx*sz（sx = sz = 16）；全局段序 si*4096 恰好等于把 y 拉长到 h 后的 y*256 项。
        this.voxelColliderBody = new ColliderBody(ColliderBody.Type.VOXEL,
                new Vector3d(this.chunkPos.x * 16, minY, this.chunkPos.z * 16),
                new Quaterniond(), 0.0,
                data, 16, h, 16, 1.0, 1.0, 1.0);
    }

    public void destroyVoxelCollider() {
        this.voxelColliderBody = null;
    }

    /** 建复合盒碰撞体（非满格方块，盒是世界坐标，碰撞体在原点）。 */
    public void buildCuboidCollider(double[] boxData) {
        destroyCuboidColliders();
        if (boxData.length != 0) {
            this.cuboidColliderBody = new ColliderBody(ColliderBody.Type.COMPLEX_VOXEL,
                    new Vector3d(0.0, 0.0, 0.0), new Quaterniond(), 0.0, boxData);
        }
    }

    public void destroyCuboidColliders() {
        this.cuboidColliderBody = null;
    }

    /**
     * 高度图碰撞体（用噪声直接算地面高度，未加载区块也有地）。
     *
     * @throws UnsupportedOperationException 原生还没有 HEIGHTMAP 形状（S5）
     */
    public void buildHeightmapColliderBody(double[] data) {
        throw new UnsupportedOperationException(
                "HEIGHTMAP 碰撞体需要原生形状支持（docs/mps-clone-plan.md 的 S5）");
    }

    public void destroyHeightmapColliderBody() {
        this.heightmapColliderBody = null;
    }

    /**
     * 用噪声生成器算 16×16 高度图。
     *
     * @throws UnsupportedOperationException 与 {@link #buildHeightmapColliderBody} 同属 S5
     */
    public double[] getHeightMap(ServerLevel level) {
        throw new UnsupportedOperationException("getHeightMap 属 S5（需原生 HEIGHTMAP 形状）");
    }

    // ==================== 读取 ====================

    public ChunkPos getChunkPos() {
        return chunkPos;
    }

    public ColliderBody getVoxelColliderBody() {
        return voxelColliderBody;
    }

    public ColliderBody getHeightmapColliderBody() {
        return heightmapColliderBody;
    }

    public ColliderBody getCuboidColliderBody() {
        return cuboidColliderBody;
    }
}
