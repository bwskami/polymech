package com.mss.polymech.powergrid;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
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

    /**
     * 指定 nodeId 节点所在个体/部位的碰撞盒（方块内局部坐标，0~1范围）。
     * <p>
     * 一格多节点的方块（如连接器）返回该节点对应个体的碰撞箱，
     * 使选择框能精确框住选中的那一个个体；返回 null 时渲染器退回整格。
     * </p>
     *
     * @param state  方块状态
     * @param nodeId 方块内节点ID
     * @return 该节点所在个体的碰撞盒（局部坐标）；无专属形状或节点不存在时返回 null
     */
    default AABB getNodeBox(BlockState state, int nodeId) {
        return null;
    }
}
