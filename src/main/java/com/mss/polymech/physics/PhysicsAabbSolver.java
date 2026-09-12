package com.mss.polymech.physics;

/**
 * 体素 AABB 碰撞推出求解器（纯 Java，不依赖 Minecraft，可脱离游戏单测）。
 *
 * <p>用途：物理体（飞船/建筑）的方块只存在于 Rapier，世界里没有对应方块，
 * 因此原版碰撞完全感知不到它们 —— 玩家会直接穿过去。这里把"玩家碰撞箱"
 * 与"物理体的方块集合"做 AABB 求解，算出把玩家推出所需的最小位移。</p>
 *
 * <p>坐标约定：方块 {@code (x,y,z)} 占据 {@code [x,x+1] × [y,y+1] × [z,z+1]}，
 * 与 Rapier Voxels 和 Minecraft 方块一致。所有计算在<b>物理体局部空间</b>进行，
 * 调用方负责把玩家碰撞箱变换进来、把结果变换回世界空间。</p>
 */
public final class PhysicsAabbSolver {

    private PhysicsAabbSolver() {
    }

    /**
     * 计算把盒子推出所有相交方块所需的最小位移。
     *
     * @param boxMin    盒子最小角（局部空间），长度 3
     * @param boxMax    盒子最大角（局部空间），长度 3
     * @param blocks    方块坐标扁平数组 {@code [dx,dy,dz, dx,dy,dz, ...]}
     * @param out       输出长度 ≥ 4：{@code [dx, dy, dz, standing]}，
     *                  standing 为 1 表示"位移主要来自向上"（站在方块顶上）
     * @return 是否发生了碰撞
     */
    public static boolean pushOut(double[] boxMin, double[] boxMax, int[] blocks, double[] out) {
        double mx = boxMin[0], my = boxMin[1], mz = boxMin[2];
        boolean collided = false;
        boolean standing = false;

        int count = blocks.length / 3;
        // 多轮：一次推出后可能又与相邻方块相交（例如墙角 + 地面），所以迭代几轮收敛
        for (int pass = 0; pass < 4; pass++) {
            boolean changedThisPass = false;
            for (int i = 0; i < count; i++) {
                double bx = blocks[i * 3];
                double by = blocks[i * 3 + 1];
                double bz = blocks[i * 3 + 2];

                double ex = Math.min(boxMax[0], bx + 1.0) - Math.max(mx, bx);
                double ey = Math.min(boxMax[1], by + 1.0) - Math.max(my, by);
                double ez = Math.min(boxMax[2], bz + 1.0) - Math.max(mz, bz);
                if (ex <= 0.0 || ey <= 0.0 || ez <= 0.0) {
                    continue; // 不相交
                }
                collided = true;
                changedThisPass = true;

                // 沿穿透最浅的轴推出
                if (ey <= ex && ey <= ez) {
                    double dir = (my + (boxMax[1] - my) * 0.5) >= (by + 0.5) ? 1.0 : -1.0;
                    double d = ey * dir;
                    my += d;
                    if (dir > 0) {
                        standing = true;
                    }
                } else if (ex <= ez) {
                    double dir = (mx + (boxMax[0] - mx) * 0.5) >= (bx + 0.5) ? 1.0 : -1.0;
                    mx += ex * dir;
                } else {
                    double dir = (mz + (boxMax[2] - mz) * 0.5) >= (bz + 0.5) ? 1.0 : -1.0;
                    mz += ez * dir;
                }
            }
            if (!changedThisPass) {
                break;
            }
        }

        out[0] = mx - boxMin[0];
        out[1] = my - boxMin[1];
        out[2] = mz - boxMin[2];
        out[3] = standing ? 1.0 : 0.0;
        return collided;
    }
}
