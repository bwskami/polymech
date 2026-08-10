package com.mss.polymech.powergrid;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 电网方块接口。
 * <p>
 * 实现此接口的方块在世界中提供电气节点，可被线轴连接电线。
 * 节点ID与本地坐标（相对方块原点，0~1范围）的映射由方块自身定义。
 * </p>
 */
public interface GridNodeBlock {

    /**
     * 返回该方块状态提供的所有节点（节点ID → 节点本地坐标）。
     *
     * @param state 方块状态
     * @return 节点ID到本地坐标的映射；无节点时返回空Map
     */
    Map<Integer, Vec3> getNodePositions(BlockState state);
}
