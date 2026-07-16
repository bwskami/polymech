package com.mss.polymech.block.entity.transport;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;

/**
 * 传送带物品运输策略接口。
 * <p>
 * 策略模式：不同类型的传送带（水平/上坡/下坡）有不同的物品运输方式。
 * 每个实现类负责计算掉落物在对应类型传送带上的运动速度和方向。
 * </p>
 */
public interface IItemTransportHandler {

    /**
     * 计算掉落物在传送带上应该获得的速度向量。
     *
     * @param level    当前世界
     * @param pos      传送带方块位置
     * @param facing   传送带朝向
     * @param item     掉落物实体
     * @return 要施加给掉落物的速度向量（三维分量）
     */
    double[] computeMotion(Level level, BlockPos pos, Direction facing, ItemEntity item);

    /**
     * 判断掉落物是否应该被当前传送带拾取/接管。
     * <p>
     * 水平传送带：物品在传送带表面上（y在方块底部+0.25到+0.5之间）
     * 上坡传送带：物品在传送带末端较高位置
     * 下坡传送带：物品在传送带末端较低位置
     * </p>
     *
     * @param level    当前世界
     * @param pos      传送带方块位置
     * @param facing   传送带朝向
     * @param item     掉落物实体
     * @return true 如果该传送带应该运输此掉落物
     */
    boolean shouldPickup(Level level, BlockPos pos, Direction facing, ItemEntity item);

    /**
     * 获取传送带的运输速度（方块/秒）。
     */
    double getSpeed();
}