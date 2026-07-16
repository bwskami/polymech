package com.mss.polymech.block.entity.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

/**
 * 下坡传送带运输策略。
 * <p>
 * 物品沿坡面向下运动：水平分量 + 垂直向下分量。
 * 8 步斜坡，每步降低 2 像素（0.125 格），垂直速度 = -水平速度 / 8。
 * 拾取范围：物品在下坡传送带表面附近。
 * </p>
 */
public class DownSlopeTransportHandler implements IItemTransportHandler {

    /** 水平运输速度（方块/秒） */
    private static final double SPEED = 0.5;

    /** 下坡斜率 */
    private static final double SLOPE_RATIO = 1.0 / 8.0;

    @Override
    public double[] computeMotion(Level level, BlockPos pos, Direction facing, ItemEntity item) {
        double speed = getSpeed();
        return new double[]{
                facing.getStepX() * speed,
                -speed * SLOPE_RATIO, // 向下分量
                facing.getStepZ() * speed
        };
    }

    @Override
    public boolean shouldPickup(Level level, BlockPos pos, Direction facing, ItemEntity item) {
        double localY = item.getY() - pos.getY();
        double localZ = getLocalCoordinate(pos, facing, item);

        // 下坡表面高度：前方（z=1）高度为 4/16 = 0.25，后方（z=0）高度为 16/16 = 1.0
        double surfaceY = computeSlopeSurfaceY(localZ);
        return localY >= surfaceY - 0.1 && localY <= surfaceY + 0.45;
    }

    /**
     * 计算物品在下坡方向上的局部坐标（沿 facing 方向从 0 到 1）。
     */
    private double getLocalCoordinate(BlockPos pos, Direction facing, ItemEntity item) {
        double worldPos = facing.getAxis() == Direction.Axis.Z
                ? item.getZ()
                : item.getX();
        double blockPos = facing.getAxis() == Direction.Axis.Z
                ? pos.getZ()
                : pos.getX();
        double raw = worldPos - blockPos;
        return facing.getAxisDirection() == Direction.AxisDirection.POSITIVE ? raw : (1 - raw);
    }

    /**
     * 计算下坡传送带在局部位置 z (0~1) 处的表面高度。
     * 后方（z=0）高度为 16/16 = 1.0，前方（z=1）高度为 4/16 = 0.25
     */
    private double computeSlopeSurfaceY(double localZ) {
        return (16.0 - 12.0 * localZ) / 16.0;
    }

    @Override
    public double getSpeed() {
        return SPEED;
    }
}