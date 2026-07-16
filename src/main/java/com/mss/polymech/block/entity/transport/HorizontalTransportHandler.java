package com.mss.polymech.block.entity.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

/**
 * 水平传送带运输策略。
 * <p>
 * 物品在水平传送带上沿传送带朝向水平移动，没有垂直分量。
 * 拾取范围：传送带表面上方 0.05~0.5 格的掉落物。
 * </p>
 */
public class HorizontalTransportHandler implements IItemTransportHandler {

    /** 运输速度（方块/秒），Minecraft 中 0.5 方块/秒 ≈ 10 格/20 秒，感觉适中 */
    private static final double SPEED = 0.5;

    /** 拾取检测：Y 轴最低高度（相对于方块底部） */
    private static final double PICKUP_Y_MIN = 0.05;

    /** 拾取检测：Y 轴最高高度（相对于方块底部） */
    private static final double PICKUP_Y_MAX = 0.45;

    /** 传送带表面高度（方块底部起） */
    private static final double BELT_SURFACE_Y = 4.0 / 16.0; // 0.25

    @Override
    public double[] computeMotion(Level level, BlockPos pos, Direction facing, ItemEntity item) {
        double speed = getSpeed();
        return new double[]{
                facing.getStepX() * speed,
                0,
                facing.getStepZ() * speed
        };
    }

    @Override
    public boolean shouldPickup(Level level, BlockPos pos, Direction facing, ItemEntity item) {
        double localY = item.getY() - pos.getY();
        return localY >= PICKUP_Y_MIN && localY <= PICKUP_Y_MAX;
    }

    @Override
    public double getSpeed() {
        return SPEED;
    }
}