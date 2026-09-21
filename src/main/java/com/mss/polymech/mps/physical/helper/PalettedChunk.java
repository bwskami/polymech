package com.mss.polymech.mps.physical.helper;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 64³ 方块容器 —— <b>与 {@code org.polaris2023.mps.physical.helper.PalettedChunk} 同形的公开 API</b>
 * 的自有实现（clean-room；见 {@code docs/mps-clone-plan.md}）。
 *
 * <p>它是物理体的"方块快照"单元：{@code PhysicalBody} 用 2×2×2 个本对象装一个 128³ 的体，
 * {@code PhysicalChunk}（地形）也用它表达空间占用。方块 id 用原版全局调色板
 * （{@link Block#stateById(int)}），{@code 0} = 空气。</p>
 *
 * <p><b>与本项目取舍有关的一处实现差异（不影响行为）</b>：MPS 内部做了 16 位以内调色板打包、
 * 超出后退化成 {@code int[]}；这里直接用一个 {@code int[262144]}（1 MB/块）+ 调色板缓存，
 * 语义（{@code get}/{@code set}/{@code toInt3}/{@code colliderArray}/AABB）逐一对齐，
 * 只是内存换实现简单 —— 与 MPS 自己的 direct 退化分支同量级（其 16 位打包为 512 KB）。</p>
 *
 * @see PhysicalChunk
 */
public class PalettedChunk {

    /** 边长（方块）。 */
    public static final int WIDTH = 64;
    /** 单元数（64³）。 */
    public static final int SIZE = 262144;

    /** "这个方块 id 有没有碰撞形状"的缓存（原版 id 是全局的，缓存跨实例共享）。 */
    private static final Map<Integer, Boolean> COLLISION_CACHE = new ConcurrentHashMap<>();

    private final int[] cells = new int[SIZE];

    /** 非空方块的包围盒（局部坐标，格）；空块时为空盒。 */
    private final IntAABB aabb = new IntAABB();

    /**
     * @param fillId 初始填满的方块 id；{@code 0} = 全空气
     */
    public PalettedChunk(int fillId) {
        this.aabb.setEmpty();
        if (fillId != 0) {
            Arrays.fill(this.cells, fillId);
            this.aabb.setMin(0, 0, 0).setMax(WIDTH - 1, WIDTH - 1, WIDTH - 1);
        }
    }

    /** 格索引：{@code x + (z << 6) + (y << 12)}（与 MPS 相同，保证 {@code colliderArray()} 的遍历序一致）。 */
    public static int index(int x, int y, int z) {
        return x + (z << 6) + (y << 12);
    }

    /**
     * 从三维数组建块。
     *
     * @return 全空气时返回 {@code null}（与 MPS 一致，调用方据此跳过空子块）
     */
    public static PalettedChunk from(int[][][] arr) {
        boolean any = false;
        for (int x = 0; x < WIDTH && !any; x++) {
            for (int y = 0; y < WIDTH && !any; y++) {
                for (int z = 0; z < WIDTH; z++) {
                    if (arr[x][y][z] != 0) {
                        any = true;
                        break;
                    }
                }
            }
        }
        if (!any) {
            return null;
        }
        PalettedChunk chunk = new PalettedChunk(0);
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < WIDTH; y++) {
                for (int z = 0; z < WIDTH; z++) {
                    chunk.cells[index(x, y, z)] = arr[x][y][z];
                }
            }
        }
        chunk.recomputeAABB();
        return chunk;
    }

    public int get(int x, int y, int z) {
        return this.cells[index(x, y, z)];
    }

    public void set(int x, int y, int z, int id) {
        int i = index(x, y, z);
        int old = this.cells[i];
        if (old == id) {
            return;
        }
        this.cells[i] = id;
        updateAABB(x, y, z, id, old);
    }

    /** 包围盒副本（局部坐标）。 */
    public IntAABB getAABB() {
        return new IntAABB(this.aabb);
    }

    public void fillRegion(int sx, int sy, int sz, int ex, int ey, int ez, int id) {
        for (int x = sx; x <= ex; x++) {
            for (int y = sy; y <= ey; y++) {
                for (int z = sz; z <= ez; z++) {
                    set(x, y, z, id);
                }
            }
        }
    }

    public int[][][] toInt3() {
        int[][][] out = new int[WIDTH][WIDTH][WIDTH];
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < WIDTH; y++) {
                for (int z = 0; z < WIDTH; z++) {
                    out[x][y][z] = this.cells[index(x, y, z)];
                }
            }
        }
        return out;
    }

    /**
     * 空间占用布尔数组（索引同 {@link #index}）：有碰撞形状的格子为 true。
     *
     * <p>这一份就是喂给原生体素碰撞体的东西（{@code PhysicalBody} → {@code ColliderBody.VOXEL}）。</p>
     */
    public boolean[] colliderArray() {
        boolean[] out = new boolean[SIZE];
        for (int i = 0; i < SIZE; i++) {
            out[i] = hasCollision(this.cells[i]);
        }
        return out;
    }

    /** 取出所有"有碰撞"的格子的打包坐标（本项目原生体素碰撞体直接吃这个）。 */
    public long[] solidCells(long[] out, int[] countOut) {
        int n = 0;
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < WIDTH; y++) {
                for (int z = 0; z < WIDTH; z++) {
                    if (!hasCollision(this.cells[index(x, y, z)])) {
                        continue;
                    }
                    if (out == null || n >= out.length) {
                        countOut[0] = n;
                        return out;
                    }
                    out[n++] = com.mss.polymech.physics.NativePhysics.packCell(x, y, z);
                }
            }
        }
        countOut[0] = n;
        return out;
    }

    /** 该方块 id 是否有碰撞形状（带跨实例缓存）。 */
    public static boolean hasCollision(int blockStateId) {
        if (blockStateId == 0) {
            return false;
        }
        return COLLISION_CACHE.computeIfAbsent(blockStateId, id -> {
            try {
                return !Block.stateById(id)
                        .getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                        .isEmpty();
            } catch (Throwable t) {
                // 未知/已卸载的 id：当作无碰撞，绝不让物理线程因它抛异常
                return false;
            }
        });
    }

    private void updateAABB(int x, int y, int z, int newId, int oldId) {
        // 与 MPS 一致：包围盒按"非空气"扩张（不是按'有碰撞'），删格只在边界上才重算
        if (newId != 0) {
            this.aabb.union(x, y, z);
        } else if (oldId != 0
                && (x == this.aabb.minX || x == this.aabb.maxX
                || y == this.aabb.minY || y == this.aabb.maxY
                || z == this.aabb.minZ || z == this.aabb.maxZ)) {
            recomputeAABB();
        }
    }

    private void recomputeAABB() {
        this.aabb.setEmpty();
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < WIDTH; y++) {
                for (int z = 0; z < WIDTH; z++) {
                    if (this.cells[index(x, y, z)] != 0) {
                        this.aabb.union(x, y, z);
                    }
                }
            }
        }
    }
}
