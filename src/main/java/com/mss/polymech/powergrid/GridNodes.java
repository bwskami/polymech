package com.mss.polymech.powergrid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 电网节点工具类。
 * <p>
 * 提供节点世界坐标查询、最近节点查找（拉线交互命中判定）等静态工具。
 * </p>
 */
public final class GridNodes {

    private GridNodes() {}

    /** 默认节点命中判定阈值（格） */
    public static final float NODE_HIT_THRESHOLD = 1.5f;

    /**
     * 获取节点在世界中的坐标。
     *
     * @param level 世界
     * @param node  节点
     * @return 节点世界坐标；方块不是电网方块或节点不存在时返回null
     */
    public static Vec3 getNodePosition(Level level, GridNode node) {
        BlockState state = level.getBlockState(node.sourcePos());
        if (!(state.getBlock() instanceof GridNodeBlock gridBlock))
            return null;
        Map<Integer, Vec3> positions = gridBlock.getNodePositions(state);
        Vec3 local = positions.get(node.nodeId());
        if (local == null)
            return null;
        BlockPos pos = node.sourcePos();
        return local.add(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * 计算两节点之间的欧氏距离（格）。
     * 节点缺失时返回极大值（判定失败）。
     */
    public static double distanceBetween(Level level, GridNode n1, GridNode n2) {
        Vec3 p1 = getNodePosition(level, n1);
        Vec3 p2 = getNodePosition(level, n2);
        if (p1 == null || p2 == null)
            return Double.MAX_VALUE;
        return p1.distanceTo(p2);
    }

    /**
     * 查找点击位置附近最近的节点（仿Create-Electro-Energetics的closestNode）。
     * <p>
     * 在点击位置周围 2×2×2 的方块邻域内搜索所有电网节点，
     * 返回与点击位置距离最近且在阈值内的节点。
     * </p>
     *
     * @param level       世界
     * @param clickedPos  点击的世界坐标（射线命中位置）
     * @param threshold   命中阈值（格）
     * @return 命中的节点；未命中返回null
     */
    public static GridNode closestNode(Level level, Vec3 clickedPos, float threshold) {
        GridNode closest = null;
        double closestDist = threshold;
        BlockPos center = BlockPos.containing(clickedPos);

        for (BlockPos offset : searchOffsets(clickedPos)) {
            BlockPos pos = center.offset(offset);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof GridNodeBlock gridBlock))
                continue;
            for (Map.Entry<Integer, Vec3> e : gridBlock.getNodePositions(state).entrySet()) {
                Vec3 nodePos = e.getValue().add(pos.getX(), pos.getY(), pos.getZ());
                double dist = nodePos.distanceTo(clickedPos);
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = new GridNode(e.getKey(), pos);
                }
            }
        }
        return closest;
    }

    /** 生成点击位置周围的搜索偏移（按点击位置所在半区偏向最近邻域） */
    private static List<BlockPos> searchOffsets(Vec3 clickedPos) {
        List<BlockPos> offsets = new ArrayList<>(8);
        // 用 floor 取小数部分保证 ∈ [0,1)（负数坐标下 % 会返回负值导致偏移方向判断错误）
        int xDir = (clickedPos.x() - Math.floor(clickedPos.x())) < 0.5 ? -1 : 1;
        int yDir = (clickedPos.y() - Math.floor(clickedPos.y())) < 0.5 ? -1 : 1;
        int zDir = (clickedPos.z() - Math.floor(clickedPos.z())) < 0.5 ? -1 : 1;
        for (int x = 0; x < 2; x++)
            for (int y = 0; y < 2; y++)
                for (int z = 0; z < 2; z++)
                    offsets.add(new BlockPos(x * xDir, y * yDir, z * zDir));
        return offsets;
    }

    /**
     * 生成两节点之间带弧垂的电线路径点。
     * <p>
     * 移植自Create-Electro-Energetics的QuadraticWireHelper.cablePoints（抛物线近似悬链线）：
     * yOffset = a * x * (x - resolution)，a = (0.05/distance) * dip。
     * 最大下坠 ≈ 0.05 * distance * dip 格。
     * </p>
     *
     * @param pos1     端点1世界坐标
     * @param pos2     端点2世界坐标
     * @param dip      弧垂系数（电线类型sag）
     * @param detail   细分步长（越大顶点越少，用于远距离降采样）
     * @return 路径点列表（含两端点）；距离超过1000格时返回空列表防止性能问题
     */
    public static List<Vec3> cablePoints(Vec3 pos1, Vec3 pos2, float dip, float detail) {
        float distance = (float) pos1.distanceTo(pos2);
        if (distance > 1000 || distance < 0.01f)
            return distance > 1000 ? List.of() : List.of(pos1, pos2);

        double resolution = distance * 2;
        double invResolution = 1 / resolution;
        int totalPoints = (int) Math.ceil(resolution / detail);
        int ppp = Math.max(1, (int) Math.ceil(resolution / totalPoints));

        List<Vec3> points = new ArrayList<>(totalPoints + 1);
        float a = (0.05f / distance) * dip;
        for (int x = 0; x < resolution; x++) {
            float particleLevel = a * x * (x - (float) resolution);
            double pX = (pos2.x - pos1.x) * (invResolution) * x + pos1.x;
            double pY = (pos2.y - pos1.y) * (invResolution) * x + pos1.y + particleLevel;
            double pZ = (pos2.z - pos1.z) * (invResolution) * x + pos1.z;
            if (x % ppp == 0)
                points.add(new Vec3(pX, pY, pZ));
        }
        return points;
    }
}
