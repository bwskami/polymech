package com.mss.polymech.physics;

/**
 * 物理体方块射线拾取（纯 Java，不依赖 Minecraft，可脱离游戏单测）。
 *
 * <p>物理体的方块不在世界里，原版 {@code clip()} 打不到它们，所以破坏/放置必须自己做射线。
 * 这里对物理体的方块集合逐块做射线-AABB 测试（slab 法），取最近命中，
 * 并给出命中面法线 —— 放置时需要用它决定新方块落在哪一格。</p>
 *
 * <p>坐标约定：所有计算在<b>物理体局部空间</b>进行（调用方把射线变换进来），
 * 方块 {@code (x,y,z)} 占 {@code [x,x+1]³}。</p>
 */
public final class PhysicsRaycast {

    private PhysicsRaycast() {
    }

    /** 命中结果。 */
    public record Hit(double distance, int blockX, int blockY, int blockZ,
                      int normalX, int normalY, int normalZ) {
    }

    /**
     * 射线与方块集合求交。
     *
     * @param origin  射线起点（局部空间）
     * @param dir     单位方向（局部空间）
     * @param maxDist 最大距离
     * @param blocks  方块扁平数组 {@code [dx,dy,dz, ...]}
     * @return 最近命中；无命中返回 {@code null}
     */
    public static Hit cast(double[] origin, double[] dir, double maxDist, int[] blocks) {
        double best = maxDist;
        Hit hit = null;
        int count = blocks.length / 3;
        for (int i = 0; i < count; i++) {
            double bx = blocks[i * 3];
            double by = blocks[i * 3 + 1];
            double bz = blocks[i * 3 + 2];

            double tmin = 0.0;
            double tmax = best;
            int nx = 0, ny = 0, nz = 0;

            // X 轴
            double[] tmp = new double[4];
            if (!slab(origin[0], dir[0], bx, bx + 1.0, tmp)) {
                continue;
            }
            tmin = Math.max(tmin, tmp[0]);
            tmax = Math.min(tmax, tmp[1]);
            if (tmp[3] != 0) {
                nx = (int) tmp[2];
                ny = 0;
                nz = 0;
            }
            // Y 轴
            if (!slab(origin[1], dir[1], by, by + 1.0, tmp)) {
                continue;
            }
            if (tmp[0] > tmin) {
                tmin = tmp[0];
                nx = 0;
                ny = (int) tmp[2];
                nz = 0;
            } else {
                tmax = Math.min(tmax, tmp[1]);
            }
            if (tmin > tmax) {
                continue;
            }
            // Z 轴
            if (!slab(origin[2], dir[2], bz, bz + 1.0, tmp)) {
                continue;
            }
            if (tmp[0] > tmin) {
                tmin = tmp[0];
                nx = 0;
                ny = 0;
                nz = (int) tmp[2];
            } else {
                tmax = Math.min(tmax, tmp[1]);
            }
            if (tmin > tmax || tmin < 0.0 || tmin > best) {
                continue;
            }
            best = tmin;
            hit = new Hit(tmin, (int) bx, (int) by, (int) bz, nx, ny, nz);
        }
        return hit;
    }

    /**
     * 单轴 slab 测试。
     *
     * @param out {@code [tNear, tFar, normalSign, hitThisAxis]}
     */
    private static boolean slab(double origin, double dir, double min, double max, double[] out) {
        if (Math.abs(dir) < 1.0e-9) {
            if (origin < min || origin > max) {
                return false;
            }
            out[0] = Double.NEGATIVE_INFINITY;
            out[1] = Double.POSITIVE_INFINITY;
            out[2] = 0;
            out[3] = 0;
            return true;
        }
        double inv = 1.0 / dir;
        double t1 = (min - origin) * inv;
        double t2 = (max - origin) * inv;
        double sign = -1.0;
        if (t1 > t2) {
            double swap = t1;
            t1 = t2;
            t2 = swap;
            sign = 1.0;
        }
        out[0] = t1;
        out[1] = t2;
        out[2] = sign;
        out[3] = 1;
        return true;
    }
}
