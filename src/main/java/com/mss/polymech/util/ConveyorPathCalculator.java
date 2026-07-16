package com.mss.polymech.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 传送带平面路径计算器。
 * <p>
 * 仅支持 XZ 平面（水平方向）的路径计算，不允许 Y 轴变化。
 * 路径采用 L 形走法：先走长轴，再走短轴。
 * </p>
 */
public class ConveyorPathCalculator {

    /**
     * 计算从 start 到 end 的平面路径。
     * 如果两个位置不在同一 Y 层，返回空列表。
     *
     * @return 路径上的所有方块位置（包含起点和终点，不包含起点时需手动跳过）
     */
    public static List<BlockPos> calculatePath(BlockPos start, BlockPos end) {
        List<BlockPos> path = new ArrayList<>();

        if (start.getY() != end.getY()) {
            return path; // 不允许跨 Y 层
        }

        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();

        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);

        if (absDx == 0 && absDz == 0) {
            return path; // 起点和终点相同
        }

        path.add(start);

        if (absDx == 0 || absDz == 0) {
            // 单轴直线
            Direction dir;
            int distance;
            if (absDx > 0) {
                dir = dx > 0 ? Direction.EAST : Direction.WEST;
                distance = absDx;
            } else {
                dir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
                distance = absDz;
            }
            BlockPos current = start;
            for (int i = 0; i < distance; i++) {
                current = current.relative(dir);
                path.add(current);
            }
        } else {
            // 两轴 L 形：先走 X，再走 Z
            Direction firstDir = dx > 0 ? Direction.EAST : Direction.WEST;
            Direction secondDir = dz > 0 ? Direction.SOUTH : Direction.NORTH;

            BlockPos current = start;
            for (int i = 0; i < absDx; i++) {
                current = current.relative(firstDir);
                path.add(current);
            }
            for (int i = 0; i < absDz; i++) {
                current = current.relative(secondDir);
                path.add(current);
            }
        }

        return path;
    }

    /**
     * 获取路径中每个位置应该朝向的方向。
     * 对于 L 形路径，每个线段上的方块面朝该线段的移动方向。
     *
     * @param path  完整路径列表（含起点）
     * @param start 起点
     * @param end   终点
     * @return 每个位置对应的朝向，与 path 列表一一对应
     */
    public static List<Direction> calculateFacings(List<BlockPos> path, BlockPos start, BlockPos end) {
        List<Direction> facings = new ArrayList<>();
        if (path.size() < 2) return facings;

        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        int absDx = Math.abs(dx);
        int absDz = Math.abs(dz);

        Direction firstDir = dx > 0 ? Direction.EAST : Direction.WEST;
        Direction secondDir = dz > 0 ? Direction.SOUTH : Direction.NORTH;

        if (absDx == 0 || absDz == 0) {
            // 单轴：全部同一方向
            Direction dir;
            if (absDx > 0) {
                dir = dx > 0 ? Direction.EAST : Direction.WEST;
            } else {
                dir = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
            for (int i = 0; i < path.size(); i++) {
                facings.add(dir);
            }
        } else {
            // L 形：第一段面朝 firstDir，转角处及之后面朝 secondDir
            // path[0]=起点, path[1..absDx]=X步, path[absDx+1..]=Z步
            // 起点 + 前 absDx-1 个 X 步面朝 firstDir，最后1个 X 步 + 全部 Z 步面朝 secondDir
            for (int i = 0; i < absDx; i++) {
                facings.add(firstDir);
            }
            for (int i = 0; i <= absDz; i++) {
                facings.add(secondDir);
            }
        }

        return facings;
    }
}