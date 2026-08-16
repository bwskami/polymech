package com.mss.polymech.powergrid;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 电线瞄准工具。
 * <p>
 * 电线不是方块，无法被原版射线命中，因此剪线钳需要把玩家视线与
 * 带弧垂的电线路径点做最近距离判定，找出准星瞄准的那一段连接。
 * 服务端与客户端共用本类，保证剪断判定一致。
 * </p>
 */
public final class WireTargeting {

    /** 剪线钳可瞄准/剪断的最大距离（格） */
    public static final double TARGET_REACH = 6.0;

    /** 瞄准时电线路径点的细分步长（格） */
    private static final float TARGET_DETAIL = 0.5F;

    private WireTargeting() {}

    /**
     * 服务端/客户端通用入口：沿玩家视线在给定连接集合中查找目标。
     *
     * @param level       世界
     * @param player      玩家
     * @param connections 要搜索的电线连接集合（服务端用 WorldPowerGrid，客户端用 ClientWireCache）
     * @return 最近命中电线；未命中返回 null
     */
    @Nullable
    public static GridConnection findTarget(Level level, Player player, Collection<GridConnection> connections) {
        Vec3 eye = player.getEyePosition(1.0F);
        Vec3 look = player.getViewVector(1.0F);
        double blockClip = blockClipDistance(level, player, eye, look, TARGET_REACH);
        return findConnection(level, connections, eye, look, TARGET_REACH, blockClip);
    }

    /**
     * 在连接集合中查找与视线最近的电线。
     *
     * @param level       世界
     * @param connections 连接集合
     * @param eye         玩家眼睛位置
     * @param look        视线方向（未归一化也可）
     * @param reach       最大瞄准距离
     * @param blockClip   方块遮挡距离（超过该距离的电线段视为被挡住）
     * @return 最近命中电线；未命中返回 null
     */
    @Nullable
    public static GridConnection findConnection(Level level, Collection<GridConnection> connections,
                                                 Vec3 eye, Vec3 look, double reach, double blockClip) {
        Vec3 dir = look.normalize();
        Vec3 end = eye.add(dir.scale(reach));
        GridConnection best = null;
        double bestT = Double.MAX_VALUE;

        for (GridConnection connection : connections) {
            Vec3 p1 = GridNodes.getNodePosition(level, connection.node1());
            Vec3 p2 = GridNodes.getNodePosition(level, connection.node2());
            if (p1 == null || p2 == null)
                continue;

            double threshold = Math.max(0.18D, connection.wireType().getThickness() + 0.14D);
            // 快速剔除：视线完全不可能穿过该电线的包围盒
            if (new net.minecraft.world.phys.AABB(p1, p2).inflate(threshold + 0.5).clip(eye, end) == null)
                continue;

            List<Vec3> path = new ArrayList<>(GridNodes.cablePoints(
                    p1, p2, connection.wireType().getSag(), TARGET_DETAIL));
            if (path.isEmpty())
                continue;
            if (path.get(path.size() - 1).distanceToSqr(p2) > 1.0e-8)
                path.add(p2);

            for (Vec3 point : path) {
                double t = point.subtract(eye).dot(dir);
                if (t < 0 || t > reach)
                    continue;
                // 方块遮挡：电线位于方块命中点之后时不可选
                if (t > blockClip + 0.25)
                    continue;

                Vec3 proj = eye.add(dir.scale(t));
                double distSqr = point.distanceToSqr(proj);
                if (distSqr <= threshold * threshold && t < bestT) {
                    bestT = t;
                    best = connection;
                }
            }
        }
        return best;
    }

    /**
     * 计算玩家视线到第一个碰撞方块的遮挡距离。
     * 未命中方块时返回 reach。
     */
    public static double blockClipDistance(Level level, Player player, Vec3 eye, Vec3 look, double reach) {
        Vec3 end = eye.add(look.normalize().scale(reach));
        BlockHitResult hit = level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (hit.getType() == HitResult.Type.MISS)
            return reach;
        return eye.distanceTo(hit.getLocation());
    }

    /** 获取节点文本标签（含方块内节点ID） */
    public static String nodeLabel(GridNode node) {
        BlockPos pos = node.sourcePos();
        return node.nodeId() == 0
                ? pos.toShortString()
                : pos.toShortString() + " #" + node.nodeId();
    }
}
