package com.mss.polymech.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Arrays;

/**
 * 方块碰撞形状 → Rapier 碰撞体的转换助手（B1 / Tier 1）。
 *
 * <p><b>为什么必须有它</b>：我们此前把"非空气非流体"的方块<b>一律当成整格体素</b>。
 * 于是台阶（半砖）在物理世界里是一整格 —— 人站在旁边会浮空半格、脚踩不上去；
 * 楼梯变成实心方块、栅栏和锁链变成实心墙、花盆也不能穿过。</p>
 *
 * <p>space 0.1.3 的做法（{@code PhysicalChunk.getFullBlockArrayInChunkQuickly}）：
 * 满碰撞形状（{@code isCollisionShapeFullBlock}）走体素，
 * 非满形状用 {@code state.getCollisionShape(...)} 收成盒子、交给
 * {@code ColliderBody.Type.COMPLEX_VOXEL}（Rapier compound boxes）。我们照做，
 * 只是盒子走 ABI 5 的 {@link NativePhysics#colliderAttachBoxes}。</p>
 *
 * <p><b>形状必须在真实位置上求</b>：栅栏/墙/铁栏杆的碰撞形状取决于<b>邻居</b>
 * （{@code getCollisionShape} 会去读 level 的相邻方块），所以调用方必须传真实
 * 的 {@link BlockGetter} 与真实 {@link BlockPos}，不能拿一个空 level 糊弄 ——
 * 那样所有栅栏都会退化成"孤立柱子"。</p>
 */
public final class PhysicsShapes {

    /** 单个区块最多收集多少盒子（防御性：极端模组方块可能吐出成百上千个盒）。 */
    public static final int MAX_BOXES_PER_CHUNK = 4096;

    private PhysicsShapes() {
    }

    /**
     * 是否是"满格"碰撞形状 —— 这种可以直接当体素用（绝大多数地形方块都是）。
     */
    public static boolean isFullBlock(BlockState state) {
        return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /**
     * 可增长的盒子累积器。
     *
     * <p>刻意不用 {@code ArrayList&lt;Double&gt;}（每格几十次装箱）也不用静态缓冲
     * （单人游戏里客户端与服务端线程都会建地形，静态缓冲会互相踩）。</p>
     */
    public static final class Boxes {

        private double[] data = new double[6 * 64];
        private int size;

        /** 已累积的盒子数。 */
        public int count() {
            return size / 6;
        }

        public boolean isEmpty() {
            return size == 0;
        }

        public void clear() {
            size = 0;
        }

        /** 追加一个盒。 */
        public void add(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            if (size + 6 > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = minX;
            data[size++] = minY;
            data[size++] = minZ;
            data[size++] = maxX;
            data[size++] = maxY;
            data[size++] = maxZ;
        }

        /**
         * 把一个方块的碰撞形状按 {@code (ox, oy, oz)} 偏移后追加进来。
         *
         * @param limit 本次最多再追加多少个盒（0 或负数 = 不再追加）
         * @return 实际追加的数量
         */
        public int addShape(VoxelShape shape, int ox, int oy, int oz, int limit) {
            if (limit <= 0 || shape.isEmpty()) {
                return 0;
            }
            int before = count();
            shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> {
                if (count() - before >= limit) {
                    return;
                }
                add(x1 + ox, y1 + oy, z1 + oz, x2 + ox, y2 + oy, z2 + oz);
            });
            return count() - before;
        }

        /** 取出紧凑数组（每 6 个 double 一个盒）；空时返回长度为 0 的数组。 */
        public double[] toArray() {
            return size == 0 ? EMPTY : Arrays.copyOf(data, size);
        }
    }

    private static final double[] EMPTY = new double[0];
}
