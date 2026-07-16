package com.mss.polymech.block.entity.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

/**
 * 上坡传送带运输策略。
 * <p>
 * 物品沿坡面向上运动：水平分量 + 垂直向上分量。
 * 8 步斜坡，每步升高 2 像素（0.125 格），所以垂直速度 = 水平速度 / 8。
 * 拾取范围：物品在上坡传送带表面附近。
 * </p>
 */
public class UpSlopeTransportHandler implements IItemTransportHandler {

    /** 水平运输速度（方块/秒） */
    private static final double SPEED = 0.5;

    /** 上坡斜率：水平移动 16px 对应垂直移动 2px = 1/8 */
    private static final double SLOPE_RATIO = 1.0 / 8.0;

    @Override
    public double[] computeMotion(Level level, BlockPos pos, Direction facing, ItemEntity item) {
        double speed = getSpeed();
        return new double[]{
                facing.getStepX() * speed,
                speed * SLOPE_RATIO,  // 向上分量
                facing.getStepZ() * speed
        };
    }

    @Override
    public boolean shouldPickup(Level level, BlockPos pos, Direction facing, ItemEntity item) {
        double localY = item.getY() - pos.getY();
        double localZ = getLocalCoordinate(pos, facing, item);

        // 上坡表面高度是变化的：从后方的 0.25（4/16）到前方（朝向方向）的 1.0（16/16）
        // 物品应该贴近变化中的坡面
        double surfaceY = computeSlopeSurfaceY(localZ);
        return localY >= surfaceY - 0.1 && localY <= surfaceY + 0.45;
    }

    /**
     * 计算物品在上坡方向上的局部坐标（沿 facing 方向从 0 到 1）。
     */
    private double getLocalCoordinate(BlockPos pos, Direction facing, ItemEntity item) {
        double worldPos = facing.getAxis() == Direction.Axis.Z
                ? item.getZ()
                : item.getX();
        double blockPos = facing.getAxis() == Direction.Axis.Z
                ? pos.getZ()
                : pos.getX();
        double raw = worldPos - blockPos;
        // 如果是负方向（NORTH/WEST），取反使得局部坐标从 0 到 1
        return facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? raw : (1 - raw);
    }

    /**
     * 计算上坡传送带在局部位置 z (0~1) 处的表面高度。
     * 后方（z=0）高度为 4/16 = 0.25，前方（z=1）高度为 16/16 = 1.0
     */
    private double computeSlopeSurfaceY(double localZ) {
        return (4.0 + 12.0 * localZ) / 16.0;
    }

    @Override
    public double getSpeed() {
        return SPEED;
    }
}